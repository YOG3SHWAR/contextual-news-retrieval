package com.inshorts.news.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Jackson binding for one record in {@code data/news_data.json} (§2.1).
 *
 * <p>Unknown properties are ignored so future feed additions don't break ingest.
 * {@code publicationDate} is kept as a raw String and parsed defensively in the
 * loader (the source uses an ISO-8601 <i>local</i> datetime, e.g.
 * {@code 2025-03-26T04:46:55}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewsJsonRecord(
        String id,
        String title,
        String description,
        String url,
        String publication_date,
        String source_name,
        List<String> category,
        Double relevance_score,
        Double latitude,
        Double longitude) {
}
