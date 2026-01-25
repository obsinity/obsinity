package com.obsinity.service.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.model.config.ServiceConfig;
import com.obsinity.service.core.model.config.StateExtractorConfig;
import com.obsinity.service.core.model.config.TransitionCounterConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigMaterializerTransitionCountersTest {

    private final ConfigMaterializer materializer = new ConfigMaterializer(new ObjectMapper());

    @Test
    void materializeDefaultsToLastState() {
        ServiceConfig service =
                baseService(List.of(new TransitionCounterConfig("to_FINISHED_default", "X", "FINISHED", null, null)));

        ConfigMaterializer.ServiceConfigView view =
                materializer.materializeService(service, UUID.randomUUID(), "svc", Instant.now());

        assertThat(view.transitionCounters()).hasSize(1);
        TransitionCounterDefinition definition = view.transitionCounters().get(0);
        assertThat(definition.fromMode()).isEqualTo(FromMode.DEFAULT_LAST);
        assertThat(definition.untilTerminal()).isFalse();
        assertThat(definition.toState()).isEqualTo("FINISHED");
    }

    @Test
    void materializeFromAnySeen() {
        ServiceConfig service =
                baseService(List.of(new TransitionCounterConfig("to_ABANDONED_from_any", "X", "ABANDONED", "*", null)));

        ConfigMaterializer.ServiceConfigView view =
                materializer.materializeService(service, UUID.randomUUID(), "svc", Instant.now());

        TransitionCounterDefinition definition = view.transitionCounters().get(0);
        assertThat(definition.fromMode()).isEqualTo(FromMode.ANY_SEEN);
        assertThat(definition.fromStates()).isEmpty();
    }

    @Test
    void materializeFromSubsetList() {
        ServiceConfig service = baseService(List.of(
                new TransitionCounterConfig("subset", "X", "DONE", List.of("PENDING", "ACTIVE", "PENDING"), null)));

        ConfigMaterializer.ServiceConfigView view =
                materializer.materializeService(service, UUID.randomUUID(), "svc", Instant.now());

        TransitionCounterDefinition definition = view.transitionCounters().get(0);
        assertThat(definition.fromMode()).isEqualTo(FromMode.SUBSET);
        assertThat(definition.fromStates()).containsExactly("PENDING", "ACTIVE");
    }

    @Test
    void materializeFromSubsetAllowsNullInitial() {
        java.util.List<Object> fromStates = java.util.Arrays.asList(null, "PENDING");
        ServiceConfig service =
                baseService(List.of(new TransitionCounterConfig("subset", "X", "DONE", fromStates, null)));

        ConfigMaterializer.ServiceConfigView view =
                materializer.materializeService(service, UUID.randomUUID(), "svc", Instant.now());

        TransitionCounterDefinition definition = view.transitionCounters().get(0);
        assertThat(definition.fromMode()).isEqualTo(FromMode.SUBSET);
        assertThat(definition.fromStates()).containsExactly(null, "PENDING");
    }

    @Test
    void materializeUntilTerminal() {
        ServiceConfig service = baseService(List.of(new TransitionCounterConfig("open_objects", "X", null, "*", true)));

        ConfigMaterializer.ServiceConfigView view =
                materializer.materializeService(service, UUID.randomUUID(), "svc", Instant.now());

        TransitionCounterDefinition definition = view.transitionCounters().get(0);
        assertThat(definition.untilTerminal()).isTrue();
        assertThat(definition.toState()).isNull();
        assertThat(definition.fromMode()).isEqualTo(FromMode.ANY_SEEN);
    }

    @Test
    void materializeRejectsInvalidCombos() {
        ServiceConfig invalidFrom =
                baseService(List.of(new TransitionCounterConfig("bad-from", "X", "DONE", List.of("*"), null)));

        assertThatThrownBy(() -> materializer.materializeService(invalidFrom, UUID.randomUUID(), "svc", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);

        ServiceConfig invalidUntil =
                baseService(List.of(new TransitionCounterConfig("bad-until", "X", "DONE", null, true)));

        assertThatThrownBy(() -> materializer.materializeService(invalidUntil, UUID.randomUUID(), "svc", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ServiceConfig baseService(List<TransitionCounterConfig> counters) {
        return new ServiceConfig(
                "svc",
                "snapshot",
                Instant.now(),
                ServiceConfig.EMPTY_DEFAULTS,
                List.of(),
                List.of(new StateExtractorConfig("event", "X", "id", List.of("state"), null)),
                counters,
                List.of());
    }
}
