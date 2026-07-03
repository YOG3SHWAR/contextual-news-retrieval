package com.inshorts.news.web.dto;

import com.inshorts.news.domain.Article;
import com.inshorts.news.service.ArticleHit;
import java.util.List;
import org.springframework.stereotype.Component;

/** Maps domain {@link Article} / {@link ArticleHit} to the API {@link ArticleDto}. */
@Component
public class ArticleMapper {

    public ArticleDto toDto(ArticleHit hit) {
        Article a = hit.article();
        return ArticleDto.builder()
                .id(a.getId() == null ? null : a.getId().toString())
                .title(a.getTitle())
                .description(a.getDescription())
                .url(a.getUrl())
                .publicationDate(a.getPublicationDate())
                .sourceName(a.getSourceName())
                .category(a.getCategories() == null ? List.of() : List.of(a.getCategories()))
                .relevanceScore(a.getRelevanceScore())
                .llmSummary(a.getLlmSummary())
                .latitude(a.getLatitude())
                .longitude(a.getLongitude())
                .distanceKm(hit.distanceKm())
                .build();
    }

    public List<ArticleDto> toDtos(List<ArticleHit> hits) {
        return hits.stream().map(this::toDto).toList();
    }
}
