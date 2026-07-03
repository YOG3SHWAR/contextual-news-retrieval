package com.inshorts.news.integration.llm.model;

import java.util.List;

/**
 * Structured result of query understanding (design §5.1): the entities the query
 * mentions, one or more retrieval intents, and normalized search keywords.
 */
public record QueryUnderstanding(
        List<String> entities,
        List<Intent> intents,
        List<String> keywords) {

    public QueryUnderstanding {
        entities = entities == null ? List.of() : List.copyOf(entities);
        intents = (intents == null || intents.isEmpty()) ? List.of(Intent.SEARCH) : List.copyOf(intents);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }
}
