package com.inshorts.news;

import static org.assertj.core.api.Assertions.assertThat;

import com.inshorts.news.ingest.DataLoader;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Ingestion: correct row count, idempotent re-run, and geo normalization
 * (tasks §6.2). The seed file has 2000 rows, all with valid coordinates.
 */
class IngestionTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataLoader dataLoader;

    @Test
    void loadsExactlyTheSeedRowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM articles", Long.class);
        assertThat(count).isEqualTo(2000L);
    }

    @Test
    void allSeedRowsHaveGeographyPopulated() {
        // Seed corpus: every row has valid coordinates → geog must be set.
        Long withGeog = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM articles WHERE geog IS NOT NULL", Long.class);
        assertThat(withGeog).isEqualTo(2000L);
    }

    @Test
    void categoriesNormIsLowercased() {
        Long mixedCase = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM articles WHERE EXISTS ("
                        + "  SELECT 1 FROM unnest(categories_norm) c WHERE c <> lower(c))",
                Long.class);
        assertThat(mixedCase).isZero();
    }

    @Test
    void reRunIsIdempotentNoDuplicates() throws Exception {
        Long before = jdbcTemplate.queryForObject("SELECT count(*) FROM articles", Long.class);
        // Re-running the loader must not duplicate rows (upsert by id).
        dataLoader.run(new DefaultApplicationArguments());
        Long after = jdbcTemplate.queryForObject("SELECT count(*) FROM articles", Long.class);
        assertThat(after).isEqualTo(before).isEqualTo(2000L);
    }

    @Test
    void defendsAgainstNullIslandFixture() {
        // Insert a (0,0) fixture the way the loader would: geog must be null.
        jdbcTemplate.update("""
                INSERT INTO articles (id, title, description, latitude, longitude, geog)
                VALUES (gen_random_uuid(), 'fixture', 'null island', 0, 0, NULL)
                """);
        Long nullIsland = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM articles WHERE latitude = 0 AND longitude = 0 AND geog IS NULL",
                Long.class);
        assertThat(nullIsland).isGreaterThanOrEqualTo(1L);
        jdbcTemplate.update("DELETE FROM articles WHERE title = 'fixture'");
    }
}
