package com.obsinity.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import lombok.Data;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class PublishEventRequest {

    /** Immutable event identity (ULID/UUIDv7 recommended). */
    @NotBlank
    private String eventId;

    /** Producer start time (occurredAt). */
    @NotNull private Instant timestamp;

    /** Producer end time (optional, for span duration). */
    private Instant endTimestamp;

    /** Optional span name (OTEL Span.name). */
    private String name;

    /** Optional span kind (INTERNAL, SERVER, CLIENT, PRODUCER, CONSUMER). */
    private String kind;

    /** Optional OTEL correlation ids. */
    @Valid
    private Trace trace;

    /** Optional OTEL status. */
    @Valid
    private Status status;

    /** Optional OTEL resource attributes (nested). */
    @Valid
    private Resource resource;

    /** Business/telemetry attributes (nested JSON). */
    @NotNull private JsonNode attributes;

    /** Optional OTEL span events. */
    @Valid
    private List<OtelEvent> events;

    /** Optional OTEL links. */
    @Valid
    private List<OtelLink> links;

    /** Optional free-form correlation id. */
    private String correlationId;

    /** Optional synthetic marker. */
    private Boolean synthetic;

    // ---- nested types ----

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    public static class Trace {
        private String traceId;
        private String spanId;
        private String parentSpanId;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    public static class Status {
        /** One of: UNSET, OK, ERROR (OTEL status codes). */
        private String code;

        private String message;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    public static class Resource {
        /** Nested attributes, e.g. { "service": { "id": "...", "name": "..." } } */
        @NotNull private JsonNode attributes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    public static class OtelEvent {
        @NotBlank
        private String name;

        @NotNull private Instant timestamp;

        private JsonNode attributes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Data
    public static class OtelLink {
        @NotBlank
        private String traceId;

        @NotBlank
        private String spanId;

        private JsonNode attributes;
    }
}
