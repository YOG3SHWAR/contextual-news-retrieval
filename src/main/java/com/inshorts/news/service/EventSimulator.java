package com.inshorts.news.service;

import ch.hsr.geohash.GeoHash;
import com.inshorts.news.config.NewsProperties;
import com.inshorts.news.domain.UserEvent;
import com.inshorts.news.integration.events.EventStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seeds synthetic user events at startup (design §4.4) so {@code /trending}
 * returns meaningful data without real traffic. Events are:
 *
 * <ul>
 *   <li>biased toward a small set of "hot" articles,</li>
 *   <li>clustered around Indian cities inside the corpus bounding box
 *       (lat 15.6–22.5, lon 72.6–80.9),</li>
 *   <li>skewed toward recent timestamps,</li>
 *   <li>geohash-7 tagged, deduped by {@code event_id}.</li>
 * </ul>
 *
 * Runs after {@link com.inshorts.news.ingest.DataLoader} and is skipped when
 * events already exist or the simulator is disabled.
 */
@Slf4j
@Component
@Order(2)
@RequiredArgsConstructor
public class EventSimulator implements ApplicationRunner {

    private static final int EVENT_GEOHASH_PRECISION = 7;

    // Indian cities inside the corpus bounding box (lat, lon).
    private static final double[][] CITY_CLUSTERS = {
            {19.0760, 72.8777},  // Mumbai
            {18.5204, 73.8567},  // Pune
            {17.3850, 78.4867},  // Hyderabad
            {21.1458, 79.0882},  // Nagpur
            {16.5062, 80.6480},  // Vijayawada
    };
    private static final String[] EVENT_TYPES = {"view", "view", "view", "dwell", "click", "share"};

    private final NewsProperties props;
    private final EventStream eventStream;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        NewsProperties.Trending.Simulator cfg = props.getTrending().getSimulator();
        if (!cfg.isEnabled()) {
            log.info("Event simulator disabled — skipping");
            return;
        }
        Long existing = jdbcTemplate.queryForObject("SELECT count(*) FROM user_events", Long.class);
        if (existing != null && existing > 0) {
            log.info("user_events already populated ({} rows) — skipping simulation", existing);
            return;
        }

        List<UUID> articleIds = jdbcTemplate.queryForList("SELECT id FROM articles", UUID.class);
        if (articleIds.isEmpty()) {
            log.warn("No articles present — skipping event simulation");
            return;
        }

        Random rnd = new Random(42); // deterministic seed for reproducible demos
        int hotCount = Math.max(1, Math.min(20, articleIds.size() / 10));
        List<UUID> hot = articleIds.subList(0, hotCount);

        int windowHours = (int) props.getTrending().getWindowHours();
        int target = cfg.getEventCount();
        List<UserEvent> batch = new ArrayList<>(target);

        for (int i = 0; i < target; i++) {
            // 80% of events hit a hot article (creates a clear trending signal).
            UUID articleId = (rnd.nextDouble() < 0.8)
                    ? hot.get(rnd.nextInt(hot.size()))
                    : articleIds.get(rnd.nextInt(articleIds.size()));

            double[] city = CITY_CLUSTERS[rnd.nextInt(CITY_CLUSTERS.length)];
            double lat = city[0] + (rnd.nextDouble() - 0.5) * 0.1;   // ~±0.05° jitter
            double lon = city[1] + (rnd.nextDouble() - 0.5) * 0.1;

            String type = EVENT_TYPES[rnd.nextInt(EVENT_TYPES.length)];
            // Recency skew: square of uniform biases toward small ages.
            double frac = rnd.nextDouble() * rnd.nextDouble();
            LocalDateTime ts = LocalDateTime.now().minusMinutes((long) (frac * windowHours * 60));
            String geohash = GeoHash.withCharacterPrecision(lat, lon, EVENT_GEOHASH_PRECISION).toBase32();

            batch.add(UserEvent.builder()
                    .eventId(UUID.randomUUID())
                    .userId(UUID.randomUUID())
                    .articleId(articleId)
                    .eventType(type)
                    .latitude(lat)
                    .longitude(lon)
                    .geohash(geohash)
                    .createdAt(ts)
                    .build());
        }

        eventStream.publish(batch);
        log.info("Event simulation complete: {} events across {} city clusters ({} hot articles)",
                batch.size(), CITY_CLUSTERS.length, hotCount);
    }
}
