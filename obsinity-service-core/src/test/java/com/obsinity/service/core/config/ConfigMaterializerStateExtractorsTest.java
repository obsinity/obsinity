package com.obsinity.service.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obsinity.service.core.model.config.ServiceConfig;
import com.obsinity.service.core.model.config.StateExtractorConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigMaterializerStateExtractorsTest {

    private final ConfigMaterializer materializer = new ConfigMaterializer(new ObjectMapper());

    @Test
    void materializeServiceFiltersInvalidExtractorsAndNormalizesValues() {
        ServiceConfig service = new ServiceConfig(
                "payments",
                "snapshot-1",
                Instant.now(),
                ServiceConfig.EMPTY_DEFAULTS,
                List.of(),
                List.of(
                        new StateExtractorConfig(
                                " account.updated ",
                                "Account",
                                " account_id ",
                                List.of(" status ", "phase", "  "),
                                new StateExtractorConfig.TransitionPolicyConfig(List.of())),
                        new StateExtractorConfig(null, "Missing", "id", List.of("state"), null)),
                List.of());

        ConfigMaterializer.ServiceConfigView view =
                materializer.materializeService(service, UUID.randomUUID(), "payments", Instant.now());

        assertThat(view.stateExtractors()).hasSize(1);
        StateExtractorDefinition extractor = view.stateExtractors().get(0);
        assertThat(extractor.rawType()).isEqualTo("account.updated");
        assertThat(extractor.objectType()).isEqualTo("Account");
        assertThat(extractor.objectIdField()).isEqualTo("account_id");
        assertThat(extractor.stateAttributes()).containsExactly("status", "phase");
        assertThat(extractor.transitionOnlyFromStates()).isEmpty();
        assertThat(extractor.transitionAdditionalFromStates()).isEmpty();
    }

    @Test
    void materializeServiceMapsLegacyFromStatesToAdditionalAndSupportsOnly() {
        ServiceConfig service = new ServiceConfig(
                "payments",
                "snapshot-2",
                Instant.now(),
                ServiceConfig.EMPTY_DEFAULTS,
                List.of(),
                List.of(
                        new StateExtractorConfig(
                                "account.updated",
                                "Account",
                                "account_id",
                                List.of("status"),
                                new StateExtractorConfig.TransitionPolicyConfig(
                                        List.of("NEW"), List.of("?"), List.of("ACTIVE")))),
                List.of());

        ConfigMaterializer.ServiceConfigView view =
                materializer.materializeService(service, UUID.randomUUID(), "payments", Instant.now());

        assertThat(view.stateExtractors()).hasSize(1);
        StateExtractorDefinition extractor = view.stateExtractors().get(0);
        assertThat(extractor.transitionOnlyFromStates()).containsExactly("?");
        assertThat(extractor.transitionAdditionalFromStates()).containsExactly("ACTIVE", "NEW");
    }
}
