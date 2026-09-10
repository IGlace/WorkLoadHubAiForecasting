package com.workloadhub.forecast.api;

import java.util.List;
import java.util.Map;

public record RunResult(RunSummary run, List<ModelScore> scores, Map<String, Double> maseByModel, Map<String, String> unavailable,
        List<MemberWindowForecast> memberWindows, List<MemberDayForecast> memberDays, String factsJson) {
}
