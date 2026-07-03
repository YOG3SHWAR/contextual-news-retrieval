package com.inshorts.news.integration.events;

import com.inshorts.news.domain.UserEvent;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Seed implementation of {@link EventStream}: persists events to the
 * {@code user_events} table. Inserts use {@code ON CONFLICT (event_id) DO NOTHING}
 * so publishing is idempotent (dedupe by {@code event_id}).
 */
@Component
@RequiredArgsConstructor
public class InProcessEventStream implements EventStream {

    private static final String INSERT_SQL = """
            INSERT INTO user_events
                (event_id, user_id, article_id, event_type, latitude, longitude, geohash, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void publish(UserEvent event) {
        publish(List.of(event));
    }

    @Override
    public void publish(Collection<UserEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        List<UserEvent> batch = new ArrayList<>(events);
        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                UserEvent e = batch.get(i);
                ps.setObject(1, e.getEventId());
                ps.setObject(2, e.getUserId());
                ps.setObject(3, e.getArticleId());
                ps.setString(4, e.getEventType());
                setNullableDouble(ps, 5, e.getLatitude());
                setNullableDouble(ps, 6, e.getLongitude());
                ps.setString(7, e.getGeohash());
                if (e.getCreatedAt() == null) {
                    ps.setNull(8, Types.TIMESTAMP);
                } else {
                    ps.setTimestamp(8, Timestamp.valueOf(e.getCreatedAt()));
                }
            }

            @Override
            public int getBatchSize() {
                return batch.size();
            }
        });
    }

    private static void setNullableDouble(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) {
            ps.setNull(idx, Types.DOUBLE);
        } else {
            ps.setDouble(idx, v);
        }
    }
}
