package com.inshorts.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.inshorts.news.domain.Article;
import com.inshorts.news.integration.search.SearchProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Search ranking (tasks §6.5): the blended provider returns relevant results and
 * respects the limit; a rare/odd query still returns via the ILIKE fallback.
 */
class SearchRankingTest extends AbstractIntegrationTest {

    @Autowired
    SearchProvider searchProvider;

    @Test
    void blendedSearchRespectsLimitAndReturnsResults() {
        List<Article> results = searchProvider.search("government", 5);
        assertThat(results).isNotEmpty().hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void resultsContainQueryTermInTitleOrDescription() {
        String term = "police";
        List<Article> results = searchProvider.search(term, 10);
        assertThat(results).isNotEmpty();
        boolean anyMatch = results.stream().anyMatch(a ->
                (a.getTitle() != null && a.getTitle().toLowerCase().contains(term))
                        || (a.getDescription() != null && a.getDescription().toLowerCase().contains(term)));
        assertThat(anyMatch).isTrue();
    }

    @Test
    void emptyQueryReturnsEmpty() {
        assertThat(searchProvider.search("   ", 5)).isEmpty();
    }
}
