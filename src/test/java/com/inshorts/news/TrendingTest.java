package com.inshorts.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.inshorts.news.service.ArticleHit;
import com.inshorts.news.service.TrendingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Trending (tasks §6.7): the simulator seeds events, the service returns
 * location-aware ranked results, and repeat calls (served from the geohash
 * cache) are consistent.
 */
class TrendingTest extends AbstractIntegrationTest {

    @Autowired
    TrendingService trendingService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void simulatorSeededEvents() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM user_events", Long.class);
        assertThat(count).isGreaterThan(0L);
    }

    @Test
    void trendingReturnsResultsNearSeededCity() {
        // Mumbai is one of the simulator's clusters → should have trending data.
        List<ArticleHit> results = trendingService.trending(19.0760, 72.8777, 5);
        assertThat(results).isNotEmpty().hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void repeatCallsAreConsistentFromCache() {
        List<ArticleHit> first = trendingService.trending(18.5204, 73.8567, 5);
        List<ArticleHit> second = trendingService.trending(18.5204, 73.8567, 5);
        List<String> firstIds = first.stream().map(h -> h.article().getId().toString()).toList();
        List<String> secondIds = second.stream().map(h -> h.article().getId().toString()).toList();
        assertThat(secondIds).isEqualTo(firstIds);
    }

    @Test
    void remoteLocationWithNoEventsReturnsEmpty() {
        // A point far from any cluster (mid-ocean) has no nearby events.
        List<ArticleHit> results = trendingService.trending(0.0, -40.0, 5);
        assertThat(results).isEmpty();
    }
}
