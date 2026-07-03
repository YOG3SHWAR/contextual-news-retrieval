package com.inshorts.news.web;

import com.inshorts.news.web.error.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the {@link RateLimiter} to LLM-backed endpoints. Exceeding the bucket
 * throws {@link RateLimitExceededException}, which the global handler maps to a
 * 429 with a {@code Retry-After} header.
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String clientKey = resolveClientKey(request);
        if (!rateLimiter.tryConsume(clientKey)) {
            throw new RateLimitExceededException("Rate limit exceeded. Please retry shortly.");
        }
        return true;
    }

    private String resolveClientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
