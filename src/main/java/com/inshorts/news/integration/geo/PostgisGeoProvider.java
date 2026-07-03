package com.inshorts.news.integration.geo;

import com.inshorts.news.domain.Article;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
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
            ORDER BY distance_m ASC, id DESC
            LIMIT ?
            """;

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

    private static final RowMapper<GeoArticle> GEO_ROW_MAPPER = (rs, rowNum) -> {
        Article a = mapArticle(rs);
        double distanceKm = rs.getDouble("distance_m") / 1000.0;
        return new GeoArticle(a, distanceKm);
    };

    static Article mapArticle(ResultSet rs) throws SQLException {
        Article a = new Article();
        a.setId(rs.getObject("id", UUID.class));
        a.setTitle(rs.getString("title"));
        a.setDescription(rs.getString("description"));
        a.setUrl(rs.getString("url"));
        Timestamp ts = rs.getTimestamp("publication_date");
        a.setPublicationDate(ts == null ? null : ts.toLocalDateTime());
        a.setSourceName(rs.getString("source_name"));
        a.setCategories(toStringArray(rs.getArray("categories")));
        a.setCategoriesNorm(toStringArray(rs.getArray("categories_norm")));
        double score = rs.getDouble("relevance_score");
        a.setRelevanceScore(rs.wasNull() ? null : score);
        double lat = rs.getDouble("latitude");
        a.setLatitude(rs.wasNull() ? null : lat);
        double lon = rs.getDouble("longitude");
        a.setLongitude(rs.wasNull() ? null : lon);
        a.setLlmSummary(rs.getString("llm_summary"));
        return a;
    }

    private static String[] toStringArray(Array sqlArray) throws SQLException {
        if (sqlArray == null) {
            return new String[0];
        }
        Object arr = sqlArray.getArray();
        if (arr instanceof String[] s) {
            return s;
        }
        Object[] objs = (Object[]) arr;
        String[] out = new String[objs.length];
        for (int i = 0; i < objs.length; i++) {
            out[i] = objs[i] == null ? null : objs[i].toString();
        }
        return out;
    }
}
