package com.inshorts.news.integration.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Two-tier cache (design §10.3): local Caffeine in front of distributed Redis,
 * with per-entry TTL and in-process single-flight on hot keys.
 *
 * <p>Redis is treated as <b>optional</b>: any Redis failure is caught, logged,
 * and degraded to a local-only / recompute path — it never fails the request.
 */
@Slf4j
@Service
public class CacheService {

    /** Sentinel TTL meaning "no expiry" (immutable data, e.g. summaries). */
    private static final Duration NO_EXPIRY = Duration.ofDays(3650);

    // TTL side-table for the local tier (Caffeine's Expiry has no per-put argument).
    private final ConcurrentHashMap<String, Long> localTtlNanos = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    private final StringRedisTemplate redis;
    private final boolean redisConfigured;
    private final Cache<String, String> local;
    private final Counter hits;
    private final Counter misses;
    private final Counter redisErrors;

    public CacheService(ObjectProvider<StringRedisTemplate> redisProvider, MeterRegistry meterRegistry) {
        this.redis = redisProvider.getIfAvailable();
        this.redisConfigured = this.redis != null;
        this.hits = meterRegistry.counter("news.cache.hits");
        this.misses = meterRegistry.counter("news.cache.misses");
        this.redisErrors = meterRegistry.counter("news.cache.redis.errors");
        this.local = Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfter(new Expiry<String, String>() {
                    @Override
                    public long expireAfterCreate(String key, String value, long currentTime) {
                        return localTtlNanos.getOrDefault(key, NO_EXPIRY.toNanos());
                    }

                    @Override
                    public long expireAfterUpdate(String key, String value, long currentTime, long currentDuration) {
                        return localTtlNanos.getOrDefault(key, currentDuration);
                    }

                    @Override
                    public long expireAfterRead(String key, String value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    /** Look up a value, checking local then Redis. Never throws. */
    public Optional<String> get(String key) {
        String v = local.getIfPresent(key);
        if (v != null) {
            hits.increment();
            return Optional.of(v);
        }
        if (redisConfigured) {
            try {
                String r = redis.opsForValue().get(key);
                if (r != null) {
                    putLocal(key, r, NO_EXPIRY);
                    hits.increment();
                    return Optional.of(r);
                }
            } catch (Exception e) {
                redisErrors.increment();
                log.debug("Redis get failed for {} — degrading to miss: {}", key, e.getMessage());
            }
        }
        misses.increment();
        return Optional.empty();
    }

    /** Store a value in both tiers. {@code ttl} null means no expiry. Never throws. */
    public void put(String key, String value, @Nullable Duration ttl) {
        if (value == null) {
            return;
        }
        Duration effective = ttl == null ? NO_EXPIRY : ttl;
        putLocal(key, value, effective);
        if (redisConfigured) {
            try {
                if (ttl == null) {
                    redis.opsForValue().set(key, value);
                } else {
                    redis.opsForValue().set(key, value, ttl);
                }
            } catch (Exception e) {
                redisErrors.increment();
                log.debug("Redis put failed for {} — kept local only: {}", key, e.getMessage());
            }
        }
    }

    /**
     * Return the cached value or compute it under a per-key single-flight lock.
     * The loader may return null (nothing to cache). Never throws from caching.
     */
    public String getOrCompute(String key, @Nullable Duration ttl, Supplier<String> loader) {
        Optional<String> cached = get(key);
        if (cached.isPresent()) {
            return cached.get();
        }
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                Optional<String> again = get(key);
                if (again.isPresent()) {
                    return again.get();
                }
                String value = loader.get();
                if (value != null) {
                    put(key, value, ttl);
                }
                return value;
            } finally {
                locks.remove(key, lock);
            }
        }
    }

    private void putLocal(String key, String value, Duration ttl) {
        localTtlNanos.put(key, ttl.toNanos());
        local.put(key, value);
    }
}
