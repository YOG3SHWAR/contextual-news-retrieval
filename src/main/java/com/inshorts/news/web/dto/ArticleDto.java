package com.inshorts.news.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Builder;

/**
 * API representation of an article (response contract §4.1).
 *
 * <p>{@code category} is returned as the original label array (the honest
 * superset). {@code llm_summary} is always present (possibly {@code null}).
 * {@code distance_km} is emitted only for geo endpoints (non-null).
 */
@Builder
public record ArticleDto(
        String id,
        String title,
        String description,
        String url,
        @JsonProperty("publication_date") LocalDateTime publicationDate,
        @JsonProperty("source_name") String sourceName,
        List<String> category,
        @JsonProperty("relevance_score") Double relevanceScore,
        @JsonProperty("llm_summary") String llmSummary,
        Double latitude,
        Double longitude,
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("distance_km") Double distanceKm) {
}
