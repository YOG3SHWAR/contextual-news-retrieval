package com.inshorts.news;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inshorts.news.integration.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * LLM fault injection (tasks §6.6): with the provider "enabled" but throwing on
 * every call, all retrieval endpoints must still return 200 with
 * {@code degraded:true} and {@code llm_summary:null} — never a 500.
 */
class LlmFaultInjectionTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    LlmClient llmClient;

    @BeforeEach
    void injectFaults() {
        when(llmClient.isEnabled()).thenReturn(true);
        when(llmClient.extract(anyString())).thenThrow(new RuntimeException("boom: extract failed"));
        when(llmClient.summarize(any(), any())).thenThrow(new RuntimeException("boom: summarize failed"));
    }

    @Test
    void searchStillReturns200WithNullSummaryAndDegraded() throws Exception {
        mockMvc.perform(get("/api/v1/news/search").param("query", "government"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.articles[0].llm_summary").value(nullValue()));
    }

    @Test
    void queryFallsBackToHeuristicOn200() throws Exception {
        mockMvc.perform(get("/api/v1/news/query").param("query", "latest world news"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.articles").isArray());
    }

    @Test
    void categoryStillWorksWhenSummariesFail() throws Exception {
        mockMvc.perform(get("/api/v1/news/category").param("category", "world"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true));
    }
}
