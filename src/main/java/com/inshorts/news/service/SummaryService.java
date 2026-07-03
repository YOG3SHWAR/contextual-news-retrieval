package com.inshorts.news.service;

import com.inshorts.news.integration.cache.CacheService;
import com.inshorts.news.integration.llm.LlmClient;
import com.inshorts.news.domain.Article;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Generates and caches {@code llm_summary} for a result page (design §4.3).
 *
 * <p>Resolution order per article: Redis/local cache → DB (lazily persisted) →
 * LLM. Successful LLM summaries are cached (no TTL — articles are immutable) and
 * written back to the {@code articles} row. Enrichment runs on a bounded pool
 * (the shared {@code summaryExecutor} bulkhead) so it never floods the LLM or
 * steals web threads. If any summary can't be produced, the response is flagged
 * {@code degraded} but still returns 200.
 */
@Slf4j
@Service
public class SummaryService {

    private final CacheService cache;
    private final LlmClient llmClient;
    private final JdbcTemplate jdbcTemplate;
    private final ExecutorService pool;
    private final MeterRegistry meterRegistry;

    public SummaryService(CacheService cache, LlmClient llmClient, JdbcTemplate jdbcTemplate,
                          @Qualifier("summaryExecutor") ExecutorService summaryExecutor,
                          MeterRegistry meterRegistry) {
        this.cache = cache;
        this.llmClient = llmClient;
        this.jdbcTemplate = jdbcTemplate;
        this.pool = summaryExecutor;
        this.meterRegistry = meterRegistry;
    }

    /** Per-request tally of where each summary was resolved from (for the DEBUG line). */
    private static final class Counts {
        final LongAdder cache = new LongAdder();
        final LongAdder db = new LongAdder();
        final LongAdder llm = new LongAdder();
        final LongAdder failed = new LongAdder();
    }

    /**
     * Enrich each hit's article with a summary in place.
     *
     * @return true if the result is degraded (at least one summary is missing)
     */
    public boolean enrich(List<ArticleHit> hits) {
        if (hits.isEmpty()) {
            return false;
        }
        AtomicBoolean degraded = new AtomicBoolean(false);
        Counts counts = new Counts();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (ArticleHit hit : hits) {
            futures.add(pool.submit(() -> {
                String summary = resolve(hit.article(), counts);
                hit.article().setLlmSummary(summary);
                if (summary == null) {
                    degraded.set(true);
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                degraded.set(true);
                log.debug("Summary task failed: {}", e.getMessage());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("Enriched {} article(s): cache={} db={} llm={} failed={} degraded={}",
                    hits.size(), counts.cache.sum(), counts.db.sum(), counts.llm.sum(),
                    counts.failed.sum(), degraded.get());
        }
        return degraded.get();
    }

    private String resolve(Article article, Counts counts) {
        String title = article.getTitle();
        String description = article.getDescription();
        String key = "summary:" + sha256(title + "\n" + description);
        // If the loader below isn't invoked, the value came from the cache tier.
        String[] source = {"cache"};
        String value = cache.getOrCompute(key, null, () -> {
            // DB tier: lazily persisted summary already on the row.
            if (article.getLlmSummary() != null && !article.getLlmSummary().isBlank()) {
                source[0] = "db";
                return article.getLlmSummary();
            }
            if (!llmClient.isEnabled()) {
                source[0] = "failed";
                return null;
            }
            try {
                Optional<String> generated = llmClient.summarize(title, description);
                if (generated.isPresent()) {
                    persist(article.getId(), generated.get());
                    source[0] = "llm";
                    return generated.get();
                }
            } catch (Exception e) {
                log.debug("LLM summarize failed for {}: {}", article.getId(), e.getMessage());
            }
            source[0] = "failed";
            return null;
        });
        record(source[0], counts);
        return value;
    }

    private void record(String source, Counts counts) {
        meterRegistry.counter("news.summary.resolution", "source", source).increment();
        switch (source) {
            case "cache" -> counts.cache.increment();
            case "db" -> counts.db.increment();
            case "llm" -> counts.llm.increment();
            default -> counts.failed.increment();
        }
    }

    private void persist(java.util.UUID id, String summary) {
        try {
            jdbcTemplate.update("UPDATE articles SET llm_summary = ? WHERE id = ?", summary, id);
        } catch (Exception e) {
            log.debug("Failed to persist summary for {}: {}", id, e.getMessage());
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }
}
