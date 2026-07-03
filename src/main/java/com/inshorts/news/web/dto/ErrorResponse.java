package com.inshorts.news.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * Uniform error envelope for all failures (response contract §4.2).
 * Stack traces are never included.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String error,
        String message,
        int status,
        String timestamp,
        String path) {

    public static ErrorResponse of(String error, String message, int status, String path) {
        return new ErrorResponse(error, message, status, OffsetDateTime.now().toString(), path);
    }
}
