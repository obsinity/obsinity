package com.obsinity.reference.demodata;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component("demoDataProfileGeneratorProperties")
@ConfigurationProperties(prefix = "demo-data.profiles")
public class DemoProfileGeneratorProperties {

    private boolean enabled;
    private Duration runEvery = Duration.ofSeconds(1);
    private int targetCount;
    private int createPerRun;
    private Long transitionSeed;
    private int oversampleFactor = 5;
    private int maxSelectionPerState = 10_000;
    private String initialState = "NEW";
    private String serviceKey = "payments";
    private String eventType = "user_profile.updated";
    private Map<String, TransitionRule> transitions = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getRunEvery() {
        return runEvery;
    }

    public void setRunEvery(Duration runEvery) {
        this.runEvery = runEvery;
    }

    public int getTargetCount() {
        return targetCount;
    }

    public void setTargetCount(int targetCount) {
        this.targetCount = Math.max(0, targetCount);
    }

    public int getCreatePerRun() {
        return createPerRun;
    }

    public void setCreatePerRun(int createPerRun) {
        this.createPerRun = Math.max(0, createPerRun);
    }

    public Long getTransitionSeed() {
        return transitionSeed;
    }

    public void setTransitionSeed(Long transitionSeed) {
        this.transitionSeed = transitionSeed;
    }

    public int getOversampleFactor() {
        return oversampleFactor;
    }

    public void setOversampleFactor(int oversampleFactor) {
        this.oversampleFactor = oversampleFactor;
    }

    public int getMaxSelectionPerState() {
        return maxSelectionPerState;
    }

    public void setMaxSelectionPerState(int maxSelectionPerState) {
        this.maxSelectionPerState = maxSelectionPerState;
    }

    public String getInitialState() {
        return initialState;
    }

    public void setInitialState(String initialState) {
        this.initialState = initialState;
    }

    public String getServiceKey() {
        return serviceKey;
    }

    public void setServiceKey(String serviceKey) {
        this.serviceKey = serviceKey;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Map<String, TransitionRule> getTransitions() {
        return transitions;
    }

    public void setTransitions(Map<String, TransitionRule> transitions) {
        this.transitions = transitions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(transitions);
    }

    public static class TransitionRule {

        private Selection select = new Selection();
        private List<String> next = List.of();

        public Selection getSelect() {
            return select;
        }

        public void setSelect(Selection select) {
            this.select = select == null ? new Selection() : select;
        }

        public List<String> getNext() {
            return next;
        }

        public void setNext(List<String> next) {
            this.next = next == null ? List.of() : List.copyOf(next);
        }
    }

    public static class Selection {

        private Duration minAge = Duration.ZERO;
        private Duration maxAge;
        private int limitPerRun;

        public Duration getMinAge() {
            return minAge;
        }

        public void setMinAge(Duration minAge) {
            this.minAge = minAge == null ? Duration.ZERO : minAge;
        }

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }

        public int getLimitPerRun() {
            return limitPerRun;
        }

        public void setLimitPerRun(int limitPerRun) {
            this.limitPerRun = Math.max(0, limitPerRun);
        }
    }
}
