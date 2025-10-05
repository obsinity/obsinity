package com.obsinity.controller.rest;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Provides structured error responses for UnifiedPublishController so clients receive 4xx
 * details instead of generic 500 errors when validation fails.
 */
@RestControllerAdvice(assignableTypes = UnifiedPublishController.class)
public class PublishExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PublishExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ProblemDetail> handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        String detail = (ex.getMessage() == null || ex.getMessage().isBlank())
                ? "Request could not be processed"
                : ex.getMessage();

        log.warn(
                "Ingest request validation failed: {} (path={})",
                detail,
                request != null ? request.getRequestURI() : "<unknown>",
                ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid ingest request");
        problem.setProperty("code", errorCodeFor(detail));
        problem.setProperty("timestamp", OffsetDateTime.now());
        problem.setProperty("path", request.getRequestURI());
        String hint = hintFor(detail);
        if (hint != null) {
            problem.setProperty("hint", hint);
        }
        return ResponseEntity.badRequest().body(problem);
    }

    private static String errorCodeFor(String detail) {
        String normalized = detail.toLowerCase(Locale.ROOT);
        if (normalized.contains("unknown event type")) {
            return "ingest.event-type-missing";
        }
        if (normalized.contains("resource.service.name") || normalized.contains("event.name")) {
            return "ingest.required-field-missing";
        }
        return "ingest.invalid-request";
    }

    private static String hintFor(String detail) {
        String normalized = detail.toLowerCase(Locale.ROOT);
        if (normalized.contains("unknown event type")) {
            return "Ensure the event type is registered for the service in the controller config.";
        }
        if (normalized.contains("resource.service.name") || normalized.contains("event.name")) {
            return "Populate both resource.service.name and event.name in the request payload.";
        }
        return null;
    }
}
