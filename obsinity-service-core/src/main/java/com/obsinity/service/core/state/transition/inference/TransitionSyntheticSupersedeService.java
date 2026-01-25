package com.obsinity.service.core.state.transition.inference;

import com.obsinity.service.core.model.EventEnvelope;
import com.obsinity.service.core.state.transition.counter.TerminalStateResolver;
import com.obsinity.service.core.state.transition.counter.TransitionCounterEvaluator;
import com.obsinity.service.core.state.transition.counter.TransitionCounterFootprintEntry;
import com.obsinity.service.core.state.transition.counter.TransitionCounterMetricKey;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingIdFactory;
import com.obsinity.service.core.state.transition.counter.TransitionCounterPostingSink;
import com.obsinity.service.core.state.transition.outcome.TransitionOutcomeService;
import com.obsinity.service.core.state.transition.telemetry.TransitionTelemetry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class TransitionSyntheticSupersedeService {
    private static final String OBSINITY_SERVICE_ID = "obsinity";

    private final SyntheticTerminalRecordRepository recordRepository;
    private final TransitionCounterEvaluator counterEvaluator;
    private final TransitionCounterPostingSink postingSink;
    private final TransitionCounterPostingIdFactory postingIdFactory;
    private final TerminalStateResolver terminalStateResolver;
    private final TransitionOutcomeService outcomeService;
    private final TransitionTelemetry telemetry;
    private final Clock clock;

    public boolean handleIfSuperseding(
            UUID serviceId,
            EventEnvelope envelope,
            String objectType,
            String objectId,
            String attribute,
            String newState) {
        if (serviceId == null
                || envelope == null
                || objectType == null
                || objectId == null
                || attribute == null
                || newState == null) {
            return false;
        }
        if (OBSINITY_SERVICE_ID.equals(envelope.getServiceId())) {
            return false;
        }
        if (!terminalStateResolver.terminalStates(serviceId, objectType).contains(newState)) {
            return false;
        }
        List<SyntheticTerminalRecord> actives = recordRepository.findActive(serviceId, objectType, objectId, attribute);
        if (actives.isEmpty()) {
            return false;
        }
        boolean supersededAny = false;
        for (SyntheticTerminalRecord record : actives) {
            if (record == null) {
                continue;
            }
            if (!OBSINITY_SERVICE_ID.equals(record.emitServiceId())) {
                continue;
            }
            Instant supersededAt = Instant.now(clock);
            boolean superseded =
                    recordRepository.supersede(record.syntheticEventId(), envelope.getEventId(), supersededAt);
            if (!superseded) {
                continue;
            }
            supersededAny = true;
            reverseSynthetic(record);
            recordRepository.markReversed(record.syntheticEventId(), Instant.now(clock));
            outcomeService.recordObservedTerminal(
                    serviceId,
                    record.objectType(),
                    record.objectId(),
                    record.attribute(),
                    newState,
                    envelope.getTimestamp(),
                    envelope.getEventId());
            log.info(
                    "Synthetic terminal superseded: serviceId={} objectType={} objectId={} attribute={} ruleId={} syntheticEventId={} realEventId={}",
                    serviceId,
                    record.objectType(),
                    record.objectId(),
                    record.attribute(),
                    record.ruleId(),
                    record.syntheticEventId(),
                    envelope.getEventId());
            if (telemetry != null) {
                telemetry.adjustSyntheticActive(record.objectType(), record.ruleId(), -1);
                telemetry.recordSyntheticSuperseded(
                        record.objectType(),
                        record.ruleId(),
                        java.time.Duration.between(record.syntheticTs(), supersededAt));
            }
        }
        if (!supersededAny) {
            return false;
        }
        counterEvaluator.evaluate(
                serviceId, envelope.getEventId(), envelope.getTimestamp(), objectType, objectId, attribute, newState);
        log.info(
                "Observed terminal applied after supersede: serviceId={} objectType={} objectId={} attribute={} eventId={} state={}",
                serviceId,
                objectType,
                objectId,
                attribute,
                envelope.getEventId(),
                newState);
        return true;
    }

    private void reverseSynthetic(SyntheticTerminalRecord record) {
        List<TransitionCounterFootprintEntry> footprint = record.transitionFootprint();
        if (footprint == null || footprint.isEmpty()) {
            return;
        }
        String reverseEventId = "reverse:" + record.syntheticEventId();
        long reversed = 0;
        for (TransitionCounterFootprintEntry entry : footprint) {
            if (entry == null
                    || entry.fromStates() == null
                    || entry.fromStates().isEmpty()) {
                continue;
            }
            for (String fromState : entry.fromStates()) {
                TransitionCounterMetricKey key = new TransitionCounterMetricKey(
                        record.serviceId(),
                        record.objectType(),
                        record.attribute(),
                        entry.counterName(),
                        fromState,
                        entry.toState());
                String postingId = postingIdFactory.build(reverseEventId, key, -1, record.syntheticTs());
                postingSink.post(key, record.syntheticTs(), -1, postingId);
                reversed++;
            }
        }
        log.info(
                "Synthetic reversal postings applied: syntheticEventId={} reversedCount={}",
                record.syntheticEventId(),
                reversed);
    }
}
