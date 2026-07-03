package com.inshorts.news.ingest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inshorts.news.config.NewsProperties;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads {@code news_data.json} into the {@code articles} table at startup
 * (tasks §1.4). Design goals:
 *
 * <ul>
 *   <li><b>Streaming parse</b> — Jackson pull parser reads one record at a time,
 *       so memory is constant whether the file has 2k or 20M rows.</li>
 *   <li><b>Batched upsert by id</b> — {@code ON CONFLICT (id) DO UPDATE}, so
 *       re-running is idempotent and produces no duplicates. {@code llm_summary}
 *       is deliberately not overwritten, preserving lazily persisted summaries.</li>
 *   <li><b>Normalization</b> — {@code categories_norm} is the lowercased copy;
 *       {@code geog} is set only for valid, non-(0,0) coordinates.</li>
 *   <li><b>Defensive</b> — malformed rows are skipped and logged, never fatal.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataLoader implements ApplicationRunner {

    private static final String UPSERT_SQL = """
            INSERT INTO articles
                (id, title, description, url, publication_date, source_name,
                 categories, categories_norm, relevance_score, latitude, longitude, geog)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                 ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
            ON CONFLICT (id) DO UPDATE SET
                title = EXCLUDED.title,
                description = EXCLUDED.description,
                url = EXCLUDED.url,
                publication_date = EXCLUDED.publication_date,
                source_name = EXCLUDED.source_name,
                categories = EXCLUDED.categories,
                categories_norm = EXCLUDED.categories_norm,
                relevance_score = EXCLUDED.relevance_score,
                latitude = EXCLUDED.latitude,
                longitude = EXCLUDED.longitude,
                geog = EXCLUDED.geog
            """;

    private final NewsProperties props;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        NewsProperties.Ingest cfg = props.getIngest();

        if (cfg.isSkipIfPopulated()) {
            Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM articles", Long.class);
            if (count != null && count > 0) {
                log.info("articles table already populated ({} rows) — skipping ingest", count);
                return;
            }
        }

        long start = System.currentTimeMillis();
        int batchSize = Math.max(1, cfg.getBatchSize());
        List<CleanRecord> buffer = new ArrayList<>(batchSize);
        int loaded = 0;
        int skipped = 0;

        try (InputStream in = openSource(cfg.getFile());
             JsonParser parser = objectMapper.getFactory().createParser(in)) {

            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IllegalStateException("Expected a JSON array at the root of " + cfg.getFile());
            }

            while (parser.nextToken() == JsonToken.START_OBJECT) {
                NewsJsonRecord raw = objectMapper.readValue(parser, NewsJsonRecord.class);
                CleanRecord clean = clean(raw);
                if (clean == null) {
                    skipped++;
                    continue;
                }
                buffer.add(clean);
                if (buffer.size() >= batchSize) {
                    loaded += flush(buffer);
                    buffer.clear();
                }
            }
        }
        if (!buffer.isEmpty()) {
            loaded += flush(buffer);
            buffer.clear();
        }

        log.info("Ingest complete: {} rows upserted, {} skipped (malformed), in {} ms",
                loaded, skipped, System.currentTimeMillis() - start);
    }

    /** Resolve the ingest file from the filesystem first, then the classpath. */
    private InputStream openSource(String file) throws Exception {
        Path path = Path.of(file);
        if (Files.exists(path)) {
            return Files.newInputStream(path);
        }
        ClassPathResource cp = new ClassPathResource(file);
        if (cp.exists()) {
            return cp.getInputStream();
        }
        throw new IllegalStateException("Ingest file not found on filesystem or classpath: " + file);
    }

    /** Validate + normalize a raw record; returns null for malformed rows. */
    private CleanRecord clean(NewsJsonRecord r) {
        if (r == null || isBlank(r.id()) || isBlank(r.title()) || isBlank(r.description())) {
            log.warn("Skipping malformed record (missing id/title/description): id={}",
                    r == null ? null : r.id());
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(r.id().trim());
        } catch (IllegalArgumentException e) {
            log.warn("Skipping record with non-UUID id: {}", r.id());
            return null;
        }

        LocalDateTime pubDate = parseDate(r.publication_date());

        String[] categories = (r.category() == null) ? new String[0]
                : r.category().stream().filter(c -> c != null).toArray(String[]::new);
        String[] categoriesNorm = new String[categories.length];
        for (int i = 0; i < categories.length; i++) {
            categoriesNorm[i] = categories[i].toLowerCase(Locale.ROOT);
        }

        boolean geoValid = isGeoValid(r.latitude(), r.longitude());

        return new CleanRecord(id, r.title(), r.description(), r.url(), pubDate,
                r.source_name(), categories, categoriesNorm, r.relevance_score(),
                r.latitude(), r.longitude(), geoValid);
    }

    private int flush(List<CleanRecord> batch) {
        int[] results = jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                CleanRecord c = batch.get(i);
                ps.setObject(1, c.id());
                ps.setString(2, c.title());
                ps.setString(3, c.description());
                ps.setString(4, c.url());
                if (c.publicationDate() == null) {
                    ps.setNull(5, Types.TIMESTAMP);
                } else {
                    ps.setTimestamp(5, Timestamp.valueOf(c.publicationDate()));
                }
                ps.setString(6, c.sourceName());
                ps.setArray(7, ps.getConnection().createArrayOf("text", c.categories()));
                ps.setArray(8, ps.getConnection().createArrayOf("text", c.categoriesNorm()));
                if (c.relevanceScore() == null) {
                    ps.setNull(9, Types.DOUBLE);
                } else {
                    ps.setDouble(9, c.relevanceScore());
                }
                setNullableDouble(ps, 10, c.latitude());
                setNullableDouble(ps, 11, c.longitude());
                // geog params: pass coords only when valid, else NULL → NULL geography
                if (c.geoValid()) {
                    ps.setDouble(12, c.longitude());
                    ps.setDouble(13, c.latitude());
                } else {
                    ps.setNull(12, Types.DOUBLE);
                    ps.setNull(13, Types.DOUBLE);
                }
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
        return results.length;
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, Types.DOUBLE);
        } else {
            ps.setDouble(idx, v);
        }
    }

    private static LocalDateTime parseDate(String raw) {
        if (isBlank(raw)) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.trim());
        } catch (Exception e) {
            log.debug("Unparseable publication_date '{}' — storing null", raw);
            return null;
        }
    }

    /** Valid = both present, within earth bounds, and not the (0,0) null-island sentinel. */
    private static boolean isGeoValid(Double lat, Double lon) {
        if (lat == null || lon == null) {
            return false;
        }
        if (lat == 0.0 && lon == 0.0) {
            return false;
        }
        return lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Cleaned, validated projection ready for binding. */
    private record CleanRecord(UUID id, String title, String description, String url,
                               LocalDateTime publicationDate, String sourceName,
                               String[] categories, String[] categoriesNorm,
                               Double relevanceScore, Double latitude, Double longitude,
                               boolean geoValid) {
    }
}
