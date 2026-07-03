package com.inshorts.news.integration.geo;

import com.inshorts.news.domain.Article;
import com.inshorts.news.repository.ArticleRowMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Seed implementation of {@link GeoProvider} backed by PostGIS. Uses
 * {@code ST_DWithin} on the GiST-indexed {@code geog} column for the radius
 * filter and {@code ST_Distance} (spheroidal, metres) for ranking; km→m
 * conversion happens at the query edge.
 */
@Component
@RequiredArgsConstructor
public class PostgisGeoProvider implements GeoProvider {

    private static final String NEARBY_SQL = """
            SELECT id, title, description, url, publication_date, source_name,
                   categories, categories_norm, relevance_score, latitude, longitude,
                   llm_summary,
                   ST_Distance(geog, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography) AS distance_m
            FROM articles
            WHERE geog IS NOT NULL
              AND ST_DWithin(geog, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
            ORDER BY distance_m ASC, id ASC
            LIMIT ?
            """;

    private static final RowMapper<GeoProvider.GeoArticle> GEO_ROW_MAPPER = (rs, rowNum) -> {
        Article a = ArticleRowMapper.mapArticle(rs);
        double distanceKm = rs.getDouble("distance_m") / 1000.0;
        return new GeoArticle(a, distanceKm);
    };

    private final JdbcTemplate jdbcTemplate;

    @Override
    public List<GeoArticle> nearby(double lat, double lon, double radiusKm, int limit) {
        double radiusMeters = radiusKm * 1000.0;
        return jdbcTemplate.query(NEARBY_SQL, GEO_ROW_MAPPER,
                lon, lat,            // ST_Distance point
                lon, lat,            // ST_DWithin point
                radiusMeters,
                limit);
    }
}
