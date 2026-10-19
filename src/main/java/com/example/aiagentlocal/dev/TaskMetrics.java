package com.example.aiagentlocal.dev;

import java.util.LinkedHashMap;
import java.util.Map;

public record TaskMetrics(long totalDurationNs, long loadDurationNs, long promptEvalCount,
                          long promptEvalDurationNs, long evalCount, long evalDurationNs) {
    public static final TaskMetrics EMPTY = new TaskMetrics(0, 0, 0, 0, 0, 0);

    public TaskMetrics plus(TaskMetrics other) {
        return new TaskMetrics(totalDurationNs + other.totalDurationNs, loadDurationNs + other.loadDurationNs,
                promptEvalCount + other.promptEvalCount, promptEvalDurationNs + other.promptEvalDurationNs,
                evalCount + other.evalCount, evalDurationNs + other.evalDurationNs);
    }
    public Map<String, Object> display() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("totalSeconds", round(totalDurationNs / 1_000_000_000d));
        values.put("loadSeconds", round(loadDurationNs / 1_000_000_000d));
        values.put("promptTokens", promptEvalCount);
        values.put("promptTokensPerSecond", rate(promptEvalCount, promptEvalDurationNs));
        values.put("generatedTokens", evalCount);
        values.put("generatedTokensPerSecond", rate(evalCount, evalDurationNs));
        return values;
    }
    private static double rate(long tokens, long durationNs) { return durationNs == 0 ? 0 : round(tokens / (durationNs / 1_000_000_000d)); }
    private static double round(double value) { return Math.round(value * 100d) / 100d; }
}
