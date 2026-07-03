package com.inshorts.news.integration.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.inshorts.news.config.NewsProperties;
import com.inshorts.news.integration.llm.model.Intent;
import com.inshorts.news.integration.llm.model.QueryUnderstanding;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anthropic Claude implementation of {@link LlmClient} (design §5).
 *
 * <ul>
 *   <li><b>Extraction</b> uses tool-use with a JSON schema so Claude must return
 *       a typed {@code {entities, intent[], keywords}} object rather than prose.</li>
 *   <li><b>Summaries</b> use a cheap/fast model with small {@code max_tokens} and a
 *       "no new facts" instruction to guard against hallucination.</li>
 * </ul>
 *
 * Every call has an explicit connect+read timeout. Failures propagate as
 * exceptions so the caller can fall back (heuristic / null summary + degraded).
 */
@Slf4j
@Component
public class ClaudeLlmClient implements LlmClient {

    private static final String EXTRACT_TOOL = "extract_query";

    private final NewsProperties.Llm cfg;
    private final ObjectMapper mapper;
    private final RestClient client;

    public ClaudeLlmClient(NewsProperties props, ObjectMapper mapper) {
        this.cfg = props.getLlm();
        this.mapper = mapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) cfg.getTimeoutMs());
        factory.setReadTimeout((int) cfg.getTimeoutMs());
        this.client = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(cfg.getBaseUrl())
                .defaultHeader("anthropic-version", cfg.getAnthropicVersion())
                .defaultHeader("content-type", "application/json")
                .build();
    }

    @Override
    public boolean isEnabled() {
        return cfg.isActive();
    }

    @Override
    @CircuitBreaker(name = "llm")
    @Retry(name = "llm")
    public QueryUnderstanding extract(String query) {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "entities", Map.of("type", "array", "items", Map.of("type", "string")),
                        "intent", Map.of("type", "array", "items",
                                Map.of("type", "string",
                                        "enum", List.of("category", "score", "search", "source", "nearby"))),
                        "keywords", Map.of("type", "array", "items", Map.of("type", "string"))),
                "required", List.of("entities", "intent", "keywords"));

        Map<String, Object> body = Map.of(
                "model", cfg.getModelExtract(),
                "max_tokens", 512,
                "system", extractionSystemPrompt(),
                "tools", List.of(Map.of(
                        "name", EXTRACT_TOOL,
                        "description", "Extract entities, retrieval intents, and search keywords from a news query.",
                        "input_schema", schema)),
                "tool_choice", Map.of("type", "tool", "name", EXTRACT_TOOL),
                "messages", List.of(Map.of("role", "user", "content", query)));

        JsonNode response = post(body);
        JsonNode toolInput = findToolInput(response);
        if (toolInput == null) {
            throw new IllegalStateException("Claude returned no tool_use block for extraction");
        }
        return toUnderstanding(toolInput);
    }

    @Override
    @CircuitBreaker(name = "llm")
    @Retry(name = "llm")
    public Optional<String> summarize(String title, String description) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        String userContent = "Title: " + safe(title) + "\nDescription: " + safe(description);
        Map<String, Object> body = Map.of(
                "model", cfg.getModelSummary(),
                "max_tokens", cfg.getMaxSummaryTokens(),
                "system", "You summarize a news item in one or two sentences using ONLY the "
                        + "provided title and description. Do not add facts, opinions, or details "
                        + "not present in the input. Output only the summary text.",
                "messages", List.of(Map.of("role", "user", "content", userContent)));

        JsonNode response = post(body);
        String text = extractText(response);
        return (text == null || text.isBlank()) ? Optional.empty() : Optional.of(text.trim());
    }

    // --- HTTP + parsing helpers ------------------------------------------------

    private JsonNode post(Map<String, Object> body) {
        return client.post()
                .header("x-api-key", cfg.getApiKey())
                .body(body)
                .retrieve()
                .body(JsonNode.class);
    }

    private QueryUnderstanding toUnderstanding(JsonNode input) {
        List<String> entities = toStringList(input.get("entities"));
        List<String> keywords = toStringList(input.get("keywords"));
        List<Intent> intents = new ArrayList<>();
        JsonNode intentNode = input.get("intent");
        if (intentNode != null && intentNode.isArray()) {
            for (JsonNode n : intentNode) {
                intents.add(Intent.parseOrSearch(n.asText()));
            }
        }
        return new QueryUnderstanding(entities, intents, keywords);
    }

    private static JsonNode findToolInput(JsonNode response) {
        JsonNode content = response == null ? null : response.get("content");
        if (content == null || !content.isArray()) {
            return null;
        }
        for (JsonNode block : content) {
            if ("tool_use".equals(text(block.get("type")))) {
                return block.get("input");
            }
        }
        return null;
    }

    private static String extractText(JsonNode response) {
        JsonNode content = response == null ? null : response.get("content");
        if (content == null || !content.isArray()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            if ("text".equals(text(block.get("type")))) {
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                String v = n.asText(null);
                if (v != null && !v.isBlank()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private static String text(JsonNode n) {
        return n == null ? null : n.asText(null);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String extractionSystemPrompt() {
        return """
                You classify news search queries. Extract:
                - entities: named people, organizations, and locations mentioned.
                - intent: one or more of category, score, search, source, nearby.
                - keywords: normalized search terms.
                Guidance:
                - A location + "near"/"in" a place => nearby (e.g. "Elon Musk Twitter news near Palo Alto" => nearby).
                - "top/latest <topic> news from <publication>" => [category, source].
                - A named news outlet => source. A topic like technology/sports => category.
                - High-quality / most relevant => score. Otherwise => search.
                Return only the tool call.
                """;
    }
}
