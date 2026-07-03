package com.inshorts.news.integration.geo;

import com.inshorts.news.domain.Article;
import java.util.List;

/**
 * Scale seam (design §1.1). Geospatial nearby search + distance. The seed adapter
 * is {@code PostgisGeoProvider} (PostGIS GiST); tiled/sharded geo (H3/geohash)
 * can be swapped in later without touching controllers or services.
 */
public interface GeoProvider {

    /** An article together with its spheroidal distance from the query point, in km. */
    record GeoArticle(Article article, double distanceKm) {
    }

    /**
     * Articles within {@code radiusKm} of ({@code lat},{@code lon}), nearest-first.
     * Rows with null geography are excluded.
     */
    List<GeoArticle> nearby(double lat, double lon, double radiusKm, int limit);
}
