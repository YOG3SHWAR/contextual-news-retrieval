package com.inshorts.news.config;

import com.inshorts.news.web.RateLimitInterceptor;
import com.inshorts.news.web.RequestLoggingInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers MVC interceptors: an access logger over all news endpoints and the
 * rate-limit guard on the LLM-backed /query endpoint.
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RequestLoggingInterceptor requestLoggingInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Added first so its preHandle runs first and afterCompletion runs last —
        // it logs every request including those rejected (e.g. 429) by later interceptors.
        registry.addInterceptor(requestLoggingInterceptor)
                .addPathPatterns("/api/v1/news/**");
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/v1/news/query");
    }
}
