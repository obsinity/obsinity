package com.obsinity.service.core.histogram;

import java.util.List;

public record HistogramQueryResult(List<HistogramQueryWindow> windows, int offset, int limit, int totalWindows) {}
