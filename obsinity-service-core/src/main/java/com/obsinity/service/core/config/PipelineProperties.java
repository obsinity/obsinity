package com.obsinity.service.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "obsinity")
public class PipelineProperties {
    private PipelineConfig counters = new PipelineConfig();
    private PipelineConfig histograms = new PipelineConfig();
    private PipelineConfig stateTransitions = new PipelineConfig();

    public PipelineConfig getCounters() {
        return counters;
    }

    public void setCounters(PipelineConfig counters) {
        this.counters = counters;
    }

    public PipelineConfig getHistograms() {
        return histograms;
    }

    public void setHistograms(PipelineConfig histograms) {
        this.histograms = histograms;
    }

    public PipelineConfig getStateTransitions() {
        return stateTransitions;
    }

    public void setStateTransitions(PipelineConfig stateTransitions) {
        this.stateTransitions = stateTransitions;
    }

    public static class PipelineConfig {
        private Persist persist = new Persist();
        private Flush flush = new Flush();

        public Persist getPersist() {
            return persist;
        }

        public void setPersist(Persist persist) {
            this.persist = persist;
        }

        public Flush getFlush() {
            return flush;
        }

        public void setFlush(Flush flush) {
            this.flush = flush;
        }
    }

    public static class Persist {
        private int workers = 10;
        private int queueCapacity = 20000;

        public int getWorkers() {
            return workers;
        }

        public void setWorkers(int workers) {
            this.workers = workers;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Flush {
        private int maxBatchSize = 5000;
        private Rate rate = new Rate();

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }

        public Rate getRate() {
            return rate;
        }

        public void setRate(Rate rate) {
            this.rate = rate;
        }
    }

    public static class Rate {
        private long s5 = 5000;

        public long getS5() {
            return s5;
        }

        public void setS5(long s5) {
            this.s5 = s5;
        }
    }
}
