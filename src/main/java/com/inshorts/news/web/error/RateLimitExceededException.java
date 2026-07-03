package com.inshorts.news.web.error;

/** Thrown when a client exceeds its rate limit; mapped to HTTP 429. */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
