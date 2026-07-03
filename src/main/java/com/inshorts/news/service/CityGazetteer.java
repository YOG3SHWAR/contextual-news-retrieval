package com.inshorts.news.service;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A small bundled gazetteer of Indian cities → coordinates (design §4.2). No
 * external geocoder. Weighted toward the corpus bounding box (lat 15.6–22.5,
 * lon 72.6–80.9) so location entities resolve to populated regions.
 */
@Component
public class CityGazetteer {

    /** A latitude/longitude pair. */
    public record Coordinates(double lat, double lon) {
    }

    private static final Map<String, Coordinates> CITIES = Map.ofEntries(
            Map.entry("mumbai", new Coordinates(19.0760, 72.8777)),
            Map.entry("bombay", new Coordinates(19.0760, 72.8777)),
            Map.entry("pune", new Coordinates(18.5204, 73.8567)),
            Map.entry("hyderabad", new Coordinates(17.3850, 78.4867)),
            Map.entry("nagpur", new Coordinates(21.1458, 79.0882)),
            Map.entry("nashik", new Coordinates(19.9975, 73.7898)),
            Map.entry("aurangabad", new Coordinates(19.8762, 75.3433)),
            Map.entry("solapur", new Coordinates(17.6599, 75.9064)),
            Map.entry("kolhapur", new Coordinates(16.7050, 74.2433)),
            Map.entry("vijayawada", new Coordinates(16.5062, 80.6480)),
            Map.entry("warangal", new Coordinates(17.9689, 79.5941)),
            Map.entry("surat", new Coordinates(21.1702, 72.8311)),
            Map.entry("ahmedabad", new Coordinates(23.0225, 72.5714)),
            Map.entry("bhopal", new Coordinates(23.2599, 77.4126)),
            Map.entry("indore", new Coordinates(22.7196, 75.8577)),
            Map.entry("nanded", new Coordinates(19.1383, 77.3210)),
            Map.entry("amravati", new Coordinates(20.9374, 77.7796)),
            Map.entry("delhi", new Coordinates(28.6139, 77.2090)),
            Map.entry("new delhi", new Coordinates(28.6139, 77.2090)),
            Map.entry("bengaluru", new Coordinates(12.9716, 77.5946)),
            Map.entry("bangalore", new Coordinates(12.9716, 77.5946)),
            Map.entry("chennai", new Coordinates(13.0827, 80.2707)),
            Map.entry("kolkata", new Coordinates(22.5726, 88.3639)),
            Map.entry("jaipur", new Coordinates(26.9124, 75.7873)));

    /** Resolve a city name (case-insensitive) to coordinates, if known. */
    public Optional<Coordinates> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CITIES.get(name.trim().toLowerCase(Locale.ROOT)));
    }

    /** Whether the term names a known city. */
    public boolean isCity(String name) {
        return resolve(name).isPresent();
    }
}
