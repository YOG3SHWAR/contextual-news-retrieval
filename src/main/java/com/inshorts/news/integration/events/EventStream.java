package com.inshorts.news.integration.events;

import com.inshorts.news.domain.UserEvent;
import java.util.Collection;

/**
 * Scale seam (design §1.1, §10.4). The trending pipeline publishes/consumes user
 * events through this interface. The seed adapter is {@code InProcessEventStream}
 * (writes to Postgres); a {@code KafkaEventStream} can be swapped in later so
 * {@code /trending} becomes a pure cache read — without changing
 * {@code TrendingService}'s public contract.
 */
public interface EventStream {

    /** Publish a single event (idempotent by {@code event_id}). */
    void publish(UserEvent event);

    /** Publish a batch of events (idempotent by {@code event_id}). */
    void publish(Collection<UserEvent> events);
}
