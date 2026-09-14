package com.workloadhub.forecast.api;

import java.util.List;

public record RunResult(RunSummary run, List<BacktestScore> scores, List<MemberWindowForecast> memberWindows, List<MemberDayForecast> memberDays,
        String factsJson) {
}
