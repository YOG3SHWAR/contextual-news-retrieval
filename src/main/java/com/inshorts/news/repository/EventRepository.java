package com.inshorts.news.repository;

import com.inshorts.news.domain.UserEvent;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Persistence for simulated user events (design §3.2, §4.4).
 */
@Repository
public interface EventRepository extends JpaRepository<UserEvent, UUID> {

    /**
     * Events whose geohash starts with {@code prefix} and that occurred at or
     * after {@code since}. Uses the {@code text_pattern_ops} index on geohash so
     * the {@code LIKE 'prefix%'} range is index-served rather than a full scan.
     */
    @Query("""
            SELECT e FROM UserEvent e
            WHERE e.geohash LIKE CONCAT(:prefix, '%')
              AND e.createdAt >= :since
            """)
    List<UserEvent> findByGeohashPrefix(@Param("prefix") String prefix,
                                        @Param("since") LocalDateTime since);
}
