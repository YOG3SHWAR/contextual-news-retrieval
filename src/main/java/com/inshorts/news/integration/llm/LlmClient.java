package com.inshorts.news.integration.llm;

import com.inshorts.news.integration.llm.model.QueryUnderstanding;
import java.util.Optional;

/**
 * Abstraction over the LLM provider (design §5). The seed implementation is
 * {@link ClaudeLlmClient}; when the provider is disabled/unavailable, callers
 * fall back to {@code HeuristicIntentExtractor}.
 */
public interface LlmClient {

    /** True when a real provider is configured and enabled. */
    boolean isEnabled();

    /**
     * Extract structured understanding from a natural-language query.
     *
     * @throws RuntimeException on transport/timeout/parse failure (callers fall back)
     */
    QueryUnderstanding extract(String query);

    /**
     * Summarize an article from its title + description only (no new facts).
     * Returns empty when the provider is disabled or produced nothing.
     *
     * @throws RuntimeException on transport/timeout failure (callers fall back)
     */
    Optional<String> summarize(String title, String description);
}
