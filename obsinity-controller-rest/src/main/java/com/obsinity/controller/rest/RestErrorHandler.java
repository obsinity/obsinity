package com.obsinity.controller.rest;

import java.time.Instant;
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
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorPayload> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Validation failed", request);
    }

    private ResponseEntity<ErrorPayload> build(HttpStatus status, String message, WebRequest request) {
        String path = null;
        if (request instanceof ServletWebRequest servletRequest) {
            path = servletRequest.getRequest().getRequestURI();
        }
        ErrorPayload body = new ErrorPayload(Instant.now(), status.value(), status.getReasonPhrase(), message, path);
        return ResponseEntity.status(status).body(body);
    }
}
