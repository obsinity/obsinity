package com.obsinity.reference.demodata;

import java.time.Instant;
import java.util.Random;
import org.springframework.stereotype.Component;

@Component
class SeededRandomProvider implements RandomProvider {

    private final DemoProfileGeneratorProperties properties;

    SeededRandomProvider(DemoProfileGeneratorProperties properties) {
        this.properties = properties;
    }

    @Override
    public Random forRun(Instant now, long runEverySeconds) {
        Long seed = properties.getTransitionSeed();
        if (seed == null) {
            return new Random();
        }
        long bucket = runEverySeconds <= 0 ? now.getEpochSecond() : now.getEpochSecond() / runEverySeconds;
        return new Random(seed ^ bucket);
    }
}
