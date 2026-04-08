package com.obsinity.service.core.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServicePartitionKeyTest {

    @Test
    void usesCanonicalEightCharacterLowercaseHexKey() {
        assertThat(ServicePartitionKey.forServiceKey("payments")).isEqualTo("df384ae9");
    }

    @Test
    void normalizesServiceKeyCaseBeforeHashing() {
        assertThat(ServicePartitionKey.forServiceKey("payments"))
                .isEqualTo(ServicePartitionKey.forServiceKey("PAYMENTS"));
    }
}
