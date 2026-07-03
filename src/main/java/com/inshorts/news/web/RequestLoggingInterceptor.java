package com.inshorts.news.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Emits exactly one INFO access-log line per API request (all {@code /api/v1/news/**}
 * endpoints), on completion — the baseline "what was called and how it went" record
 * an operator/reviewer reads first.
 *
 * <p>Fields: {@code method}, {@code path}, {@code status}, latency, and — when the
 * request produced a result envelope — {@code results} and {@code degraded} (and
 * {@code intent}/{@code strategy} for {@code /query}). Those extras are published to
 * the SLF4J MDC by {@link ResponseAssembler} / {@code QueryService} during handling;
 * this interceptor reads them here and the {@code CorrelationIdFilter} clears the MDC
 * at the end of the request. The correlation id itself is already on every log line
 * via the logging pattern.
 */
@Component
public class RequestLoggingInterceptor implements HandlerInterceptor {

    /** MDC keys populated during handling and surfaced on the access-log line. */
    public static final String MDC_INTENT = "intent";
    public static final String MDC_STRATEGY = "strategy";
    public static final String MDC_RESULTS = "results";
    public static final String MDC_DEGRADED = "degraded";

    private static final Logger log = LoggerFactory.getLogger("com.inshorts.news.access");
    private static final String START_ATTR = "news.startNanos";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        request.setAttribute(START_ATTR, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        long tookMs = elapsedMs(request);
        StringBuilder line = new StringBuilder(96)
                .append("method=").append(request.getMethod())
                .append(" path=").append(request.getRequestURI())
                .append(" status=").append(response.getStatus())
                .append(" tookMs=").append(tookMs);

        appendIfPresent(line, "intent", MDC_INTENT);
        appendIfPresent(line, "strategy", MDC_STRATEGY);
        appendIfPresent(line, "results", MDC_RESULTS);
        appendIfPresent(line, "degraded", MDC_DEGRADED);

        log.info(line.toString());
    }

    private static long elapsedMs(HttpServletRequest request) {
        Object start = request.getAttribute(START_ATTR);
        if (start instanceof Long s) {
            return (System.nanoTime() - s) / 1_000_000L;
        }
        return -1;
    }

    private static void appendIfPresent(StringBuilder line, String label, String mdcKey) {
        String v = MDC.get(mdcKey);
        if (v != null) {
            line.append(' ').append(label).append('=').append(v);
        }
    }
}
