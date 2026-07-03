package com.inshorts.news.config;

import java.util.Map;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Strongly-typed binding for the {@code news.*} configuration tree (design §8).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "news")
public class NewsProperties {

    @NestedConfigurationProperty
    private Ingest ingest = new Ingest();
    @NestedConfigurationProperty
    private Search search = new Search();
    @NestedConfigurationProperty
    private Nearby nearby = new Nearby();
    @NestedConfigurationProperty
    private Trending trending = new Trending();
    @NestedConfigurationProperty
    private Llm llm = new Llm();
    @NestedConfigurationProperty
    private RateLimit ratelimit = new RateLimit();

    @Getter
    @Setter
    public static class Ingest {
        private String file = "data/news_data.json";
        private boolean skipIfPopulated = true;
        private int batchSize = 500;
    }

    @Getter
    @Setter
    public static class Search {
        private double weightText = 0.6;
        private double weightRelevance = 0.4;
    }

    @Getter
    @Setter
    public static class Nearby {
        private double defaultRadiusKm = 10;
        private double maxRadiusKm = 500;
    }

    @Getter
    @Setter
    public static class Trending {
        private int geohashPrecision = 5;
        private int eventGeohashPrecision = 7;
        private long cacheTtlSeconds = 60;
        private double halfLifeHours = 6;
        private long windowHours = 48;
        private Map<String, Integer> typeWeights = Map.of("view", 1, "dwell", 2, "click", 3, "share", 5);
        @NestedConfigurationProperty
        private Simulator simulator = new Simulator();

        @Getter
        @Setter
        public static class Simulator {
            private boolean enabled = true;
            private int eventCount = 5000;
        }
    }

    @Getter
    @Setter
    public static class Llm {
        private boolean enabled = true;
        private String apiKey = "";
        private String baseUrl = "https://api.anthropic.com/v1/messages";
        private String anthropicVersion = "2023-06-01";
        private String modelExtract = "claude-sonnet-4-6";
        private String modelSummary = "claude-haiku-4-5-20251001";
        private long timeoutMs = 5000;
        private int maxSummaryTokens = 120;
        private int summaryConcurrency = 4;
        @NestedConfigurationProperty
        private Prompts prompts = new Prompts();

        /** LLM is active only when explicitly enabled and an API key is present. */
        public boolean isActive() {
            return enabled && apiKey != null && !apiKey.isBlank();
        }

        /**
         * Externalized prompt configuration. Prompts live in versioned resource
         * files (bundled on the classpath) and can be overridden per-deployment by
         * pointing the *-path to a {@code file:} or {@code classpath:} location —
         * no recompile needed. The version tags are logged/metered so output can be
         * correlated to a prompt revision.
         */
        @Getter
        @Setter
        public static class Prompts {
            private String extractionPath = "classpath:prompts/extraction-system.txt";
            private String summaryPath = "classpath:prompts/summary-system.txt";
            private String extractionVersion = "extraction.v1";
            private String summaryVersion = "summary.v1";
        }
    }

    @Getter
    @Setter
    public static class RateLimit {
        private int queryCapacity = 30;
        private int queryRefillPerMinute = 30;
    }
}
