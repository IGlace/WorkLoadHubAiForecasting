package com.workloadhub.forecast.run;

import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.ForecastWindow;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** The global half of a run: everything that does not depend on which team is forecast. */
public record Prepared(
        ForecastData data,
        Lifecycle lifecycle,
        WorkingCalendar calendar,
        LocalDate asOf,
        LocalDate origin,
        List<ForecastWindow> windows,
        int[] horizons,
        FeatureMatrix features,
        List<LocalDate> backtestOrigins,
        Backtest.Result backtest,
        Double mae,
        Double meanActualHours,
        Map<Integer, double[]> bandOffsets,
        Map<MemberWeek, Double> predictedHours,
        int historyWeeks,
        Map<String, Double> secondsByPhase) {
}
