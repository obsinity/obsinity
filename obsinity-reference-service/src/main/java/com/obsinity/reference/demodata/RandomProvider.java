package com.obsinity.reference.demodata;

import java.time.Instant;
import java.util.Random;

interface RandomProvider {
    Random forRun(Instant now, long runEverySeconds);
}
