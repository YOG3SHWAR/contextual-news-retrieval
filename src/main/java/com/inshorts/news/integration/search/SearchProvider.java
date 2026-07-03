package com.inshorts.news.integration.search;

import com.inshorts.news.domain.Article;
import java.util.List;

/**
 * Scale seam (design §1.1). Full-text search + ranking. The seed adapter is
 * {@code PostgresSearchProvider} (tsvector/ts_rank); an
 * {@code ElasticsearchSearchProvider} can be swapped in later without touching
 * controllers or services.
 */
public interface SearchProvider {

    /**
     * Return up to {@code limit} articles matching {@code query}, ranked best-first.
     */
    List<Article> search(String query, int limit);
}
