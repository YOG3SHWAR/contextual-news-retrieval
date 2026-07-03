package com.inshorts.news.web;

import com.inshorts.news.web.error.BadRequestException;

/**
 * Centralized validation for request parameters (requirements §5). Clamps where
 * the spec says clamp ({@code limit}), rejects with 400 where the spec says
 * reject ({@code threshold}, {@code lat}, {@code lon}, blank text).
 */
public final class RequestValidator {

    public static final int LIMIT_MIN = 1;
    public static final int LIMIT_MAX = 50;

    private RequestValidator() {
    }

    /** Clamp limit into [1,50]. */
    public static int clampLimit(int limit) {
        if (limit < LIMIT_MIN) {
            return LIMIT_MIN;
        }
        return Math.min(limit, LIMIT_MAX);
    }

    public static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BadRequestException(field + " must not be blank");
        }
        return value.trim();
    }

    public static double validateThreshold(double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new BadRequestException("threshold must be within [0.0, 1.0]");
        }
        return threshold;
    }

    public static void validateLat(double lat) {
        if (lat < -90.0 || lat > 90.0) {
            throw new BadRequestException("lat must be within [-90, 90]");
        }
    }

    public static void validateLon(double lon) {
        if (lon < -180.0 || lon > 180.0) {
            throw new BadRequestException("lon must be within [-180, 180]");
        }
    }

    /** radius must be > 0; capped at maxRadiusKm. Returns the effective radius. */
    public static double validateRadius(double radiusKm, double maxRadiusKm) {
        if (radiusKm <= 0) {
            throw new BadRequestException("radius must be greater than 0");
        }
        return Math.min(radiusKm, maxRadiusKm);
    }
}
