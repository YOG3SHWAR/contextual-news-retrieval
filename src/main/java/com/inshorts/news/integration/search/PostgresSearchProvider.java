package com.inshorts.news.integration.search;

import com.inshorts.news.config.NewsProperties;
import com.inshorts.news.domain.Article;
import com.inshorts.news.repository.ArticleQueryRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Seed implementation of {@link SearchProvider} backed by Postgres full-text
 * search. Blends {@code ts_rank} with {@code relevance_score}; when the tsquery
 * matches nothing (e.g. stopword-only input), falls back to an {@code ILIKE}
 * substring scan so short/rare queries still return something.
 */
@Component
@RequiredArgsConstructor
public class PostgresSearchProvider implements SearchProvider {

    private final ArticleQueryRepository queryRepository;
    private final NewsProperties props;

    @Override
    public List<Article> search(String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }
        NewsProperties.Search cfg = props.getSearch();
        List<Article> primary = queryRepository.searchBlended(
                q, cfg.getWeightText(), cfg.getWeightRelevance(), limit);
        if (!primary.isEmpty()) {
            return primary;
        }
        String pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return queryRepository.searchIlike(pattern, limit);
    }
}
