package com.workloadhub.forecast.seed;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Parameters of one seed run. History spans `weeks` Monday weeks, the last one containing `end`. */
public record SeedConfig(int weeks, LocalDate end, long seed, boolean synthetic, int users) {

    public SeedConfig {
        if (weeks < 4) {
            throw new IllegalArgumentException("weeks must be at least 4");
        }
    }

    public static LocalDate mondayOf(LocalDate d) {
        return d.minusDays(d.getDayOfWeek().getValue() - DayOfWeek.MONDAY.getValue());
    }

    public LocalDate firstMonday() {
        return mondayOf(end).minusWeeks(weeks - 1L);
    }

    public LocalDate lastDay() {
        return end;
    }

    public List<LocalDate> mondays() {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate m = firstMonday(); !m.isAfter(end); m = m.plusWeeks(1)) {
            out.add(m);
        }
        return out;
    }
}
