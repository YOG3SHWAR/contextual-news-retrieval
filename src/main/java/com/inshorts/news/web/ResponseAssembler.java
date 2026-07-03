package com.inshorts.news.web;

import com.inshorts.news.service.ArticleHit;
import com.inshorts.news.service.SummaryService;
import com.inshorts.news.web.dto.ArticleMapper;
import com.inshorts.news.web.dto.NewsResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds the standard {@link NewsResponse} envelope from ranked hits, applying
 * LLM summary enrichment (design §4.3) as the single shared step so every
 * endpoint gets consistent {@code llm_summary} + {@code degraded} handling.
 */
@Component
@RequiredArgsConstructor
public class ResponseAssembler {

    private final ArticleMapper mapper;
    private final SummaryService summaryService;

    /**
     * Assemble the envelope. {@code baseDegraded} lets callers (e.g. /query when
     * the LLM understanding fell back) seed the degraded flag; it is OR-ed with
     * any degradation observed during summary enrichment.
     */
    public NewsResponse assemble(List<ArticleHit> hits, String query, boolean baseDegraded) {
        boolean enrichDegraded = summaryService.enrich(hits);
        return NewsResponse.of(mapper.toDtos(hits), query, baseDegraded || enrichDegraded);
    }

    public NewsResponse assemble(List<ArticleHit> hits, String query) {
        return assemble(hits, query, false);
    }
}
