package com.inshorts.news;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inshorts.news.integration.llm.model.Intent;
import com.inshorts.news.service.CityGazetteer;
import com.inshorts.news.web.RequestValidator;
import com.inshorts.news.web.error.BadRequestException;
import org.junit.jupiter.api.Test;

/**
 * Fast unit tests (no Spring context) for validation, intent parsing, and the
 * bundled gazetteer — covering the §5 rules and §4.2 parsing behavior.
 */
class ValidationAndParsingUnitTest {

    @Test
    void limitIsClamped() {
        assertThat(RequestValidator.clampLimit(0)).isEqualTo(1);
        assertThat(RequestValidator.clampLimit(5)).isEqualTo(5);
        assertThat(RequestValidator.clampLimit(999)).isEqualTo(50);
    }

    @Test
    void thresholdBoundsEnforced() {
        assertThat(RequestValidator.validateThreshold(0.7)).isEqualTo(0.7);
        assertThatThrownBy(() -> RequestValidator.validateThreshold(1.5))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RequestValidator.validateThreshold(-0.1))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void latLonBoundsEnforced() {
        assertThatThrownBy(() -> RequestValidator.validateLat(91)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> RequestValidator.validateLon(181)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void radiusMustBePositiveAndIsCapped() {
        assertThat(RequestValidator.validateRadius(10, 500)).isEqualTo(10);
        assertThat(RequestValidator.validateRadius(1000, 500)).isEqualTo(500);
        assertThatThrownBy(() -> RequestValidator.validateRadius(0, 500))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void blankTextRejected() {
        assertThatThrownBy(() -> RequestValidator.requireNonBlank("  ", "query"))
                .isInstanceOf(BadRequestException.class);
        assertThat(RequestValidator.requireNonBlank(" world ", "category")).isEqualTo("world");
    }

    @Test
    void intentParsingDefaultsToSearch() {
        assertThat(Intent.parseOrSearch("nearby")).isEqualTo(Intent.NEARBY);
        assertThat(Intent.parseOrSearch("CATEGORY")).isEqualTo(Intent.CATEGORY);
        assertThat(Intent.parseOrSearch("garbage")).isEqualTo(Intent.SEARCH);
        assertThat(Intent.parse("garbage")).isEmpty();
    }

    @Test
    void intentSpecificityOrdering() {
        assertThat(Intent.NEARBY.specificity()).isGreaterThan(Intent.SEARCH.specificity());
        assertThat(Intent.SEARCH.specificity()).isGreaterThan(Intent.SOURCE.specificity());
        assertThat(Intent.SOURCE.specificity()).isGreaterThan(Intent.CATEGORY.specificity());
        assertThat(Intent.CATEGORY.specificity()).isGreaterThan(Intent.SCORE.specificity());
    }

    @Test
    void gazetteerResolvesKnownCities() {
        CityGazetteer gazetteer = new CityGazetteer();
        assertThat(gazetteer.resolve("Mumbai")).isPresent();
        assertThat(gazetteer.resolve("mumbai").get().lat()).isEqualTo(19.0760);
        assertThat(gazetteer.resolve("Atlantis")).isEmpty();
        assertThat(gazetteer.isCity("Pune")).isTrue();
    }
}
