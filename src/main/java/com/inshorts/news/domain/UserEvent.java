package com.inshorts.news.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A simulated user interaction event feeding the trending feed (design §3.2).
 */
@Entity
@Table(name = "user_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEvent {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "article_id")
    private UUID articleId;

    /** view | click | share | dwell */
    @Column(name = "event_type")
    private String eventType;

    private Double latitude;

    private Double longitude;

    /** precision-7 geohash, prefix-queried for location clustering. */
    private String geohash;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
