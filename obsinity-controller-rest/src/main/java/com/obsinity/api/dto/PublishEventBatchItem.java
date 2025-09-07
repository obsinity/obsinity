package com.obsinity.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Heterogeneous batch item: carries its own routing (serviceId,eventType)
 * and the standard PublishEventRequest payload.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class PublishEventBatchItem {

    /** Target service namespace (a.k.a. schema). */
    @NotBlank
    private String serviceId;

    /** Target event type within the service. */
    @NotBlank
    private String eventType;

    /** The event payload (camelCase, OTEL-aware, nested attributes). */
    @Valid
    @NotNull private PublishEventRequest event;
}
