package com.inshorts.news.web;

import com.inshorts.news.service.ArticleHit;
import com.inshorts.news.web.dto.ArticleMapper;
import com.inshorts.news.web.dto.NewsResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Builds the standard {@link NewsResponse} envelope from ranked hits.
 *
 * <p>This is the single point where LLM summary enrichment plugs in (Phase 3):
 * {@code SummaryService} is invoked here before mapping so every endpoint gets
 * consistent enrichment and {@code degraded} handling.
 */
@Component
@RequiredArgsConstructor
public class ResponseAssembler {

    private final ArticleMapper mapper;

    /**
     * Assemble the envelope for a set of hits. {@code baseDegraded} lets callers
     * (e.g. /query when the LLM fell back) seed the degraded flag.
     */
    public NewsResponse assemble(List<ArticleHit> hits, String query, boolean baseDegraded) {
        // Phase 3 will enrich hits with llm_summary here and OR-in enrichment degradation.
        return NewsResponse.of(mapper.toDtos(hits), query, baseDegraded);
    }

    public NewsResponse assemble(List<ArticleHit> hits, String query) {
        return assemble(hits, query, false);
    }
}
