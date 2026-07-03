package com.inshorts.news.service;

import com.inshorts.news.domain.Article;

/**
 * A single retrieval result flowing through the service layer: the matched
 * {@link Article} plus an optional {@code distanceKm} (present only for geo
 * strategies). Enrichment (Phase 3) mutates the article's {@code llmSummary}
 * before it is mapped to the API DTO.
 */
public record ArticleHit(Article article, Double distanceKm) {

    public static ArticleHit of(Article article) {
        return new ArticleHit(article, null);
    }

    public static ArticleHit of(Article article, Double distanceKm) {
        return new ArticleHit(article, distanceKm);
    }
}
