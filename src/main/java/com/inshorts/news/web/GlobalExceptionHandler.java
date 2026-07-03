package com.inshorts.news.web;

import com.inshorts.news.web.dto.ErrorResponse;
import com.inshorts.news.web.error.BadRequestException;
import com.inshorts.news.web.error.RateLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps exceptions to the uniform error envelope (§4.2). Stack traces are never
 * leaked; empty result sets are handled by the controllers as 200, not here.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({BadRequestException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Bad Request", messageFor(ex), req, null);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex, HttpServletRequest req) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "1");
        return build(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", ex.getMessage(), req, headers);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "Not Found", "No handler for " + req.getRequestURI(), req, null);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, DataAccessException.class})
    public ResponseEntity<ErrorResponse> handleDataStore(DataAccessException ex, HttpServletRequest req) {
        log.error("Datastore unavailable for {}", req.getRequestURI(), ex);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "5");
        return build(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
                "The datastore is temporarily unavailable. Please retry.", req, headers);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error for {}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred.", req, null);
    }

    private static String messageFor(Exception ex) {
        if (ex instanceof MissingServletRequestParameterException m) {
            return "Missing required parameter: " + m.getParameterName();
        }
        if (ex instanceof MethodArgumentTypeMismatchException m) {
            return "Invalid value for parameter: " + m.getName();
        }
        return ex.getMessage();
    }

    private static ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message,
                                                       HttpServletRequest req, HttpHeaders headers) {
        ErrorResponse body = ErrorResponse.of(error, message, status.value(), req.getRequestURI());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (headers != null) {
            builder.headers(headers);
        }
        return builder.body(body);
    }
}
