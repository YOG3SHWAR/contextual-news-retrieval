package com.inshorts.news.service;

import ch.hsr.geohash.GeoHash;
import com.inshorts.news.config.NewsProperties;
import com.inshorts.news.domain.Article;
import com.inshorts.news.domain.UserEvent;
import com.inshorts.news.integration.cache.CacheService;
import com.inshorts.news.repository.ArticleRepository;
import com.inshorts.news.repository.EventRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Computes the location-aware trending feed (design §4.4, R6/R9–R11).
 *
 * <p>Score for an article near the requester:
 * {@code Σ over events of typeWeight · e^(-λ·Δt_hours) · geoFactor}, with
 * {@code λ = ln(2)/halfLifeHours} (6h half-life) and a geo factor that decays
 * with distance from the requester. The expensive event aggregation is cached
 * per geohash prefix (precision ~5) with a short TTL and single-flight on miss;
 * summary enrichment happens after the cache and reuses the summary cache.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrendingService {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final EventRepository eventRepository;
    private final ArticleRepository articleRepository;
    private final CacheService cache;
    private final NewsProperties props;

    /** Ranked trending articles for a location (enrichment happens downstream). */
    public List<ArticleHit> trending(double lat, double lon, int limit) {
        NewsProperties.Trending cfg = props.getTrending();
        String prefix = GeoHash.withCharacterPrecision(lat, lon, cfg.getGeohashPrecision()).toBase32();
        String cacheKey = "trending:" + prefix + ":" + limit;

        // Cache the ordered article ids (the expensive part); TTL short, single-flight.
        String idsCsv = cache.getOrCompute(cacheKey, Duration.ofSeconds(cfg.getCacheTtlSeconds()),
                () -> computeTopIds(prefix, lat, lon, limit, cfg));

        if (idsCsv == null || idsCsv.isBlank()) {
            return List.of();
        }
        List<UUID> ids = Arrays.stream(idsCsv.split(","))
                .filter(s -> !s.isBlank())
                .map(UUID::fromString)
                .toList();
        return loadInOrder(ids);
    }

    private String computeTopIds(String prefix, double lat, double lon, int limit,
                                 NewsProperties.Trending cfg) {
        LocalDateTime since = LocalDateTime.now().minusHours(cfg.getWindowHours());
        List<UserEvent> events = eventRepository.findByGeohashPrefix(prefix, since);
        if (events.isEmpty()) {
            return "";
        }
        double lambda = Math.log(2.0) / cfg.getHalfLifeHours();
        LocalDateTime now = LocalDateTime.now();
        Map<UUID, Double> scores = new HashMap<>();

        for (UserEvent e : events) {
            if (e.getArticleId() == null) {
                continue;
            }
            double weight = typeWeight(e.getEventType(), cfg.getTypeWeights());
            double ageHours = Math.max(0, Duration.between(e.getCreatedAt(), now).toMinutes() / 60.0);
            double decay = Math.exp(-lambda * ageHours);
            double geoFactor = geoFactor(e.getLatitude(), e.getLongitude(), lat, lon);
            scores.merge(e.getArticleId(), weight * decay * geoFactor, Double::sum);
        }

        return scores.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()
                        .thenComparing(entry -> entry.getKey()))
                .limit(limit)
                .map(entry -> entry.getKey().toString())
                .collect(Collectors.joining(","));
    }

    private List<ArticleHit> loadInOrder(List<UUID> ids) {
        Map<UUID, Article> byId = articleRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Article::getId, a -> a));
        List<ArticleHit> hits = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            Article a = byId.get(id);
            if (a != null) {
                hits.add(ArticleHit.of(a));
            }
        }
        return hits;
    }

    private static double typeWeight(String type, Map<String, Integer> weights) {
        if (type == null) {
            return 1.0;
        }
        Integer w = weights.get(type.toLowerCase(Locale.ROOT));
        return w == null ? 1.0 : w.doubleValue();
    }

    /** Geo factor favoring events near the requester: 1/(1 + distanceKm). */
    private static double geoFactor(Double eventLat, Double eventLon, double reqLat, double reqLon) {
        if (eventLat == null || eventLon == null) {
            return 1.0;
        }
        double d = haversineKm(eventLat, eventLon, reqLat, reqLon);
        return 1.0 / (1.0 + d);
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
