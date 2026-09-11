package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.features.MemberDay;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

class AccuracyProperties {

    static final UUID TEAM = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final LocalDate FROM = LocalDate.of(2026, 8, 3);
    static final LocalDate TO = LocalDate.of(2026, 8, 28);
    static final LocalDate TODAY = LocalDate.of(2026, 9, 6);

    /** {@code members} members, each with one current row per weekday of the range, forecast and logged from the arrays (cycled). */
    static AccuracyResult build(int members, List<Double> forecast, List<Double> logged) {
        List<CurrentDayForecast> current = new ArrayList<>();
        SortedMap<MemberDay, Double> truth = new TreeMap<>();
        int i = 0;
        for (int m = 1; m <= members; m++) {
            UUID user = UUID.fromString(String.format("30000000-0000-0000-0000-%012d", m));
            for (LocalDate d = FROM; !d.isAfter(TO); d = d.plusDays(1)) {
                if (Accuracy.isWeekday(d)) {
                    double f = forecast.get(i % forecast.size());
                    current.add(new CurrentDayForecast(TEAM, user, d, UUID.randomUUID(), f, 0, 0, f, 8, Math.max(0, f - 8), LocalDateTime.of(2026, 8, 1, 9, 0)));
                    truth.put(new MemberDay(user, d), logged.get(i % logged.size()));
                    i++;
                }
            }
        }
        return Accuracy.evaluate(TEAM, FROM, TO, TODAY, current, List.of(), truth);
    }

    @Property
    void maeIsNonNegativeAndZeroWhenForecastEqualsTruth(@ForAll @IntRange(min = 1, max = 4) int members,
            @ForAll @Size(min = 1, max = 12) List<@DoubleRange(min = 0, max = 16) Double> hours) {
        AccuracyResult r = build(members, hours, hours);
        for (AccuracyScore s : r.scores()) {
            assertEquals(0.0, s.mae(), 1e-9, s.scope() + " " + s.key());
            assertEquals(0.0, s.bias(), 1e-9);
        }
    }

    @Property
    void biasFlipsSignWhenForecastAndTruthSwap(@ForAll @IntRange(min = 1, max = 3) int members,
            @ForAll @Size(min = 1, max = 8) List<@DoubleRange(min = 0, max = 16) Double> forecast,
            @ForAll @Size(min = 1, max = 8) List<@DoubleRange(min = 0, max = 16) Double> logged) {
        AccuracyScore a = build(members, forecast, logged).scores().get(0);
        AccuracyScore b = build(members, logged, forecast).scores().get(0);
        assertEquals(a.bias(), -b.bias(), 1e-9);
        assertEquals(a.mae(), b.mae(), 1e-9);
        assertTrue(a.mae() >= 0);
    }

    @Property
    void theTeamCountIsTheSumOfTheMemberCountsAndEveryRowIsAWeekdayInRange(@ForAll @IntRange(min = 1, max = 5) int members,
            @ForAll @Size(min = 1, max = 6) List<@DoubleRange(min = 0, max = 16) Double> hours) {
        AccuracyResult r = build(members, hours, hours);
        int memberSum = r.scores().stream().filter(s -> s.scope().equals(Accuracy.MEMBER)).mapToInt(AccuracyScore::n).sum();
        assertEquals(r.scores().get(0).n(), memberSum);
        assertEquals(r.current().size(), memberSum);
        for (AccuracyRow row : r.current()) {
            assertTrue(Accuracy.isWeekday(row.day()) && !row.day().isBefore(FROM) && !row.day().isAfter(TO));
            assertTrue(row.day().isBefore(r.evaluatedAt()));
        }
    }
}
