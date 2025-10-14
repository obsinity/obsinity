package com.obsinity.service.core.counter;

import java.util.List;
import java.util.Map;

public record CounterQueryWindow(String from, String to, List<CountEntry> counts) {

    public record CountEntry(Map<String, String> key, long count) {}
}
