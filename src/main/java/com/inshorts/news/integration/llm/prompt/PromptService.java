package com.inshorts.news.integration.llm.prompt;

import com.inshorts.news.config.NewsProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Loads and serves the externalized LLM system prompts (design §5, prompt-mgmt
 * plan Part A). Prompts live in versioned resource files and are resolved at
 * startup via {@link ResourceLoader} (supports {@code classpath:} and
 * {@code file:} locations), so a deployment can override them without a rebuild.
 *
 * <p>Fail-fast: if a configured prompt cannot be read or is blank, startup fails
 * loudly rather than silently sending an empty system prompt to the model.
 *
 * <p>The tool/JSON schema and response parsing intentionally stay in
 * {@code ClaudeLlmClient} — that is the parser contract, not prose. These prompts
 * are static system instructions; untrusted user text is never interpolated here
 * (it goes in the user message), preserving prompt-injection hygiene.
 */
@Slf4j
@Service
public class PromptService {

    private final String extractionSystem;
    private final String summarySystem;
    private final String extractionVersion;
    private final String summaryVersion;

    public PromptService(NewsProperties props, ResourceLoader resourceLoader) {
        NewsProperties.Llm.Prompts cfg = props.getLlm().getPrompts();
        this.extractionSystem = load(resourceLoader, cfg.getExtractionPath());
        this.summarySystem = load(resourceLoader, cfg.getSummaryPath());
        this.extractionVersion = cfg.getExtractionVersion();
        this.summaryVersion = cfg.getSummaryVersion();
        log.info("Prompts loaded: extraction={} ({} chars), summary={} ({} chars)",
                extractionVersion, extractionSystem.length(), summaryVersion, summarySystem.length());
    }

    /** System prompt for query understanding (tool-use extraction). */
    public String extractionSystem() {
        return extractionSystem;
    }

    /** System prompt for summarization. */
    public String summarySystem() {
        return summarySystem;
    }

    public String extractionVersion() {
        return extractionVersion;
    }

    public String summaryVersion() {
        return summaryVersion;
    }

    private static String load(ResourceLoader resourceLoader, String location) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException("Prompt resource not found: " + location);
        }
        try (InputStream in = resource.getInputStream()) {
            String content = StreamUtils.copyToString(in, StandardCharsets.UTF_8).trim();
            if (content.isEmpty()) {
                throw new IllegalStateException("Prompt resource is empty: " + location);
            }
            return content;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt resource: " + location, e);
        }
    }
}
