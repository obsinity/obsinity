package com.obsinity.controller.rest;

import com.obsinity.service.core.state.query.MissingTransitionCountersException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/** Global REST exception mapper. Produces consistent JSON payloads for client-visible errors. */
@ControllerAdvice
public class RestErrorHandler {

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ErrorPayload> handleBadRequest(RuntimeException ex, WebRequest request) {
        if (ex instanceof MissingTransitionCountersException missing) {
            return build(HttpStatus.BAD_REQUEST, ex.getMessage(), detailsForMissing(missing), request);
        }
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), null, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorPayload> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Validation failed", null, request);
    }

    private ResponseEntity<ErrorPayload> build(HttpStatus status, String message, Object details, WebRequest request) {
        String path = null;
        if (request instanceof ServletWebRequest servletRequest) {
            path = servletRequest.getRequest().getRequestURI();
        }
        ErrorPayload body =
                new ErrorPayload(Instant.now(), status.value(), status.getReasonPhrase(), message, path, details);
        return ResponseEntity.status(status).body(body);
    }

    private Map<String, Object> detailsForMissing(MissingTransitionCountersException missing) {
        Map<String, Object> details = new LinkedHashMap<>();
        List<Map<String, Object>> transitions = missing.missingPairs().stream()
                .map(pair -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("fromState", pair.fromState());
                    entry.put("toState", pair.toState());
                    return entry;
                })
                .toList();
        details.put("missingTransitions", transitions);
        return details;
    }
}
