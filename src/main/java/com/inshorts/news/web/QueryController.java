package com.inshorts.news.web;

import com.inshorts.news.service.QueryService;
import com.inshorts.news.web.dto.NewsResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * R7: LLM-routed natural-language query endpoint (requirements §3.2).
 *
 * <p>Supports a direct-input escape hatch: callers may pass {@code intent} and/or
 * {@code entities} (comma-separated or repeated) to bypass the LLM entirely —
 * deterministic and functional with no API key.
 */
@RestController
@RequestMapping("/api/v1/news")
@RequiredArgsConstructor
public class QueryController {

    private final QueryService queryService;
    private final ResponseAssembler assembler;

    /** GET /query?query=&lat=&lon=&limit=&intent=&entities= */
    @GetMapping("/query")
    public NewsResponse query(@RequestParam String query,
                              @RequestParam(required = false) Double lat,
                              @RequestParam(required = false) Double lon,
                              @RequestParam(defaultValue = "5") int limit,
                              @RequestParam(required = false) List<String> intent,
                              @RequestParam(required = false) List<String> entities) {
        String q = RequestValidator.requireNonBlank(query, "query");
        if (lat != null) {
            RequestValidator.validateLat(lat);
        }
        if (lon != null) {
            RequestValidator.validateLon(lon);
        }
        int lim = RequestValidator.clampLimit(limit);

        QueryService.RoutedResult result = queryService.handle(q, lat, lon, lim, intent, entities);
        return assembler.assemble(result.hits(), result.echo(), result.degraded());
    }
}
