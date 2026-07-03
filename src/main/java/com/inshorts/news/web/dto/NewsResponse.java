package com.inshorts.news.web.dto;

import java.util.List;

/**
 * Standard success envelope (response contract §4.1).
 *
 * @param articles the top-N ranked, enriched articles
 * @param total    number of articles returned (not the full match count)
 * @param query    echo of the caller's input or a description of the filter
 * @param degraded true when the LLM/cache fell back
 */
public record NewsResponse(
        List<ArticleDto> articles,
        int total,
        String query,
        boolean degraded) {

    public static NewsResponse of(List<ArticleDto> articles, String query, boolean degraded) {
        return new NewsResponse(articles, articles.size(), query, degraded);
    }
}
