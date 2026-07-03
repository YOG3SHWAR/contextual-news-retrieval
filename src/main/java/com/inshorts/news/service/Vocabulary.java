package com.inshorts.news.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Known category labels and source names, loaded lazily from the corpus and
 * cached in memory. Used by {@code HeuristicIntentExtractor} and
 * {@link QueryService} to resolve which entity/keyword corresponds to a real
 * category or source (both stored lowercased for case-insensitive matching).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Vocabulary {

    private final JdbcTemplate jdbcTemplate;

    private volatile Set<String> categories;
    private volatile Set<String> sources;

    public Set<String> categories() {
        Set<String> c = categories;
        if (c == null) {
            c = load();
        }
        return c;
    }

    public Set<String> sources() {
        if (sources == null) {
            load();
        }
        return sources;
    }

    private synchronized Set<String> load() {
        if (categories != null && sources != null) {
            return categories;
        }
        try {
            List<String> cats = jdbcTemplate.queryForList(
                    "SELECT DISTINCT unnest(categories_norm) FROM articles", String.class);
            List<String> srcs = jdbcTemplate.queryForList(
                    "SELECT DISTINCT lower(source_name) FROM articles WHERE source_name IS NOT NULL",
                    String.class);
            Set<String> c = new HashSet<>();
            cats.forEach(s -> { if (s != null) c.add(s.toLowerCase(Locale.ROOT)); });
            Set<String> s = new HashSet<>(srcs);
            this.categories = c;
            this.sources = s;
            log.info("Vocabulary loaded: {} categories, {} sources", c.size(), s.size());
        } catch (Exception e) {
            log.warn("Failed to load vocabulary; using empty sets", e);
            this.categories = Set.of();
            this.sources = Set.of();
        }
        return categories;
    }

    /** Force a reload (e.g. after ingestion). */
    public void invalidate() {
        this.categories = null;
        this.sources = null;
    }
}
