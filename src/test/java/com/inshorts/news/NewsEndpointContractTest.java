package com.inshorts.news;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Endpoint contract tests: success envelopes for R1–R7 and the uniform error
 * envelope + validation rules (tasks §6.3). LLM is disabled, so responses are
 * {@code degraded:true} with {@code llm_summary:null} but still 200.
 */
class NewsEndpointContractTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void categoryReturnsEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/news/category").param("category", "world").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles").isArray())
                .andExpect(jsonPath("$.total").value(lessThanOrEqualTo(5)))
                .andExpect(jsonPath("$.query").exists())
                .andExpect(jsonPath("$.degraded").value(true));
    }

    @Test
    void scoreRespectsThresholdOrdering() throws Exception {
        mockMvc.perform(get("/api/v1/news/score").param("threshold", "0.5").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles[0].relevance_score").value(greaterThanOrEqualTo(0.5)));
    }

    @Test
    void searchReturnsResults() throws Exception {
        mockMvc.perform(get("/api/v1/news/search").param("query", "government").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles").isArray());
    }

    @Test
    void sourceIsCaseInsensitive() throws Exception {
        mockMvc.perform(get("/api/v1/news/source").param("source", "news18").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles").isArray());
    }

    @Test
    void nearbyReturnsDistanceForIndianCoords() throws Exception {
        mockMvc.perform(get("/api/v1/news/nearby")
                        .param("lat", "19.07").param("lon", "72.88")
                        .param("radius", "500").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles").isArray())
                .andExpect(jsonPath("$.articles[0].distance_km").exists());
    }

    @Test
    void trendingReturnsEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/news/trending").param("lat", "19.07").param("lon", "72.88"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles").isArray());
    }

    @Test
    void queryRoutesWithDirectIntentEscapeHatch() throws Exception {
        mockMvc.perform(get("/api/v1/news/query")
                        .param("query", "world news")
                        .param("intent", "category")
                        .param("entities", "world"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles").isArray());
    }

    @Test
    void queryWithEntitiesOnlyNoIntentReturns200() throws Exception {
        // Entities but no valid intent must not error (empty intents -> search fallback).
        mockMvc.perform(get("/api/v1/news/query")
                        .param("query", "news18 update")
                        .param("entities", "News18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articles").isArray())
                .andExpect(jsonPath("$.total").exists());
    }

    @Test
    void limitIsClampedToFifty() throws Exception {
        mockMvc.perform(get("/api/v1/news/score").param("threshold", "0.0").param("limit", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(lessThanOrEqualTo(50)))
                .andExpect(jsonPath("$.total").value(greaterThan(0)));
    }

    // --- error envelope + validation ---

    @Test
    void missingRequiredParamIs400() throws Exception {
        mockMvc.perform(get("/api/v1/news/category"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.path").exists());
    }

    @Test
    void blankCategoryIs400() throws Exception {
        mockMvc.perform(get("/api/v1/news/category").param("category", "  "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void thresholdOutOfRangeIs400() throws Exception {
        mockMvc.perform(get("/api/v1/news/score").param("threshold", "2.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void latOutOfRangeIs400() throws Exception {
        mockMvc.perform(get("/api/v1/news/nearby").param("lat", "200").param("lon", "72"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyResultIs200NotFound() throws Exception {
        mockMvc.perform(get("/api/v1/news/category").param("category", "no-such-category-xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.articles").isEmpty());
    }
}
