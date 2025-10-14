package com.obsinity.controller.rest;

import java.time.Instant;

/** Structured error payload returned by REST endpoints. */
public record ErrorPayload(Instant timestamp, int status, String error, String message, String path) {}
