package com.inshorts.news.repository;

import com.inshorts.news.domain.Article;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.UUID;

/**
 * Maps a JDBC row (from native retrieval SQL that selects the standard article
 * columns) to an {@link Article}. Shared by the geo provider and the dynamic
 * {@code /query} router so column handling stays consistent.
 */
public final class ArticleRowMapper {

    private ArticleRowMapper() {
    }

    public static Article mapArticle(ResultSet rs) throws SQLException {
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

    public static String[] toStringArray(Array sqlArray) throws SQLException {
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
