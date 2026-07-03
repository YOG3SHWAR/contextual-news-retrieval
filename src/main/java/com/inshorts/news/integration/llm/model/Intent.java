package com.inshorts.news.integration.llm.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Retrieval intents the LLM (or heuristic) can assign to a query.
 * {@link #specificity()} orders how "specific" a ranking rule is when a query
 * matches multiple intents — the most specific matched rule wins (design §4.2).
 */
public enum Intent {
    NEARBY(5),
    SEARCH(4),
    SOURCE(3),
    CATEGORY(2),
    SCORE(1);

    private final int specificity;

    Intent(int specificity) {
        this.specificity = specificity;
    }

    public int specificity() {
        return specificity;
    }

    /** Parse an LLM/user-supplied intent token; unknown values default to SEARCH. */
    public static Intent parseOrSearch(String raw) {
        if (raw == null) {
            return SEARCH;
        }
        try {
            return Intent.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return SEARCH;
        }
    }

    public static Optional<Intent> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Intent.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
