package com.inshorts.news.web.error;

/** Thrown for invalid request parameters; mapped to HTTP 400. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
