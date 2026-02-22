package com.obsinity.service.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.model.config.RatioQueryConfig;
import com.obsinity.service.core.model.config.ServiceConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigMaterializerRatioQueriesTest {

    private final ConfigMaterializer materializer = new ConfigMaterializer(new ObjectMapper());

    @Test
    void materializesRatioQueryDefinitionsWithDefaults() {
        ServiceConfig service = new ServiceConfig(
                "payments",
                "snapshot-1",
                Instant.now(),
                ServiceConfig.EMPTY_DEFAULTS,
                List.of(),
                List.of(),
                List.of(new RatioQueryConfig(
                        "state_distribution",
                        "ratio",
                        "states",
                        "UserProfile",
                        "user.status",
                        new RatioQueryConfig.Window("-1h", "now"),
                        List.of(new RatioQueryConfig.Item("ACTIVE", null, null)),
                        null,
                        null)));

        ConfigMaterializer.ServiceConfigView view =
                materializer.materializeService(service, UUID.randomUUID(), "payments", Instant.now());

        assertThat(view.ratioQueries()).containsKey("state_distribution");
        RatioQueryDefinition definition = view.ratioQueries().get("state_distribution");
        assertThat(definition.output().valueMode()).isEqualTo(RatioQueryDefinition.ValueMode.COUNT);
        assertThat(definition.items()).hasSize(1);
        assertThat(definition.items().get(0).label()).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsInvalidStateSourceItems() {
        ServiceConfig service = new ServiceConfig(
                "payments",
                "snapshot-1",
                Instant.now(),
                ServiceConfig.EMPTY_DEFAULTS,
                List.of(),
                List.of(),
                List.of(new RatioQueryConfig(
                        "invalid_ratio",
                        "ratio",
                        "states",
                        "UserProfile",
                        "user.status",
                        new RatioQueryConfig.Window("-1h", "now"),
                        List.of(new RatioQueryConfig.Item(null, "NEW->ACTIVE", "Bad")),
                        null,
                        null)));

        assertThatThrownBy(() -> materializer.materializeService(service, UUID.randomUUID(), "payments", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must provide state for source=states");
    }
}
