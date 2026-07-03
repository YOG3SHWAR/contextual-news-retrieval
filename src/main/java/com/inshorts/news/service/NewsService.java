package com.inshorts.news.service;

import com.inshorts.news.integration.geo.GeoProvider;
import com.inshorts.news.integration.search.SearchProvider;
import com.inshorts.news.repository.ArticleQueryRepository;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Retrieval orchestration + ranking (design §2.2, §6). One method per strategy;
 * each builds an index-served query (via the repository or a provider seam) and
 * returns ranked {@link ArticleHit}s. Services depend on the {@link SearchProvider}
 * / {@link GeoProvider} seams, not on Postgres directly.
 */
@Service
@RequiredArgsConstructor
public class NewsService {

    private final ArticleQueryRepository queryRepository;
    private final SearchProvider searchProvider;
    private final GeoProvider geoProvider;

    /** R1: articles in a category (case-insensitive), newest first. */
    public List<ArticleHit> byCategory(String category, int limit) {
        String normalized = category.toLowerCase(Locale.ROOT);
        return wrap(queryRepository.findByCategory(normalized, limit));
    }

    /** R2: articles at or above a relevance threshold, highest score first. */
    public List<ArticleHit> byScore(double threshold, int limit) {
        return wrap(queryRepository.findByScore(threshold, limit));
    }

    /** R4: articles from a source (case-insensitive), newest first. */
    public List<ArticleHit> bySource(String source, int limit) {
        return wrap(queryRepository.findBySource(source, limit));
    }

    /** R3: blended full-text + relevance search. */
    public List<ArticleHit> search(String query, int limit) {
        return wrap(searchProvider.search(query, limit));
    }

    /** R5: articles within a radius, nearest first, carrying distance_km. */
    public List<ArticleHit> nearby(double lat, double lon, double radiusKm, int limit) {
        return geoProvider.nearby(lat, lon, radiusKm, limit).stream()
                .map(g -> ArticleHit.of(g.article(), g.distanceKm()))
                .toList();
    }

    private static List<ArticleHit> wrap(List<com.inshorts.news.domain.Article> articles) {
        return articles.stream().map(ArticleHit::of).toList();
    }
}
