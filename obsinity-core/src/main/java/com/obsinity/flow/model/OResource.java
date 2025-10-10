package com.obsinity.flow.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import lombok.Getter;
import lombok.Setter;

/**
 * API-only resource wrapper. No references to OTel SDK types.
 * Use ResourceMapper (in app modules) to convert to/from the SDK Resource.
 */
@JsonInclude(Include.NON_NULL)
@Getter
@Setter
public final class OResource {
    private OAttributes attributes;

    public OResource() {
        this.attributes = new OAttributes();
    }

    public OResource(OAttributes attributes) {
        this.attributes = attributes != null ? attributes : new OAttributes();
    }

    public OAttributes attributes() {
        return attributes;
    }

    /** Convenience add/put. */
    public OResource put(String key, Object value) {
        this.attributes.put(key, value);
        return this;
    }
}
