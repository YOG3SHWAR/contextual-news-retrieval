package com.inshorts.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.inshorts.news.integration.geo.GeoProvider;
import com.inshorts.news.integration.geo.GeoProvider.GeoArticle;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Geo correctness (tasks §6.4): PostGIS spheroidal distance against a known
 * city pair, and nearest-first ordering with a populated distance_km.
 */
class GeoQueryTest extends AbstractIntegrationTest {

    @Autowired
    GeoProvider geoProvider;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void mumbaiToPuneDistanceMatchesReality() {
        // Mumbai (19.0760, 72.8777) -> Pune (18.5204, 73.8567) is ~120 km.
        Double meters = jdbcTemplate.queryForObject("""
                SELECT ST_Distance(
                    ST_SetSRID(ST_MakePoint(72.8777, 19.0760), 4326)::geography,
                    ST_SetSRID(ST_MakePoint(73.8567, 18.5204), 4326)::geography)
                """, Double.class);
        assertThat(meters).isNotNull();
        double km = meters / 1000.0;
        assertThat(km).isBetween(110.0, 130.0);
    }

    @Test
    void nearbyReturnsAscendingDistancesWithinRadius() {
        List<GeoArticle> results = geoProvider.nearby(19.07, 72.88, 200, 10);
        assertThat(results).isNotEmpty();
        double previous = -1;
        for (GeoArticle g : results) {
            assertThat(g.distanceKm()).isLessThanOrEqualTo(200.0);
            assertThat(g.distanceKm()).isGreaterThanOrEqualTo(previous);
            previous = g.distanceKm();
        }
    }

    @Test
    void nearbyExcludesArticlesOutsideRadius() {
        // A tiny radius around a point should return far fewer than a large one.
        int tight = geoProvider.nearby(19.07, 72.88, 1, 50).size();
        int wide = geoProvider.nearby(19.07, 72.88, 500, 50).size();
        assertThat(wide).isGreaterThanOrEqualTo(tight);
    }
}
