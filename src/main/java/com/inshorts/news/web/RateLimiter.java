package com.inshorts.news.web;

import com.inshorts.news.config.NewsProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Per-client token-bucket rate limiter (design §7) protecting the LLM-backed
 * {@code /query} endpoint. Each client key gets its own bucket; buckets refill
 * greedily over a one-minute window. In-process map is fine at seed scale; a
 * distributed Bucket4j-Redis backend is the documented scale-out.
 */
@Component
public class RateLimiter {

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final int refillPerMinute;

    public RateLimiter(NewsProperties props) {
        this.capacity = props.getRatelimit().getQueryCapacity();
        this.refillPerMinute = props.getRatelimit().getQueryRefillPerMinute();
    }

    /** Try to consume one token for the given client key. */
    public boolean tryConsume(String clientKey) {
        return buckets.computeIfAbsent(clientKey, k -> newBucket()).tryConsume(1);
    }

    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.classic(capacity,
                Refill.greedy(refillPerMinute, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}
