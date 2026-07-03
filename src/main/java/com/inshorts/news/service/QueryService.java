package com.inshorts.news.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inshorts.news.config.NewsProperties;
import com.inshorts.news.domain.Article;
import com.inshorts.news.integration.cache.CacheService;
import com.inshorts.news.integration.llm.HeuristicIntentExtractor;
import com.inshorts.news.integration.llm.LlmClient;
import com.inshorts.news.integration.llm.model.Intent;
import com.inshorts.news.integration.llm.model.QueryUnderstanding;
import com.inshorts.news.repository.ArticleRowMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * LLM-routed query handling (design §4.2). Turns a natural-language query into a
 * structured understanding (LLM with heuristic fallback, cached), resolves
 * entities to concrete category/source/location filters, AND-combines multiple
 * intents, and ranks by the most specific matched strategy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryService {

    private static final String ARTICLE_COLUMNS =
            "id, title, description, url, publication_date, source_name, "
                    + "categories, categories_norm, relevance_score, latitude, longitude, llm_summary";
    private static final double DEFAULT_SCORE_THRESHOLD = 0.7;

    private final LlmClient llmClient;
    private final HeuristicIntentExtractor heuristic;
    private final CacheService cache;
    private final Vocabulary vocabulary;
    private final CityGazetteer gazetteer;
    private final NewsService newsService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final NewsProperties props;

    /** Result of routing: the ranked hits plus whether understanding was degraded. */
    public record RoutedResult(List<ArticleHit> hits, boolean degraded, String echo) {
    }

    /**
     * Handle a /query request.
     *
     * @param query        raw natural-language query (required)
     * @param lat          optional request latitude (takes precedence for nearby)
     * @param lon          optional request longitude
     * @param limit        clamped result limit
     * @param directIntents optional caller-supplied intents (bypass LLM)
     * @param directEntities optional caller-supplied entities (bypass LLM)
     */
    public RoutedResult handle(String query, Double lat, Double lon, int limit,
                               List<String> directIntents, List<String> directEntities) {
        boolean degraded;
        QueryUnderstanding understanding;

        if ((directIntents != null && !directIntents.isEmpty())
                || (directEntities != null && !directEntities.isEmpty())) {
            // Direct-input escape hatch: deterministic, no LLM (§3.2).
            understanding = fromDirect(directIntents, directEntities, query);
            degraded = false;
        } else if (llmClient.isEnabled()) {
            QueryUnderstanding fromLlm = understandWithLlm(query);
            if (fromLlm != null) {
                understanding = fromLlm;
                degraded = false;
            } else {
                understanding = heuristic.extract(query);
                degraded = true;
            }
        } else {
            understanding = heuristic.extract(query);
            degraded = true;
        }

        List<ArticleHit> hits = route(understanding, query, lat, lon, limit);
        return new RoutedResult(hits, degraded, query);
    }

    // --- understanding ---------------------------------------------------------

    private QueryUnderstanding understandWithLlm(String query) {
        String normalized = query.trim().toLowerCase(Locale.ROOT);
        String key = "qu:" + sha256(normalized);
        try {
            String json = cache.getOrCompute(key, null, () -> {
                try {
                    return objectMapper.writeValueAsString(llmClient.extract(query));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            return json == null ? null : objectMapper.readValue(json, QueryUnderstanding.class);
        } catch (Exception e) {
            log.debug("LLM understanding failed for '{}' — falling back to heuristic: {}",
                    query, e.getMessage());
            return null;
        }
    }

    private QueryUnderstanding fromDirect(List<String> directIntents, List<String> directEntities, String query) {
        List<Intent> intents = new ArrayList<>();
        if (directIntents != null) {
            directIntents.forEach(i -> Intent.parse(i).ifPresent(intents::add));
        }
        List<String> entities = directEntities == null ? List.of() : directEntities;
        List<String> keywords = List.of(query.toLowerCase(Locale.ROOT).split("\\W+"));
        return new QueryUnderstanding(entities, intents, keywords);
    }

    // --- routing ---------------------------------------------------------------

    private List<ArticleHit> route(QueryUnderstanding u, String query, Double lat, Double lon, int limit) {
        Set<Intent> intents = EnumSet.copyOf(u.intents());

        Optional<String> category = intents.contains(Intent.CATEGORY) ? resolveCategory(u) : Optional.empty();
        Optional<String> source = intents.contains(Intent.SOURCE) ? resolveSource(u) : Optional.empty();
        Optional<CityGazetteer.Coordinates> geo =
                intents.contains(Intent.NEARBY) ? resolveLocation(u, lat, lon) : Optional.empty();
        boolean scoreIntent = intents.contains(Intent.SCORE);
        // Search applies when explicitly requested, or when nothing concrete resolved.
        boolean searchIntent = intents.contains(Intent.SEARCH);

        String searchText = deriveSearchText(u, query);

        boolean anyConcrete = category.isPresent() || source.isPresent() || geo.isPresent() || scoreIntent;
        // nearby intent that couldn't resolve a location → fall back to search (§4.2)
        if (intents.contains(Intent.NEARBY) && geo.isEmpty()) {
            searchIntent = true;
        }
        if (!anyConcrete) {
            searchIntent = true;
        }

        int filterCount = (category.isPresent() ? 1 : 0) + (source.isPresent() ? 1 : 0)
                + (geo.isPresent() ? 1 : 0) + (scoreIntent ? 1 : 0) + (searchIntent ? 1 : 0);

        // Single-strategy fast paths reuse the tested NewsService queries
        // (incl. the search ILIKE fallback).
        if (filterCount == 1) {
            if (geo.isPresent()) {
                return newsService.nearby(geo.get().lat(), geo.get().lon(),
                        props.getNearby().getDefaultRadiusKm(), limit);
            }
            if (searchIntent) {
                return newsService.search(searchText, limit);
            }
            if (category.isPresent()) {
                return newsService.byCategory(category.get(), limit);
            }
            if (source.isPresent()) {
                return newsService.bySource(source.get(), limit);
            }
            if (scoreIntent) {
                return newsService.byScore(DEFAULT_SCORE_THRESHOLD, limit);
            }
        }

        // Multi-intent: AND-combine filters in one dynamic, index-served query.
        return executeCombined(category, source, geo, scoreIntent, searchIntent, searchText, limit);
    }

    private List<ArticleHit> executeCombined(Optional<String> category, Optional<String> source,
                                             Optional<CityGazetteer.Coordinates> geo, boolean scoreIntent,
                                             boolean searchIntent, String searchText, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT ").append(ARTICLE_COLUMNS);
        if (geo.isPresent()) {
            sql.append(", ST_Distance(geog, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_m");
            params.add(geo.get().lon());
            params.add(geo.get().lat());
        }
        sql.append(" FROM articles WHERE 1=1");
        if (category.isPresent()) {
            sql.append(" AND categories_norm @> ARRAY[?]::text[]");
            params.add(category.get());
        }
        if (source.isPresent()) {
            sql.append(" AND lower(source_name) = lower(?)");
            params.add(source.get());
        }
        if (scoreIntent) {
            sql.append(" AND relevance_score >= ?");
            params.add(DEFAULT_SCORE_THRESHOLD);
        }
        if (searchIntent) {
            sql.append(" AND search_tsv @@ plainto_tsquery('english', ?)");
            params.add(searchText);
        }
        if (geo.isPresent()) {
            sql.append(" AND geog IS NOT NULL AND ST_DWithin(geog, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)");
            params.add(geo.get().lon());
            params.add(geo.get().lat());
            params.add(props.getNearby().getDefaultRadiusKm() * 1000.0);
        }

        // Rank by the most specific matched strategy (nearby > search > source/category > score).
        boolean geoRank = geo.isPresent();
        boolean searchRank = !geoRank && searchIntent;
        boolean dateRank = !geoRank && !searchRank && (source.isPresent() || category.isPresent());
        if (geoRank) {
            sql.append(" ORDER BY distance_m ASC, id ASC");
        } else if (searchRank) {
            sql.append(" ORDER BY (? * ts_rank(search_tsv, plainto_tsquery('english', ?), 32)"
                    + " + ? * coalesce(relevance_score, 0)) DESC, id DESC");
            params.add(props.getSearch().getWeightText());
            params.add(searchText);
            params.add(props.getSearch().getWeightRelevance());
        } else if (dateRank) {
            sql.append(" ORDER BY publication_date DESC NULLS LAST, relevance_score DESC NULLS LAST, id DESC");
        } else {
            sql.append(" ORDER BY relevance_score DESC NULLS LAST, id DESC");
        }
        sql.append(" LIMIT ?");
        params.add(limit);

        boolean hasGeo = geo.isPresent();
        List<ArticleHit> hits = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> {
            Article a = ArticleRowMapper.mapArticle(rs);
            Double distanceKm = hasGeo ? rs.getDouble("distance_m") / 1000.0 : null;
            return new ArticleHit(a, distanceKm);
        }, params.toArray());
        return hits;
    }

    // --- entity resolution -----------------------------------------------------

    private Optional<String> resolveCategory(QueryUnderstanding u) {
        Set<String> known = vocabulary.categories();
        for (String token : allTokens(u)) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (known.contains(lower)) {
                return Optional.of(lower);
            }
        }
        return Optional.empty();
    }

    private Optional<String> resolveSource(QueryUnderstanding u) {
        Set<String> known = vocabulary.sources();
        // exact (case-insensitive) match first
        for (String entity : u.entities()) {
            if (known.contains(entity.toLowerCase(Locale.ROOT))) {
                return Optional.of(entity);
            }
        }
        // substring match (entity contains a known source or vice-versa)
        for (String entity : u.entities()) {
            String lower = entity.toLowerCase(Locale.ROOT);
            for (String src : known) {
                if (!src.isBlank() && (lower.contains(src) || src.contains(lower))) {
                    return Optional.of(src);
                }
            }
        }
        return Optional.empty();
    }

    private Optional<CityGazetteer.Coordinates> resolveLocation(QueryUnderstanding u, Double lat, Double lon) {
        if (lat != null && lon != null) {
            return Optional.of(new CityGazetteer.Coordinates(lat, lon));
        }
        for (String entity : u.entities()) {
            Optional<CityGazetteer.Coordinates> c = gazetteer.resolve(entity);
            if (c.isPresent()) {
                return c;
            }
        }
        return Optional.empty();
    }

    private String deriveSearchText(QueryUnderstanding u, String query) {
        if (u.keywords() != null && !u.keywords().isEmpty()) {
            return String.join(" ", u.keywords());
        }
        return query;
    }

    private List<String> allTokens(QueryUnderstanding u) {
        List<String> all = new ArrayList<>(u.entities());
        all.addAll(u.keywords());
        return all;
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
