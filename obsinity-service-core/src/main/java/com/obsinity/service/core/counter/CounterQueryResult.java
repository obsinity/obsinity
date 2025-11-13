package com.obsinity.service.core.counter;

import java.time.Instant;
import java.util.List;

public record CounterQueryResult(
        List<CounterQueryWindow> windows, int offset, int limit, int totalWindows, Instant start, Instant end) {}
