package com.inshorts.news.integration.llm;

import com.inshorts.news.integration.llm.model.Intent;
import com.inshorts.news.integration.llm.model.QueryUnderstanding;
import com.inshorts.news.service.CityGazetteer;
import com.inshorts.news.service.Vocabulary;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Deterministic, no-network intent extraction (design §5.3). Runs whenever the
 * LLM is disabled, has no key, or fails. Matches query tokens/phrases against
 * the known category and source vocabulary and the city gazetteer, and infers
 * a score/search intent otherwise.
 */
@Component
@RequiredArgsConstructor
public class HeuristicIntentExtractor {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "of", "in", "on", "for", "to", "and", "or", "near",
            "from", "latest", "top", "news", "about", "with", "by", "at", "is", "are");

    private static final Set<String> SCORE_HINTS = Set.of(
            "top", "best", "relevant", "important", "quality", "trending", "popular");

    private final Vocabulary vocabulary;
    private final CityGazetteer gazetteer;

    public QueryUnderstanding extract(String query) {
        String q = query == null ? "" : query.trim();
        String lower = q.toLowerCase(Locale.ROOT);
        List<String> tokens = List.of(lower.split("\\W+"));

        List<String> entities = new ArrayList<>();
        Set<Intent> intents = new LinkedHashSet<>();

        // Source: match the longest known source name that appears in the query.
        String matchedSource = null;
        for (String source : vocabulary.sources()) {
            if (!source.isBlank() && lower.contains(source)) {
                if (matchedSource == null || source.length() > matchedSource.length()) {
                    matchedSource = source;
                }
            }
        }
        if (matchedSource != null) {
            intents.add(Intent.SOURCE);
            entities.add(matchedSource);
        }

        // Category: any token that is a known category label.
        Set<String> categories = vocabulary.categories();
        for (String token : tokens) {
            if (token.length() > 1 && categories.contains(token)) {
                intents.add(Intent.CATEGORY);
                entities.add(token);
            }
        }

        // Location: any gazetteer city mentioned → nearby.
        for (String token : tokens) {
            if (gazetteer.isCity(token)) {
                intents.add(Intent.NEARBY);
                entities.add(token);
            }
        }

        // Score hint.
        for (String token : tokens) {
            if (SCORE_HINTS.contains(token)) {
                intents.add(Intent.SCORE);
                break;
            }
        }

        List<String> keywords = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() > 2 && !STOPWORDS.contains(token)) {
                keywords.add(token);
            }
        }

        // Fall back to plain search when nothing specific matched.
        if (intents.isEmpty()) {
            intents.add(Intent.SEARCH);
        }

        return new QueryUnderstanding(entities, new ArrayList<>(intents), keywords);
    }
}
