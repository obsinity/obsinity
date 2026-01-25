package com.obsinity.service.core.state.transition.inference;

import com.obsinity.service.core.config.InferenceRuleDefinition;
import com.obsinity.service.core.config.RegistrySnapshot;
import com.obsinity.service.core.config.ServiceConfig;
import com.obsinity.service.core.state.transition.counter.TransitionCounterEvaluator;
import com.obsinity.service.core.state.transition.outcome.TransitionOutcomeService;
import com.obsinity.service.core.state.transition.telemetry.TransitionTelemetry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransitionInferenceService {
    private final com.obsinity.service.core.config.ConfigRegistry configRegistry;
    private final TransitionInferenceCandidateRepository candidateRepository;
    private final SyntheticTerminalRecordRepository recordRepository;
    private final TransitionCounterEvaluator counterEvaluator;
    private final SyntheticEventIdFactory eventIdFactory;
    private final TransitionOutcomeService outcomeService;
    private final TransitionTelemetry telemetry;

    public void runOnce(Instant now, int batchSize) {
        if (now == null || batchSize <= 0) {
            return;
        }
        RegistrySnapshot snapshot = configRegistry.current();
        Map<UUID, ServiceConfig> services = snapshot.services();
        if (services == null || services.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, ServiceConfig> entry : services.entrySet()) {
            UUID serviceId = entry.getKey();
            ServiceConfig serviceConfig = entry.getValue();
            if (serviceConfig == null || serviceConfig.inferenceRules() == null) {
                continue;
            }
            List<InferenceRuleDefinition> rules = serviceConfig.inferenceRules();
            for (InferenceRuleDefinition rule : rules) {
                if (rule == null) continue;
                runRule(now, batchSize, serviceId, rule);
            }
        }
    }

    private void runRule(Instant now, int batchSize, UUID serviceId, InferenceRuleDefinition rule) {
        Instant cutoff = now.minus(rule.idleFor());
        List<TransitionInferenceCandidate> candidates =
                candidateRepository.findEligible(serviceId, rule.objectType(), cutoff, batchSize);
        for (TransitionInferenceCandidate candidate : candidates) {
            if (candidate.lastEventTs() == null) {
                continue;
            }
            if (rule.nonTerminalOnly() && candidate.terminalState() != null) {
                continue;
            }
            Instant inferredTs = candidate.lastEventTs().plus(rule.idleFor());
            if (inferredTs.isAfter(now)) {
                continue;
            }
            String syntheticEventId = eventIdFactory.build(
                    candidate.objectType(),
                    candidate.objectId(),
                    candidate.attribute(),
                    rule.id(),
                    inferredTs,
                    rule.emitState());
            SyntheticTerminalRecord record = new SyntheticTerminalRecord(
                    serviceId,
                    candidate.objectType(),
                    candidate.objectId(),
                    candidate.attribute(),
                    rule.id(),
                    syntheticEventId,
                    inferredTs,
                    rule.emitState(),
                    rule.emitServiceId(),
                    rule.reason(),
                    "SYNTHETIC",
                    "ACTIVE",
                    candidate.lastEventTs(),
                    candidate.lastState(),
                    null,
                    null,
                    null,
                    null);
            boolean inserted = recordRepository.insertIfEligible(record, candidate.lastEventTs());
            if (!inserted) {
                continue;
            }
            log.info(
                    "Synthetic terminal injected: serviceId={} objectType={} objectId={} attribute={} ruleId={} state={} inferredTs={}",
                    serviceId,
                    candidate.objectType(),
                    candidate.objectId(),
                    candidate.attribute(),
                    rule.id(),
                    rule.emitState(),
                    inferredTs);
            outcomeService.recordSyntheticTerminal(
                    serviceId,
                    candidate.objectType(),
                    candidate.objectId(),
                    candidate.attribute(),
                    rule.emitState(),
                    inferredTs,
                    syntheticEventId);
            if (telemetry != null) {
                telemetry.recordSyntheticInjection(candidate.objectType(), rule.id(), rule.emitState());
                telemetry.adjustSyntheticActive(candidate.objectType(), rule.id(), 1);
            }
            TransitionCounterEvaluator.SyntheticContext syntheticContext =
                    new TransitionCounterEvaluator.SyntheticContext(syntheticEventId, recordRepository);
            counterEvaluator.evaluate(
                    serviceId,
                    syntheticEventId,
                    inferredTs,
                    candidate.objectType(),
                    candidate.objectId(),
                    candidate.attribute(),
                    rule.emitState(),
                    syntheticContext);
        }
    }
}
