package com.inshorts.news.web;

import com.inshorts.news.config.NewsProperties;
import com.inshorts.news.service.NewsService;
import com.inshorts.news.web.dto.NewsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Retrieval endpoints R1–R5 (requirements §3.1). Controllers only parse/validate
 * parameters, delegate to {@link NewsService}, and shape the envelope.
 */
@RestController
@RequestMapping("/api/v1/news")
@RequiredArgsConstructor
public class NewsController {

    private final NewsService newsService;
    private final ResponseAssembler assembler;
    private final NewsProperties props;

    /** R1: GET /category?category=&limit= */
    @GetMapping("/category")
    public NewsResponse byCategory(@RequestParam String category,
                                   @RequestParam(defaultValue = "5") int limit) {
        String cat = RequestValidator.requireNonBlank(category, "category");
        int lim = RequestValidator.clampLimit(limit);
        return assembler.assemble(newsService.byCategory(cat, lim), "category=" + cat);
    }

    /** R2: GET /score?threshold=&limit= */
    @GetMapping("/score")
    public NewsResponse byScore(@RequestParam(defaultValue = "0.7") double threshold,
                                @RequestParam(defaultValue = "5") int limit) {
        double t = RequestValidator.validateThreshold(threshold);
        int lim = RequestValidator.clampLimit(limit);
        return assembler.assemble(newsService.byScore(t, lim), "threshold=" + t);
    }

    /** R3: GET /search?query=&limit= */
    @GetMapping("/search")
    public NewsResponse search(@RequestParam String query,
                               @RequestParam(defaultValue = "5") int limit) {
        String q = RequestValidator.requireNonBlank(query, "query");
        int lim = RequestValidator.clampLimit(limit);
        return assembler.assemble(newsService.search(q, lim), q);
    }

    /** R4: GET /source?source=&limit= */
    @GetMapping("/source")
    public NewsResponse bySource(@RequestParam String source,
                                 @RequestParam(defaultValue = "5") int limit) {
        String src = RequestValidator.requireNonBlank(source, "source");
        int lim = RequestValidator.clampLimit(limit);
        return assembler.assemble(newsService.bySource(src, lim), "source=" + src);
    }

    /** R5: GET /nearby?lat=&lon=&radius=&limit= */
    @GetMapping("/nearby")
    public NewsResponse nearby(@RequestParam double lat,
                               @RequestParam double lon,
                               @RequestParam(required = false) Double radius,
                               @RequestParam(defaultValue = "5") int limit) {
        RequestValidator.validateLat(lat);
        RequestValidator.validateLon(lon);
        NewsProperties.Nearby cfg = props.getNearby();
        double requested = radius == null ? cfg.getDefaultRadiusKm() : radius;
        double effectiveRadius = RequestValidator.validateRadius(requested, cfg.getMaxRadiusKm());
        int lim = RequestValidator.clampLimit(limit);
        return assembler.assemble(
                newsService.nearby(lat, lon, effectiveRadius, lim),
                String.format("nearby(%.4f,%.4f,%.1fkm)", lat, lon, effectiveRadius));
    }
}
