# Rolling Forecast Windows Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A forecast starts the first weekday after the run day and covers ten weekdays in two windows of five; demand and capacity are computed per day and summed per window; each run stores its windows and days and upserts a per-day current forecast; the server takes the run day from its clock.

**Architecture:** One additive task (the horizon, per-day placement, per-day capacity, per-day effort placement) followed by one core task that switches the pipeline, the facts, the contract, the store (migration V3) and the evaluation harness to windows and days, one task that removes the caller's `asOf` (clock, REST body, current-forecast route, CLI `current`), and one task for skills and documents. Everything is Java 21 under `server/`; `mvn -B -q verify` in `server/` must be green after every task (about three minutes, use a 600000 ms timeout; jqwik prints an "If you are an AI Agent..." sentence in its banner, which is library output to ignore).

**Tech Stack:** Java 21, Spring Boot 4.1, Jackson 3 (`tools.jackson`), JUnit 6, jqwik 1.10, Flyway, SQLite and PostgreSQL (Testcontainers when Docker is present), picocli.

**Spec:** `docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md` (read it first; every section number below refers to it). Background: `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`.

## Global Constraints

- The language model never produces a forecast number; demand is never capped by capacity (a window's overload is `max(0, demand − capacity)`).
- The arrival models, the feature matrix (`Features.HORIZONS = {1, 2, 3}`, Monday weeks) and `Weeks.lastCompleteWeek` are unchanged; `Weeks.forecastWeeks` is removed in Task 2.
- A window is five weekdays; holidays inside stay inside with zero capacity and zero arrivals; two windows are contiguous; the first weekday after the run day starts window 1 (Friday, Saturday, Sunday → Monday). Spec section 3 worked examples: run 2026-09-09 (Wednesday) → 09-10..09-16 and 09-17..09-23; run 09-11, 09-12 or 09-13 → 09-14..09-18 and 09-21..09-25.
- Per-day rules: spec sections 4 and 5 verbatim (arrivals `prediction / working days of that week` on the horizon's working days of that week; day capacity `0` off working days, the application's row `available / working days of the week`, else `base / 5 − that day's absence hours`, floored at 0).
- Migration V3 on both dialects exactly as spec section 7 (tables `forecast_member_windows`, `forecast_member_days`, `forecast_current_days`, index `forecast_current_days_team_idx`; `forecast_member_weeks` dropped, no data migrated); the upsert is `ON CONFLICT (team_id, user_id, day) DO UPDATE`.
- Facts keys exactly as spec section 9: `run.windows[{index,start,end,working_days}]`, `forecast[].window|start|end|...`, `forecast[].days[]`, `team.totals[].window|start|end`, `likely_work.planned[].expected_window`, contract fields `move.window` and `adjustment.window` (an ISO date equal to a window's `start`).
- The evaluation's arrival level (`Harness.HORIZONS = {1, 2}`, `scores.csv`) is unchanged; `demand.csv` columns become `model,origin,team_id,member_id,window,window_start,window_end,forecast,truth,capacity,open_hours,new_hours,planned_hours`.
- REST: `POST /runs` with `asOf` → 400 `INVALID_REQUEST`, message `asOf is not accepted: a run always starts from today`; `GET /teams/{teamId}/current?from&to` (defaults today and today + 20 days).
- CLI: `run --as-of` stays (description `Run day, ISO (default: today; an experiment override, the server always uses today)`); new `current` command.
- Tests: TDD; jqwik properties for the horizon, the day placement, the window sums, the day capacity bounds and the current-forecast overwrite; no test talks to Copilot.
- Commit messages: imperative subject, a short body saying why, then after a blank line the two trailer lines the dispatch names. Do not push. No model identifier in any committed content.
- Test data: `SeededData.asOf()` is Sunday 2026-09-06, so its windows are 2026-09-07..09-11 and 09-14..09-18 with horizons `{2, 3}` (origin 2026-08-24). `PlannedWorkTest.AS_OF` is Wednesday 2026-09-02, so its first forecast day is Thursday 2026-09-03.

---

## File structure

| Task | Creates | Modifies | Removes |
|---|---|---|---|
| 1 | `calendar/ForecastWindow.java`, `calendar/Horizon.java`, `features/MemberDay.java`, tests `HorizonTest` | `calendar/HourPlacement.java`, `capacity/CapacityRule.java`, `model/EffortModel.java`, tests `HourPlacementTest`, `CapacityRuleTest`, `EffortModelTest` | |
| 2 | `api/MemberWindowForecast.java`, `api/MemberDayForecast.java`, `api/CurrentDayForecast.java`, `db/forecast/{sqlite,postgresql}/V3__windows_and_days.sql` | `planned/PlannedWork.java`, `run/ForecastRunner.java`, `run/Prepared.java`, `run/TeamOutcome.java`, `api/RunResult.java`, `store/JdbcRunStore.java`, `service/DefaultForecastService.java`, `facts/FactsBuilder.java`, `ai/NarrativeContract.java`, `ai/FactsTools.java`, `ai/Prompts.java`, `resources/ai/contract.schema.json`, `eval/{Harness,DemandRow,Report,Truth}.java`, `calendar/Weeks.java`, `cli/RunCommand.java`, and their tests | `api/MemberWeekForecast.java` |
| 3 | `web/RunRequestBody.java`, `cli/CurrentCommand.java` | `api/RunRequest.java`, `api/ForecastService.java`, `service/DefaultForecastService.java`, `ForecastAutoConfiguration.java`, `web/ForecastController.java`, `store/JdbcRunStore.java`, `cli/{RunCommand,ForecastCli,Services}.java`, tests | |
| 4 | | the six `skills/whf-*/SKILL.md`, `ai/FactsTools.java` descriptions, `ai/Prompts.java` rule 5, `SkillTextsTest`, `server/README.md`, `CLAUDE.md`, `docs/backlog.md`, the Java spec, `docs/requirements/requirements-v1.md` | |

Package prefix everywhere: `com.workloadhub.forecast`, under `server/forecast-core/src/main/java/com/workloadhub/forecast/` (main) and `server/forecast-core/src/test/java/com/workloadhub/forecast/` (tests); the CLI is `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/`.

---

### Task 1: The horizon, per-day placement, per-day capacity and per-day effort placement (additive)

**Files:**
- Create: `calendar/ForecastWindow.java`, `calendar/Horizon.java`, `features/MemberDay.java`, `test/.../calendar/HorizonTest.java`
- Modify: `calendar/HourPlacement.java`, `capacity/CapacityRule.java`, `model/EffortModel.java`, `test/.../calendar/HourPlacementTest.java`, `test/.../capacity/CapacityRuleTest.java`, `test/.../model/EffortModelTest.java`

**Interfaces:**
- Produces: `ForecastWindow(int index, LocalDate start, LocalDate end, List<LocalDate> weekdays)` with `contains(LocalDate)`; `Horizon.firstDay(LocalDate)`, `Horizon.windows(LocalDate)`, `Horizon.days(List<ForecastWindow>)`, `Horizon.horizons(LocalDate origin, List<ForecastWindow>)`, `Horizon.WINDOWS = 2`, `Horizon.WEEKDAYS_PER_WINDOW = 5`; `MemberDay(UUID member, LocalDate day)` comparable; `HourPlacement.placeHoursByDay(double, LocalDate, LocalDate, WorkingCalendar, Set<LocalDate>)`; `CapacityRule.dayCapacity(MemberRow, LocalDate, ForecastData, WorkingCalendar)` and `dayAbsenceHours(UUID, LocalDate, ForecastData)`; `EffortModel.placeOpenTasksByDay(...)` and `placeNewArrivalsByDay(Map<MemberDay, Double>, ...)` returning `SortedMap<MemberDay, Double>`.
- Nothing existing changes signature; `placeHours`, `placeOpenTasks`, `placeNewArrivals`, `capacity`, `absenceHours` keep working (the first two now delegate to the per-day forms).

- [ ] **Step 1: Failing tests for the horizon**

Create `test/.../calendar/HorizonTest.java`:

```java
package com.workloadhub.forecast.calendar;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.time.api.constraints.DateRange;
import org.junit.jupiter.api.Test;

class HorizonTest {

    @Property
    boolean tenDistinctWeekdaysAfterTheRunDayInTwoContiguousWindows(@ForAll @DateRange(min = "2020-01-01", max = "2030-12-31") LocalDate asOf) {
        List<ForecastWindow> w = Horizon.windows(asOf);
        List<LocalDate> days = Horizon.days(w);
        boolean shape = w.size() == 2 && days.size() == 10 && new HashSet<>(days).size() == 10
                && w.get(0).weekdays().size() == 5 && w.get(1).weekdays().size() == 5 && w.get(0).index() == 1 && w.get(1).index() == 2;
        boolean order = days.get(0).equals(Horizon.firstDay(asOf)) && days.get(0).isAfter(asOf);
        boolean noWeekend = days.stream().noneMatch(Horizon::isWeekend);
        boolean contiguous = true;
        for (int i = 1; i < days.size(); i++) {
            LocalDate next = days.get(i - 1).plusDays(1);
            while (Horizon.isWeekend(next)) {
                next = next.plusDays(1);
            }
            contiguous &= days.get(i).equals(next);
        }
        boolean ends = w.get(0).start().equals(days.get(0)) && w.get(0).end().equals(days.get(4)) && w.get(1).start().equals(days.get(5))
                && w.get(1).end().equals(days.get(9));
        return shape && order && noWeekend && contiguous && ends;
    }

    @Property
    boolean aFridayOrWeekendRunStartsOnMondayOthersTomorrow(@ForAll @DateRange(min = "2020-01-01", max = "2030-12-31") LocalDate asOf) {
        LocalDate first = Horizon.firstDay(asOf);
        DayOfWeek dow = asOf.getDayOfWeek();
        boolean lateInWeek = dow == DayOfWeek.FRIDAY || dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        return lateInWeek ? first.getDayOfWeek() == DayOfWeek.MONDAY && first.toEpochDay() - asOf.toEpochDay() <= 3 : first.equals(asOf.plusDays(1));
    }

    @Test
    void theWorkedExamplesOfTheDesign() {
        List<ForecastWindow> wed = Horizon.windows(LocalDate.of(2026, 9, 9));
        assertEquals(LocalDate.of(2026, 9, 10), wed.get(0).start());
        assertEquals(LocalDate.of(2026, 9, 16), wed.get(0).end());
        assertEquals(LocalDate.of(2026, 9, 17), wed.get(1).start());
        assertEquals(LocalDate.of(2026, 9, 23), wed.get(1).end());
        assertEquals(List.of(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 16)), wed.get(0).weekdays());
        assertTrue(wed.get(0).contains(LocalDate.of(2026, 9, 14)) && !wed.get(0).contains(LocalDate.of(2026, 9, 12)));
        for (LocalDate asOf : List.of(LocalDate.of(2026, 9, 11), LocalDate.of(2026, 9, 12), LocalDate.of(2026, 9, 13))) {
            List<ForecastWindow> w = Horizon.windows(asOf);
            assertEquals(LocalDate.of(2026, 9, 14), w.get(0).start(), asOf.toString());
            assertEquals(LocalDate.of(2026, 9, 18), w.get(0).end());
            assertEquals(LocalDate.of(2026, 9, 21), w.get(1).start());
            assertEquals(LocalDate.of(2026, 9, 25), w.get(1).end());
        }
        List<ForecastWindow> mon = Horizon.windows(LocalDate.of(2026, 9, 7));
        assertEquals(LocalDate.of(2026, 9, 8), mon.get(0).start());
        assertEquals(LocalDate.of(2026, 9, 14), mon.get(0).end());
        assertEquals(LocalDate.of(2026, 9, 21), mon.get(1).end());
    }

    @Test
    void horizonsAreTheWeeksTheDaysTouchCountedFromTheOrigin() {
        assertArrayEquals(new int[] {1, 2, 3}, Horizon.horizons(Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 9)), Horizon.windows(LocalDate.of(2026, 9, 9))));
        assertArrayEquals(new int[] {1, 2, 3}, Horizon.horizons(Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 7)), Horizon.windows(LocalDate.of(2026, 9, 7))));
        assertArrayEquals(new int[] {2, 3}, Horizon.horizons(Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 11)), Horizon.windows(LocalDate.of(2026, 9, 11))));
        assertArrayEquals(new int[] {2, 3}, Horizon.horizons(Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 6)), Horizon.windows(LocalDate.of(2026, 9, 6))));
    }
}
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=HorizonTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`Horizon`, `ForecastWindow` do not exist).

- [ ] **Step 2: The horizon**

Create `calendar/ForecastWindow.java`:

```java
package com.workloadhub.forecast.calendar;

import java.time.LocalDate;
import java.util.List;

/** Five weekdays of the horizon: its index (1 or 2), its first and last weekday, and its weekdays in order (holidays included, weekends never). */
public record ForecastWindow(int index, LocalDate start, LocalDate end, List<LocalDate> weekdays) {

    public ForecastWindow {
        weekdays = List.copyOf(weekdays);
        if (weekdays.isEmpty() || !weekdays.get(0).equals(start) || !weekdays.get(weekdays.size() - 1).equals(end)) {
            throw new IllegalArgumentException("a window's start and end are its first and last weekday");
        }
    }

    public boolean contains(LocalDate day) {
        return weekdays.contains(day);
    }
}
```

Create `calendar/Horizon.java`:

```java
package com.workloadhub.forecast.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** The rolling horizon: two windows of five weekdays starting the first weekday after the run day (design 2026-09-10, section 3). */
public final class Horizon {

    public static final int WINDOWS = 2;
    public static final int WEEKDAYS_PER_WINDOW = 5;

    private Horizon() {
    }

    /** The first weekday after {@code asOf}: a Friday, Saturday or Sunday run starts on the next Monday. */
    public static LocalDate firstDay(LocalDate asOf) {
        LocalDate d = asOf.plusDays(1);
        while (isWeekend(d)) {
            d = d.plusDays(1);
        }
        return d;
    }

    public static List<ForecastWindow> windows(LocalDate asOf) {
        List<ForecastWindow> out = new ArrayList<>(WINDOWS);
        LocalDate d = firstDay(asOf);
        for (int i = 1; i <= WINDOWS; i++) {
            List<LocalDate> days = new ArrayList<>(WEEKDAYS_PER_WINDOW);
            while (days.size() < WEEKDAYS_PER_WINDOW) {
                if (!isWeekend(d)) {
                    days.add(d);
                }
                d = d.plusDays(1);
            }
            out.add(new ForecastWindow(i, days.get(0), days.get(days.size() - 1), days));
        }
        return List.copyOf(out);
    }

    /** Every weekday of the horizon, window 1 then window 2. */
    public static List<LocalDate> days(List<ForecastWindow> windows) {
        List<LocalDate> out = new ArrayList<>();
        for (ForecastWindow w : windows) {
            out.addAll(w.weekdays());
        }
        return List.copyOf(out);
    }

    /** The horizons (weeks after {@code origin}) the windows touch, ascending and distinct. */
    public static int[] horizons(LocalDate origin, List<ForecastWindow> windows) {
        TreeSet<Integer> hs = new TreeSet<>();
        for (LocalDate d : days(windows)) {
            hs.add((int) Weeks.weeksBetween(origin, d));
        }
        return hs.stream().mapToInt(Integer::intValue).toArray();
    }

    static boolean isWeekend(LocalDate d) {
        DayOfWeek dow = d.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }
}
```

Create `features/MemberDay.java`:

```java
package com.workloadhub.forecast.features;

import com.workloadhub.forecast.data.Ids;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

/** One counted member on one day: the key of the per-day placements. */
public record MemberDay(UUID member, LocalDate day) implements Comparable<MemberDay> {

    private static final Comparator<MemberDay> ORDER = Comparator.comparing(MemberDay::member, Ids.UUID_ORDER).thenComparing(MemberDay::day);

    @Override
    public int compareTo(MemberDay o) {
        return ORDER.compare(this, o);
    }
}
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=HorizonTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (two properties, two tests).

- [ ] **Step 3: Failing tests for the day placement**

Add to `test/.../calendar/HourPlacementTest.java` (add `import java.util.Map;`):

```java
    @Property
    boolean dayPlacementConservesHoursOnWorkingDaysFromStart(@ForAll @DoubleRange(min = 0, max = 200) double hours,
            @ForAll @IntRange(min = 0, max = 40) int span) {
        LocalDate start = LocalDate.of(2026, 3, 4);
        LocalDate off = LocalDate.of(2026, 3, 6);
        SortedMap<LocalDate, Double> out = HourPlacement.placeHoursByDay(hours, start, start.plusDays(span), CAL, Set.of(off));
        double sum = out.values().stream().mapToDouble(Double::doubleValue).sum();
        boolean working = out.keySet().stream().allMatch(d -> CAL.isWorkingDay(d) && !d.equals(off) && !d.isBefore(start));
        return Math.abs(sum - hours) < 1e-6 && working;
    }

    @Test
    void dayPlacementIsEvenAndFallsBackToTheStartDayWhenNoDayWorks() {
        assertEquals(2.5, HourPlacement.placeHoursByDay(10, LocalDate.of(2026, 3, 5), LocalDate.of(2026, 3, 10), CAL, Set.of()).get(LocalDate.of(2026, 3, 9)), 1e-9);
        assertEquals(Map.of(LocalDate.of(2026, 3, 7), 3.0), HourPlacement.placeHoursByDay(3, LocalDate.of(2026, 3, 7), LocalDate.of(2026, 3, 8), CAL, Set.of()));
    }
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=HourPlacementTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`placeHoursByDay` does not exist).

- [ ] **Step 4: The day placement**

Replace the body of `calendar/HourPlacement.java` with:

```java
package com.workloadhub.forecast.calendar;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/** Spreads hours evenly over working days, per day or summed per Monday week. */
public final class HourPlacement {

    private HourPlacement() {
    }

    /** Hours per working day between start and end (evenly); all of them on {@code start} when no day in the range works. */
    public static SortedMap<LocalDate, Double> placeHoursByDay(double hours, LocalDate start, LocalDate end, WorkingCalendar cal, Set<LocalDate> off) {
        LocalDate last = end.isBefore(start) ? start : end;
        List<LocalDate> days = cal.workingDays(start, last, off);
        SortedMap<LocalDate, Double> out = new TreeMap<>();
        if (days.isEmpty()) {
            out.put(start, hours);
            return out;
        }
        double perDay = hours / days.size();
        for (LocalDate d : days) {
            out.put(d, perDay);
        }
        return out;
    }

    /** The day placement summed per Monday week. */
    public static SortedMap<LocalDate, Double> placeHours(double hours, LocalDate start, LocalDate end, WorkingCalendar cal, Set<LocalDate> off) {
        SortedMap<LocalDate, Double> out = new TreeMap<>();
        placeHoursByDay(hours, start, end, cal, off).forEach((d, h) -> out.merge(Weeks.mondayOf(d), h, Double::sum));
        return out;
    }
}
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=HourPlacementTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (the existing weekly tests included: the fallback week is still `mondayOf(start)`).

- [ ] **Step 5: Failing tests for the day capacity**

Add to `test/.../capacity/CapacityRuleTest.java` (add imports `net.jqwik.api.ForAll`, `net.jqwik.api.Property`, `net.jqwik.api.constraints.DoubleRange`, `net.jqwik.api.constraints.IntRange`):

```java
    @Test
    void dayCapacityIsZeroOffWorkingDaysAndBaseOverFiveMinusTheDaysAbsence() {
        ForecastData d = data(List.of(new CapacityRow(M, LocalDate.of(2026, 3, 2), 36, 0, 36)),
                List.of(new AbsenceRow(M, LocalDate.of(2026, 4, 28), 4), new AbsenceRow(M, LocalDate.of(2026, 4, 29), 20)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(40);
        MemberRow m = d.members().get(0);
        assertEquals(7.2, rule.dayCapacity(m, LocalDate.of(2026, 4, 27), d, cal), 1e-9, "the latest base, 36 h, over five days");
        assertEquals(3.2, rule.dayCapacity(m, LocalDate.of(2026, 4, 28), d, cal), 1e-9, "minus the 4 h absence of that day");
        assertEquals(0.0, rule.dayCapacity(m, LocalDate.of(2026, 4, 29), d, cal), 1e-9, "an absence longer than the day floors at zero");
        assertEquals(0.0, rule.dayCapacity(m, LocalDate.of(2026, 5, 1), d, cal), 1e-9, "Labour Day");
        assertEquals(0.0, rule.dayCapacity(m, LocalDate.of(2026, 5, 2), d, cal), 1e-9, "Saturday");
        assertEquals(4.0, rule.dayAbsenceHours(M, LocalDate.of(2026, 4, 28), d), 1e-9);
        assertEquals(0.0, rule.dayAbsenceHours(M, LocalDate.of(2026, 4, 27), d), 1e-9);
        ForecastData none = data(List.of(), List.of());
        assertEquals(8.0, rule.dayCapacity(none.members().get(0), LocalDate.of(2026, 3, 16), none, cal), 1e-9, "the default 40 h over five days");
    }

    @Test
    void theApplicationsOwnWeekRowIsSpreadOverTheWeeksWorkingDays() {
        ForecastData d = data(List.of(new CapacityRow(M, LocalDate.of(2026, 4, 27), 40, 8, 24)), List.of());
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        // the week of 27 April has four working days (Labour Day on the Friday): 24 h available over four days
        assertEquals(6.0, new CapacityRule(40).dayCapacity(d.members().get(0), LocalDate.of(2026, 4, 28), d, cal), 1e-9);
        assertEquals(0.0, new CapacityRule(40).dayCapacity(d.members().get(0), LocalDate.of(2026, 5, 1), d, cal), 1e-9);
    }

    @Property
    boolean dayCapacityStaysBetweenZeroAndBaseOverFive(@ForAll @DoubleRange(min = 0, max = 24) double absence, @ForAll @IntRange(min = 0, max = 13) int offset) {
        LocalDate day = LocalDate.of(2026, 4, 20).plusDays(offset);
        ForecastData d = data(List.of(), List.of(new AbsenceRow(M, day, absence)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        double c = new CapacityRule(40).dayCapacity(d.members().get(0), day, d, cal);
        return c >= 0 && c <= 8.0 + 1e-9 && (cal.isWorkingDay(day) || c == 0.0);
    }
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=CapacityRuleTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`dayCapacity`, `dayAbsenceHours` do not exist).

- [ ] **Step 6: The day capacity**

In `capacity/CapacityRule.java` add `import com.workloadhub.forecast.calendar.Weeks;` and, after `capacity(...)`, the two methods:

```java
    /**
     * Hours a member can work on one day (design 2026-09-10, section 5): nothing on a weekend or holiday; the
     * application's own week row spread evenly over that week's working days; else the latest base (or the
     * default) over five days minus that day's absence hours, never below zero.
     */
    public double dayCapacity(MemberRow member, LocalDate day, ForecastData data, WorkingCalendar cal) {
        if (!cal.isWorkingDay(day)) {
            return 0.0;
        }
        LocalDate monday = Weeks.mondayOf(day);
        Optional<CapacityRow> row = rowFor(member.id(), monday, data);
        if (row.isPresent()) {
            int working = cal.workingDaysInWeek(monday);
            return working == 0 ? 0.0 : round2(Math.max(0.0, row.get().available() / working));
        }
        double base = latestRowBefore(member.id(), monday, data).map(CapacityRow::base).orElse(defaultWeeklyHours);
        return round2(Math.max(0.0, base / WORKING_DAYS_PER_WEEK - dayAbsenceHours(member.id(), day, data)));
    }

    /** The member's absence hours recorded on that day. */
    public double dayAbsenceHours(UUID member, LocalDate day, ForecastData data) {
        NavigableMap<LocalDate, Double> byDay = indexFor(data).absenceHoursByMember().get(member);
        return byDay == null ? 0.0 : round2(byDay.getOrDefault(day, 0.0));
    }
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=CapacityRuleTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Failing tests for the per-day effort placement**

Add to `test/.../model/EffortModelTest.java` (imports: `com.workloadhub.forecast.calendar.Weeks`, `com.workloadhub.forecast.features.MemberDay`, `java.util.TreeMap`; the class already has `ANA`, `BEN`, `MON = 2026-08-03`, `CAL`, `history()`, `model()`):

```java
    @Test
    void dayPlacementOfOpenTasksSumsToTheWeeklyPlacementAndLandsOnWorkingDays() {
        ForecastData data = history();
        EffortModel m = EffortModel.fit(Lifecycle.derive(data), data);
        TaskRow open = TestData.task("o", ANA.id(), MON.minusDays(3).atTime(9, 0), 10).withRemaining(6.0).withDue(MON.plusDays(8));
        ForecastData withOpen = TestData.data(List.of(ANA, BEN), List.of(open), List.of(), List.of());
        Lifecycle lc = Lifecycle.derive(withOpen);
        List<TaskFacts> openFacts = List.of(lc.of(open.id()));
        SortedMap<MemberDay, Double> byDay = EffortModel.placeOpenTasksByDay(openFacts, m, MON, id -> TestData.TEAM, id -> Set.of(), CAL);
        SortedMap<MemberWeek, Double> byWeek = EffortModel.placeOpenTasks(openFacts, m, MON, id -> TestData.TEAM, id -> Set.of(), CAL);
        Map<MemberWeek, Double> summed = new TreeMap<>();
        byDay.forEach((k, h) -> summed.merge(new MemberWeek(k.member(), Weeks.mondayOf(k.day())), h, Double::sum));
        assertEquals(byWeek.keySet(), summed.keySet());
        byWeek.forEach((k, h) -> assertEquals(h, summed.get(k), 1e-9));
        assertTrue(byDay.keySet().stream().allMatch(k -> CAL.isWorkingDay(k.day()) && !k.day().isBefore(MON)));
        assertEquals(6.0, byDay.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
    }

    @Test
    void newArrivalsOnADayAreScaledAndSpreadForwardFromThatDay() {
        EffortModel m = model();
        LocalDate thursday = MON.plusDays(3);
        SortedMap<MemberDay, Double> placed = EffortModel.placeNewArrivalsByDay(Map.of(new MemberDay(BEN.id(), thursday), 10.0), m,
                id -> TestData.TEAM, id -> Set.of(), CAL);
        assertEquals(10.0 * m.estimateRatio(BEN.id(), null, TestData.TEAM), placed.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertTrue(placed.keySet().stream().allMatch(k -> !k.day().isBefore(thursday) && CAL.isWorkingDay(k.day())));
        assertEquals(thursday, placed.firstKey().day());
    }
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=EffortModelTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 8: The per-day effort placement**

In `model/EffortModel.java` add `import com.workloadhub.forecast.features.MemberDay;` and `import com.workloadhub.forecast.calendar.Weeks;`, then replace `placeOpenTasks` and `placeNewArrivals` with these four methods (the weekly forms delegate to the day forms; the range arithmetic moves into one private helper):

```java
    private record OpenSpan(double hours, LocalDate start, LocalDate end) {
    }

    private static OpenSpan openSpan(TaskFacts t, EffortModel model, LocalDate placementStart, UUID team) {
        UUID member = t.assignee();
        double hours = t.remaining() != null ? t.remaining()
                : Math.max(0.0, t.estimate() * model.estimateRatio(member, t.family(), team) - t.actualHours());
        LocalDate assigned = t.assignedDay();
        LocalDate start = assigned.isAfter(placementStart) ? assigned : placementStart;
        int cycle = (int) Math.round(model.familyCycleDays(member, t.family(), team));
        LocalDate minEnd = start.plusDays(Math.max(cycle - 1, 0));
        LocalDate end;
        if (t.task().dueDate() != null) {
            LocalDate late = t.task().dueDate().plusDays(Math.round(model.memberLatenessDays(member, team)));
            end = late.isAfter(minEnd) ? late : minEnd;
        } else {
            LocalDate byCycle = assigned.plusDays(cycle);
            end = byCycle.isAfter(minEnd) ? byCycle : minEnd;
        }
        return new OpenSpan(hours, start, end);
    }

    /** Remaining hours of each open task spread over working days from the placement start (or the assignment day) to its expected end. */
    public static SortedMap<MemberDay, Double> placeOpenTasksByDay(List<TaskFacts> open, EffortModel model, LocalDate placementStart,
            Function<UUID, UUID> teamOf, Function<UUID, Set<LocalDate>> offDaysOf, WorkingCalendar cal) {
        SortedMap<MemberDay, Double> out = new TreeMap<>();
        for (TaskFacts t : open) {
            UUID member = t.assignee();
            OpenSpan span = openSpan(t, model, placementStart, teamOf.apply(member));
            if (span.hours() <= 0) {
                continue;
            }
            HourPlacement.placeHoursByDay(span.hours(), span.start(), span.end(), cal, offDaysOf.apply(member))
                    .forEach((day, h) -> out.merge(new MemberDay(member, day), h, Double::sum));
        }
        return out;
    }

    public static SortedMap<MemberWeek, Double> placeOpenTasks(List<TaskFacts> open, EffortModel model, LocalDate placementStart,
            Function<UUID, UUID> teamOf, Function<UUID, Set<LocalDate>> offDaysOf, WorkingCalendar cal) {
        SortedMap<MemberWeek, Double> out = new TreeMap<>();
        placeOpenTasksByDay(open, model, placementStart, teamOf, offDaysOf, cal)
                .forEach((k, h) -> out.merge(new MemberWeek(k.member(), Weeks.mondayOf(k.day())), h, Double::sum));
        return out;
    }

    /** Arrival hours of one day, scaled by the member's estimate ratio and spread over the cycle days from that day on. */
    public static SortedMap<MemberDay, Double> placeNewArrivalsByDay(Map<MemberDay, Double> arrivals, EffortModel model,
            Function<UUID, UUID> teamOf, Function<UUID, Set<LocalDate>> offDaysOf, WorkingCalendar cal) {
        SortedMap<MemberDay, Double> out = new TreeMap<>();
        for (Map.Entry<MemberDay, Double> e : new TreeMap<>(arrivals).entrySet()) {
            UUID member = e.getKey().member();
            UUID team = teamOf.apply(member);
            double hours = e.getValue() * model.estimateRatio(member, null, team);
            int span = (int) Math.round(model.memberCycleDays(member, team));
            LocalDate start = e.getKey().day();
            LocalDate end = start.plusDays(Math.max(span - 1, 0));
            HourPlacement.placeHoursByDay(hours, start, end, cal, offDaysOf.apply(member))
                    .forEach((day, h) -> out.merge(new MemberDay(member, day), h, Double::sum));
        }
        return out;
    }

    public static SortedMap<MemberWeek, Double> placeNewArrivals(Map<MemberWeek, Double> predictedEst, EffortModel model,
            Function<UUID, UUID> teamOf, Function<UUID, Set<LocalDate>> offDaysOf, WorkingCalendar cal) {
        Map<MemberDay, Double> arrivals = new TreeMap<>();
        predictedEst.forEach((k, v) -> arrivals.merge(new MemberDay(k.member(), k.week()), v, Double::sum));
        SortedMap<MemberWeek, Double> out = new TreeMap<>();
        placeNewArrivalsByDay(arrivals, model, teamOf, offDaysOf, cal)
                .forEach((k, h) -> out.merge(new MemberWeek(k.member(), Weeks.mondayOf(k.day())), h, Double::sum));
        return out;
    }
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='EffortModelTest,EffortModelPropertyTest,PlannedWorkTest,ForecastRunnerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (the weekly placements are unchanged in value: same days, same order of summation).

- [ ] **Step 9: Whole gate and commit**

Run: `cd server && mvn -B -q verify`
Expected: exit 0.

```bash
git add server/forecast-core
git commit -m "feat(server): the rolling horizon and per-day placement, capacity and effort

Two windows of five weekdays from the first weekday after the run day,
hours placed per working day, capacity per day, and arrivals placed from
their day: the building blocks the run pipeline switches to next."
```

---

### Task 2: Windows and days through the pipeline, the facts, the contract, the store and the evaluation

**Files:**
- Create: `api/MemberWindowForecast.java`, `api/MemberDayForecast.java`, `api/CurrentDayForecast.java`, `resources/db/forecast/sqlite/V3__windows_and_days.sql`, `resources/db/forecast/postgresql/V3__windows_and_days.sql`
- Modify: `planned/PlannedWork.java`, `run/Prepared.java`, `run/TeamOutcome.java`, `run/ForecastRunner.java`, `api/RunResult.java`, `store/JdbcRunStore.java`, `service/DefaultForecastService.java`, `facts/FactsBuilder.java`, `ai/NarrativeContract.java`, `ai/FactsTools.java`, `ai/Prompts.java`, `resources/ai/contract.schema.json`, `eval/DemandRow.java`, `eval/Truth.java`, `eval/Harness.java`, `eval/Report.java`, `calendar/Weeks.java`, `cli/RunCommand.java`
- Remove: `api/MemberWeekForecast.java`
- Tests (modify): `calendar/WeeksTest`, `planned/PlannedWorkTest`, `model/EffortModelTest`, `run/ForecastRunnerTest`, `facts/FactsBuilderTest`, `ai/NarrativeContractTest`, `ai/NumberVerifierTest`, `ai/PromptsTest`, `ai/NarratorTest`, `ai/FactsToolsTest`, `ai/FakeGateway`, `store/JdbcRunStoreTest`, `store/ForecastMigrationsTest`, `service/DefaultForecastServiceTest`, `web/ForecastControllerTest`, `samplehost/SampleHostIntegrationTest`, `eval/HarnessTest`, `eval/ReportTest`, `eval/TruthTest`, `cli/RunCommandTest`

**Interfaces:**
- Consumes: Task 1's `Horizon`, `ForecastWindow`, `MemberDay`, `placeHoursByDay`, `dayCapacity`, `dayAbsenceHours`, `placeOpenTasksByDay`, `placeNewArrivalsByDay`.
- Produces: `MemberWindowForecast(UUID userId, int windowIndex, LocalDate windowStart, LocalDate windowEnd, double openHrs, double newHrs, double plannedHrs, double demandHrs, double lowHrs, double highHrs, double capacityHrs, double overloadHrs, int workingDays, double absenceHrs)`; `MemberDayForecast(UUID userId, LocalDate day, int windowIndex, double openHrs, double newHrs, double plannedHrs, double demandHrs, double capacityHrs, double overloadHrs, boolean workingDay)`; `CurrentDayForecast(UUID teamId, UUID userId, LocalDate day, UUID runId, double openHrs, double newHrs, double plannedHrs, double demandHrs, double capacityHrs, double overloadHrs, LocalDateTime forecastAt)`; `RunResult(RunSummary run, List<ModelScore> scores, Map<String, Double> maseByModel, Map<String, String> unavailable, List<MemberWindowForecast> memberWindows, List<MemberDayForecast> memberDays, String factsJson)`; `Prepared.windows()` (`List<ForecastWindow>`), `Prepared.horizons()`; `TeamOutcome.memberWindows()`, `TeamOutcome.memberDays()`; `JdbcRunStore.finish(UUID runId, String champion, double championMase, String backtestJson, List<MemberWindowForecast> windows, List<MemberDayForecast> days, String factsJson, LocalDateTime finishedAt)`, `memberWindows(UUID)`, `memberDays(UUID)`, `currentDays(UUID teamId, LocalDate from, LocalDate to)`; facts and contract keys of the Global Constraints. `RunRequest` keeps `asOf` until Task 3.

The order below writes the tests first where the change is a new behaviour, then the code; the build is red between Step 1 and Step 12 and green at Step 13. Run focused tests as each unit compiles.

- [ ] **Step 1: The API records**

Create `api/MemberWindowForecast.java`:

```java
package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.UUID;

/** One member in one forecast window (five weekdays): the hours, the band, the capacity and why it is what it is. */
public record MemberWindowForecast(UUID userId, int windowIndex, LocalDate windowStart, LocalDate windowEnd, double openHrs, double newHrs,
        double plannedHrs, double demandHrs, double lowHrs, double highHrs, double capacityHrs, double overloadHrs, int workingDays, double absenceHrs) {
}
```

Create `api/MemberDayForecast.java`:

```java
package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.UUID;

/** One member on one weekday of the horizon. */
public record MemberDayForecast(UUID userId, LocalDate day, int windowIndex, double openHrs, double newHrs, double plannedHrs, double demandHrs,
        double capacityHrs, double overloadHrs, boolean workingDay) {
}
```

Create `api/CurrentDayForecast.java`:

```java
package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** The team's current forecast for one member and day: the latest run whose horizon covered the day before it arrived. */
public record CurrentDayForecast(UUID teamId, UUID userId, LocalDate day, UUID runId, double openHrs, double newHrs, double plannedHrs, double demandHrs,
        double capacityHrs, double overloadHrs, LocalDateTime forecastAt) {
}
```

Replace `api/RunResult.java` with:

```java
package com.workloadhub.forecast.api;

import java.util.List;
import java.util.Map;

public record RunResult(RunSummary run, List<ModelScore> scores, Map<String, Double> maseByModel, Map<String, String> unavailable,
        List<MemberWindowForecast> memberWindows, List<MemberDayForecast> memberDays, String factsJson) {
}
```

Delete `api/MemberWeekForecast.java` (`git rm`).

- [ ] **Step 2: Planned work per day**

In `planned/PlannedWork.java`: replace `import com.workloadhub.forecast.features.MemberWeek;` with `import com.workloadhub.forecast.features.MemberDay;` and add `import com.workloadhub.forecast.calendar.ForecastWindow;`. Replace the three records with:

```java
    public record Piece(UUID taskId, String key, String title, UUID projectId, Family family, double estimate, UUID member, double share,
            LocalDate expectedDate, Integer expectedWindow, double hoursInWindow, double hoursAfterWindow) {
    }

    public record Allocation(SortedMap<MemberDay, Double> hours, List<Piece> pieces, double hoursAfterWindow, int candidateCount,
            double candidateHours) {
        public static Allocation empty() {
            return new Allocation(new TreeMap<>(), List.of(), 0.0, 0, 0.0);
        }
    }

    public record Request(UUID teamId, List<MemberRow> members, LocalDate asOf, List<ForecastWindow> windows) {
    }
```

In `allocate(...)`: replace the two lines computing `f1` and `windowEnd` with

```java
        LocalDate f1 = req.windows().get(0).start();
        LocalDate windowEnd = req.windows().get(req.windows().size() - 1).end();
```

replace `SortedMap<MemberWeek, Double> hours = new TreeMap<>();` with `SortedMap<MemberDay, Double> hours = new TreeMap<>();`, replace the placement loop and the `expectedWeek` line with

```java
                for (Map.Entry<LocalDate, Double> placed : HourPlacement.placeHoursByDay(scaled, expected, end, cal, offDaysOf.apply(member)).entrySet()) {
                    if (placed.getKey().isAfter(windowEnd)) {
                        out += placed.getValue();
                    } else {
                        in += placed.getValue();
                        hours.merge(new MemberDay(member, placed.getKey()), placed.getValue(), Double::sum);
                    }
                }
                after += out;
                Integer expectedWindow = windowOf(req.windows(), expected);
                pieces.add(new Piece(c.id(), c.task().key(), c.task().title(), c.task().projectId(), c.family(), c.estimate(), member,
                        e.getValue(), expected, expectedWindow, in, out));
```

and add the helper at the end of the class:

```java
    /** The window an expected assignment day falls in (the first window whose last day is not before it), null after the horizon. */
    static Integer windowOf(List<ForecastWindow> windows, LocalDate day) {
        for (ForecastWindow w : windows) {
            if (!day.isAfter(w.end())) {
                return w.index();
            }
        }
        return null;
    }
```

- [ ] **Step 3: Prepared, TeamOutcome and the runner**

Replace `run/Prepared.java` with:

```java
package com.workloadhub.forecast.run;

import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.ForecastWindow;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.model.EffortModel;
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
        String champion,
        double championMase,
        String forcedModel,
        Map<Integer, double[]> bandOffsets,
        Map<MemberWeek, Double> predictedEst,
        EffortModel effort,
        int historyWeeks,
        Map<String, Double> secondsByPhase) {
}
```

Replace `run/TeamOutcome.java` with:

```java
package com.workloadhub.forecast.run;

import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.MemberDay;
import com.workloadhub.forecast.planned.PlannedWork;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

/** The per-team half of a run: per-day placements, planned work, and the window and day tables. */
public record TeamOutcome(
        Prepared prepared,
        UUID teamId,
        List<MemberRow> members,
        boolean plannedWorkEnabled,
        SortedMap<MemberDay, Double> openHours,
        SortedMap<MemberDay, Double> newHours,
        PlannedWork.Allocation planned,
        List<MemberWindowForecast> memberWindows,
        List<MemberDayForecast> memberDays) {
}
```

In `run/ForecastRunner.java`: replace `import com.workloadhub.forecast.api.MemberWeekForecast;` with `import com.workloadhub.forecast.api.MemberDayForecast;` and `import com.workloadhub.forecast.api.MemberWindowForecast;`; add `import com.workloadhub.forecast.calendar.ForecastWindow;`, `import com.workloadhub.forecast.calendar.Horizon;`, `import com.workloadhub.forecast.data.Ids;`, `import com.workloadhub.forecast.features.MemberDay;`, `import java.util.Comparator;`. Change the `Band` javadoc to `The demand arithmetic of one member-window, isolated so a property test can pin it.` In `prepare(...)` replace

```java
        LocalDate[] weeks = Weeks.forecastWeeks(asOf);
        int h1 = (int) Weeks.weeksBetween(origin, weeks[0]);
        int[] horizons = {h1, h1 + 1};
```
with
```java
        List<ForecastWindow> windows = Horizon.windows(asOf);
        int[] horizons = Horizon.horizons(origin, windows);
```
replace `predicted.put(new MemberWeek(atOrigin.key(r).member(), weeks[i]), Math.max(0.0, pred[r]));` with `predicted.put(new MemberWeek(atOrigin.key(r).member(), origin.plusWeeks(horizons[i])), Math.max(0.0, pred[r]));`, and the constructor call's `weeks` argument with `windows`. Replace `forTeam` entirely with:

```java
    public TeamOutcome forTeam(Prepared p, UUID teamId, Boolean plannedWork) {
        ForecastData data = p.data();
        List<MemberRow> members = data.membersOfTeam(teamId).stream()
                .filter(m -> m.left() == null || m.left().isAfter(p.origin()))
                .toList();
        if (members.isEmpty()) {
            throw ForecastException.of("TEAM_NOT_FOUND", "team " + teamId + " has no counted member");
        }
        boolean planEnabled = plannedWork == null ? plannedWorkDefault : plannedWork;
        Map<UUID, MemberRow> byId = members.stream().collect(Collectors.toMap(MemberRow::id, m -> m));
        Function<UUID, UUID> teamOf = id -> byId.containsKey(id) ? byId.get(id).primaryTeamId() : teamId;
        Function<UUID, Set<LocalDate>> offDaysOf = id -> CapacityRule.offDays(id, data);
        List<TaskFacts> open = p.lifecycle().all().stream()
                .filter(f -> f.isAssigned() && !f.done() && byId.containsKey(f.assignee()))
                .toList();
        List<ForecastWindow> windows = p.windows();
        LocalDate first = windows.get(0).start();
        LocalDate last = windows.get(windows.size() - 1).end();
        SortedMap<MemberDay, Double> openHours = EffortModel.placeOpenTasksByDay(open, p.effort(), first, teamOf, offDaysOf, p.calendar());
        // A predicted week's fresh hours land evenly on that week's working days inside the horizon (design 2026-09-10, section 4).
        SortedMap<MemberDay, Double> arrivals = new TreeMap<>();
        p.predictedEst().forEach((k, v) -> {
            if (!byId.containsKey(k.member())) {
                return;
            }
            LocalDate monday = k.week();
            int working = p.calendar().workingDaysInWeek(monday);
            if (working == 0) {
                return;
            }
            for (LocalDate d = monday; !d.isAfter(monday.plusDays(6)); d = d.plusDays(1)) {
                if (p.calendar().isWorkingDay(d) && !d.isBefore(first) && !d.isAfter(last)) {
                    arrivals.merge(new MemberDay(k.member(), d), v / working, Double::sum);
                }
            }
        });
        SortedMap<MemberDay, Double> newHours = EffortModel.placeNewArrivalsByDay(arrivals, p.effort(), teamOf, offDaysOf, p.calendar());
        PlannedWork.Allocation planned = planEnabled
                ? PlannedWork.allocate(new PlannedWork.Request(teamId, members, p.asOf(), windows), p.lifecycle(), data, p.effort(), p.calendar(), offDaysOf)
                : PlannedWork.Allocation.empty();
        List<MemberWindowForecast> windowRows = new ArrayList<>();
        List<MemberDayForecast> dayRows = new ArrayList<>();
        for (MemberRow m : members) {
            double ratio = p.effort().estimateRatio(m.id(), null, m.primaryTeamId());
            for (ForecastWindow w : windows) {
                double openSum = 0;
                double freshSum = 0;
                double plannedSum = 0;
                double capacity = 0;
                double absence = 0;
                int workingDays = 0;
                double q10 = 0;
                double q90 = 0;
                for (LocalDate d : w.weekdays()) {
                    MemberDay key = new MemberDay(m.id(), d);
                    double o = openHours.getOrDefault(key, 0.0);
                    double n = newHours.getOrDefault(key, 0.0);
                    double pl = planned.hours().getOrDefault(key, 0.0);
                    double cap = capacityRule.dayCapacity(m, d, data, p.calendar());
                    boolean workingDay = p.calendar().isWorkingDay(d);
                    double demand = round2(round2(o) + round2(n) + round2(pl));
                    dayRows.add(new MemberDayForecast(m.id(), d, w.index(), round2(o), round2(n), round2(pl), demand, cap,
                            round2(Math.max(0.0, demand - cap)), workingDay));
                    openSum += o;
                    freshSum += n;
                    plannedSum += pl;
                    capacity += cap;
                    absence += capacityRule.dayAbsenceHours(m.id(), d, data);
                    if (workingDay) {
                        workingDays++;
                    }
                    // The band offsets of the horizon week this day belongs to, weighted by the day's share of the window.
                    double[] q = p.bandOffsets().get((int) Weeks.weeksBetween(p.origin(), d));
                    if (q != null) {
                        q10 += q[0] / w.weekdays().size();
                        q90 += q[1] / w.weekdays().size();
                    }
                }
                Band b = band(openSum, freshSum, plannedSum, q10, q90, ratio, round2(capacity));
                windowRows.add(new MemberWindowForecast(m.id(), w.index(), w.start(), w.end(), b.open(), b.fresh(), b.planned(), b.demand(), b.low(),
                        b.high(), round2(capacity), b.overload(), workingDays, round2(absence)));
            }
        }
        windowRows.sort(Comparator.comparing(MemberWindowForecast::userId, Ids.UUID_ORDER).thenComparingInt(MemberWindowForecast::windowIndex));
        dayRows.sort(Comparator.comparing(MemberDayForecast::userId, Ids.UUID_ORDER).thenComparing(MemberDayForecast::day));
        return new TeamOutcome(p, teamId, members, planEnabled, openHours, newHours, planned, List.copyOf(windowRows), List.copyOf(dayRows));
    }
```

Then remove `forecastWeeks` from `calendar/Weeks.java` (the method and its javadoc) and delete the test `forecastWeeksStartThisWeekOnAMondayElseNextWeek` from `WeeksTest`, keeping the `lastCompleteWeek` assertion in a new test:

```java
    @Test
    void lastCompleteWeekIsTheMondayBeforeTheRunDaysWeek() {
        assertEquals(LocalDate.of(2026, 8, 24), Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 6)));
        assertEquals(LocalDate.of(2026, 8, 31), Weeks.lastCompleteWeek(LocalDate.of(2026, 9, 7)));
    }
```

- [ ] **Step 4: Migration V3**

Create `resources/db/forecast/sqlite/V3__windows_and_days.sql`:

```sql
-- The horizon became two windows of five weekdays starting the first weekday after the run
-- (design 2026-09-10). No deployment stored member weeks: the table is dropped, not migrated.
DROP TABLE forecast_member_weeks;

CREATE TABLE forecast_member_windows (
  run_id          TEXT NOT NULL REFERENCES forecast_runs(id),
  user_id         TEXT NOT NULL,
  window_index    INTEGER NOT NULL,
  window_start    TEXT NOT NULL,
  window_end      TEXT NOT NULL,
  open_hrs        REAL NOT NULL,
  new_hrs         REAL NOT NULL,
  planned_hrs     REAL NOT NULL,
  demand_hrs      REAL NOT NULL,
  low_hrs         REAL NOT NULL,
  high_hrs        REAL NOT NULL,
  capacity_hrs    REAL NOT NULL,
  overload_hrs    REAL NOT NULL,
  working_days    INTEGER NOT NULL,
  absence_hrs     REAL NOT NULL,
  PRIMARY KEY (run_id, user_id, window_index)
);

CREATE TABLE forecast_member_days (
  run_id          TEXT NOT NULL REFERENCES forecast_runs(id),
  user_id         TEXT NOT NULL,
  day             TEXT NOT NULL,
  window_index    INTEGER NOT NULL,
  open_hrs        REAL NOT NULL,
  new_hrs         REAL NOT NULL,
  planned_hrs     REAL NOT NULL,
  demand_hrs      REAL NOT NULL,
  capacity_hrs    REAL NOT NULL,
  overload_hrs    REAL NOT NULL,
  working_day     INTEGER NOT NULL,
  PRIMARY KEY (run_id, user_id, day)
);

-- The current forecast: each run upserts its ten days per member, so days ahead are overwritten
-- and days that have arrived keep the last forecast made before them.
CREATE TABLE forecast_current_days (
  team_id         TEXT NOT NULL,
  user_id         TEXT NOT NULL,
  day             TEXT NOT NULL,
  run_id          TEXT NOT NULL REFERENCES forecast_runs(id),
  open_hrs        REAL NOT NULL,
  new_hrs         REAL NOT NULL,
  planned_hrs     REAL NOT NULL,
  demand_hrs      REAL NOT NULL,
  capacity_hrs    REAL NOT NULL,
  overload_hrs    REAL NOT NULL,
  forecast_at     TEXT NOT NULL,
  PRIMARY KEY (team_id, user_id, day)
);
CREATE INDEX forecast_current_days_team_idx ON forecast_current_days (team_id, day);
```

Create `resources/db/forecast/postgresql/V3__windows_and_days.sql` with the same statements and comments, with these types: `run_id uuid`, `user_id uuid`, `team_id uuid`, `window_index integer`, `window_start date`, `window_end date`, `day date`, every `_hrs` column `double precision`, `working_days integer`, `working_day boolean`, `forecast_at timestamp`.

- [ ] **Step 5: The store**

In `store/JdbcRunStore.java`: replace `import com.workloadhub.forecast.api.MemberWeekForecast;` with imports of `CurrentDayForecast`, `MemberDayForecast` and `MemberWindowForecast`. Replace `finish(...)`, `insertMemberWeeks(...)` and `memberWeeks(...)` with:

```java
    public void finish(UUID runId, String champion, double championMase, String backtestJson, List<MemberWindowForecast> windows,
            List<MemberDayForecast> days, String factsJson, LocalDateTime finishedAt) {
        tx.executeWithoutResult(status -> {
            jdbc.sql("UPDATE forecast_runs SET status = ?, champion_model = ?, champion_mase = ?, backtest_json = ?, finished_at = " + ph("timestamp")
                    + " WHERE id = " + ph("uuid"))
                    .param(RunStatus.DONE.name()).param(champion).param(Double.isNaN(championMase) ? null : championMase).param(backtestJson)
                    .param(ts(finishedAt)).param(runId.toString()).update();
            insertWindows(runId, windows);
            insertDays(runId, days);
            String teamId = jdbc.sql("SELECT team_id FROM forecast_runs WHERE id = " + ph("uuid")).param(runId.toString())
                    .query().listOfRows().get(0).get("team_id").toString();
            upsertCurrentDays(teamId, runId, days, finishedAt);
            jdbc.sql("INSERT INTO forecast_facts (run_id, facts_json, created_at) VALUES (" + ph("uuid") + ", ?, " + ph("timestamp") + ")")
                    .param(runId.toString()).param(factsJson).param(ts(finishedAt)).update();
        });
    }

    /** Batches of {@link #BATCH} rows, same typed placeholders and binding order as a single-row insert. */
    private void insertWindows(UUID runId, List<MemberWindowForecast> rows) {
        String insert = "INSERT INTO forecast_member_windows (run_id, user_id, window_index, window_start, window_end, open_hrs, new_hrs, planned_hrs,"
                + " demand_hrs, low_hrs, high_hrs, capacity_hrs, overload_hrs, working_days, absence_hrs) VALUES (" + ph("uuid") + ", " + ph("uuid")
                + ", ?, " + ph("date") + ", " + ph("date") + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        for (int start = 0; start < rows.size(); start += BATCH) {
            List<MemberWindowForecast> chunk = rows.subList(start, Math.min(start + BATCH, rows.size()));
            List<Object[]> args = new ArrayList<>(chunk.size());
            for (MemberWindowForecast r : chunk) {
                args.add(new Object[] {runId.toString(), r.userId().toString(), r.windowIndex(), r.windowStart().toString(), r.windowEnd().toString(),
                        r.openHrs(), r.newHrs(), r.plannedHrs(), r.demandHrs(), r.lowHrs(), r.highHrs(), r.capacityHrs(), r.overloadHrs(),
                        r.workingDays(), r.absenceHrs()});
            }
            jdbcTemplate.batchUpdate(insert, args);
        }
    }

    private void insertDays(UUID runId, List<MemberDayForecast> rows) {
        String insert = "INSERT INTO forecast_member_days (run_id, user_id, day, window_index, open_hrs, new_hrs, planned_hrs, demand_hrs, capacity_hrs,"
                + " overload_hrs, working_day) VALUES (" + ph("uuid") + ", " + ph("uuid") + ", " + ph("date") + ", ?, ?, ?, ?, ?, ?, ?, ?)";
        for (int start = 0; start < rows.size(); start += BATCH) {
            List<MemberDayForecast> chunk = rows.subList(start, Math.min(start + BATCH, rows.size()));
            List<Object[]> args = new ArrayList<>(chunk.size());
            for (MemberDayForecast r : chunk) {
                args.add(new Object[] {runId.toString(), r.userId().toString(), r.day().toString(), r.windowIndex(), r.openHrs(), r.newHrs(),
                        r.plannedHrs(), r.demandHrs(), r.capacityHrs(), r.overloadHrs(), dialect.bool(r.workingDay())});
            }
            jdbcTemplate.batchUpdate(insert, args);
        }
    }

    /** Every day of the run overwrites the team's current forecast for that member and day (all of them lie after the run day). */
    private void upsertCurrentDays(String teamId, UUID runId, List<MemberDayForecast> rows, LocalDateTime forecastAt) {
        String upsert = "INSERT INTO forecast_current_days (team_id, user_id, day, run_id, open_hrs, new_hrs, planned_hrs, demand_hrs, capacity_hrs,"
                + " overload_hrs, forecast_at) VALUES (" + ph("uuid") + ", " + ph("uuid") + ", " + ph("date") + ", " + ph("uuid") + ", ?, ?, ?, ?, ?, ?, "
                + ph("timestamp") + ") ON CONFLICT (team_id, user_id, day) DO UPDATE SET run_id = excluded.run_id, open_hrs = excluded.open_hrs,"
                + " new_hrs = excluded.new_hrs, planned_hrs = excluded.planned_hrs, demand_hrs = excluded.demand_hrs, capacity_hrs = excluded.capacity_hrs,"
                + " overload_hrs = excluded.overload_hrs, forecast_at = excluded.forecast_at";
        for (int start = 0; start < rows.size(); start += BATCH) {
            List<MemberDayForecast> chunk = rows.subList(start, Math.min(start + BATCH, rows.size()));
            List<Object[]> args = new ArrayList<>(chunk.size());
            for (MemberDayForecast r : chunk) {
                args.add(new Object[] {teamId, r.userId().toString(), r.day().toString(), runId.toString(), r.openHrs(), r.newHrs(), r.plannedHrs(),
                        r.demandHrs(), r.capacityHrs(), r.overloadHrs(), ts(forecastAt)});
            }
            jdbcTemplate.batchUpdate(upsert, args);
        }
    }

    public List<MemberWindowForecast> memberWindows(UUID runId) {
        List<MemberWindowForecast> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT user_id, window_index, window_start, window_end, open_hrs, new_hrs, planned_hrs, demand_hrs, low_hrs,"
                + " high_hrs, capacity_hrs, overload_hrs, working_days, absence_hrs FROM forecast_member_windows WHERE run_id = " + ph("uuid")
                + " ORDER BY user_id, window_index").param(runId.toString()).query().listOfRows()) {
            out.add(new MemberWindowForecast(UUID.fromString(str(r, "user_id")), (int) num(r, "window_index"), date(r, "window_start"), date(r, "window_end"),
                    num(r, "open_hrs"), num(r, "new_hrs"), num(r, "planned_hrs"), num(r, "demand_hrs"), num(r, "low_hrs"), num(r, "high_hrs"),
                    num(r, "capacity_hrs"), num(r, "overload_hrs"), (int) num(r, "working_days"), num(r, "absence_hrs")));
        }
        out.sort((a, b) -> a.userId().toString().equals(b.userId().toString()) ? Integer.compare(a.windowIndex(), b.windowIndex())
                : a.userId().toString().compareTo(b.userId().toString()));
        return out;
    }

    public List<MemberDayForecast> memberDays(UUID runId) {
        List<MemberDayForecast> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT user_id, day, window_index, open_hrs, new_hrs, planned_hrs, demand_hrs, capacity_hrs, overload_hrs,"
                + " working_day FROM forecast_member_days WHERE run_id = " + ph("uuid") + " ORDER BY user_id, day").param(runId.toString()).query().listOfRows()) {
            out.add(new MemberDayForecast(UUID.fromString(str(r, "user_id")), date(r, "day"), (int) num(r, "window_index"), num(r, "open_hrs"),
                    num(r, "new_hrs"), num(r, "planned_hrs"), num(r, "demand_hrs"), num(r, "capacity_hrs"), num(r, "overload_hrs"),
                    dialect.asBoolean(r.get("working_day"))));
        }
        out.sort((a, b) -> a.userId().toString().equals(b.userId().toString()) ? a.day().compareTo(b.day())
                : a.userId().toString().compareTo(b.userId().toString()));
        return out;
    }

    /** The team's current forecast between two days inclusive, by member then day. */
    public List<CurrentDayForecast> currentDays(UUID teamId, LocalDate from, LocalDate to) {
        List<CurrentDayForecast> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT team_id, user_id, day, run_id, open_hrs, new_hrs, planned_hrs, demand_hrs, capacity_hrs, overload_hrs,"
                + " forecast_at FROM forecast_current_days WHERE team_id = " + ph("uuid") + " AND day >= " + ph("date") + " AND day <= " + ph("date")
                + " ORDER BY user_id, day").param(teamId.toString()).param(from.toString()).param(to.toString()).query().listOfRows()) {
            out.add(new CurrentDayForecast(UUID.fromString(str(r, "team_id")), UUID.fromString(str(r, "user_id")), date(r, "day"),
                    UUID.fromString(str(r, "run_id")), num(r, "open_hrs"), num(r, "new_hrs"), num(r, "planned_hrs"), num(r, "demand_hrs"),
                    num(r, "capacity_hrs"), num(r, "overload_hrs"), dateTime(r, "forecast_at")));
        }
        out.sort((a, b) -> a.userId().toString().equals(b.userId().toString()) ? a.day().compareTo(b.day())
                : a.userId().toString().compareTo(b.userId().toString()));
        return out;
    }
```

`dialect.bool(boolean)` is the existing `Dialect` method that returns `1`/`0` on SQLite and a `Boolean` on PostgreSQL, and `dialect.asBoolean(Object)` its existing reader.

- [ ] **Step 6: The service**

In `service/DefaultForecastService.java`: in `execute(...)` replace `outcome.memberWeeks()` with `outcome.memberWindows(), outcome.memberDays()` in the `store.finish(...)` call; in `getRun(...)` replace the return with

```java
        return new RunResult(run, scores, mase, unavailable, store.memberWindows(runId), store.memberDays(runId), store.facts(runId).orElse("{}"));
```

- [ ] **Step 7: The facts**

In `facts/FactsBuilder.java`: replace `import com.workloadhub.forecast.api.MemberWeekForecast;` with imports of `MemberDayForecast`, `MemberWindowForecast`, `com.workloadhub.forecast.calendar.ForecastWindow` and `com.workloadhub.forecast.calendar.Horizon`; add `import java.util.TreeSet;`. Replace `LIMITATIONS` with

```java
    public static final String LIMITATIONS = "predicted arrivals are spread evenly over a week's working days and the days already past are not"
            + " re-forecast; open tasks are placed from the first forecast day; backlog work created and assigned inside the horizon is missed by both components";
```

In `build(...)`: delete the `f1`/`f2` lines; type `rowsByMember` as `Map<UUID, List<MemberWindowForecast>>` filled from `out.memberWindows()`, and add right after it

```java
        Map<UUID, List<MemberDayForecast>> daysByMember = new TreeMap<>((a, b) -> a.toString().compareTo(b.toString()));
        for (MemberDayForecast r : out.memberDays()) {
            daysByMember.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);
        }
```

replace the `facts.put("run", ...)` statement with

```java
        List<Object> windows = new ArrayList<>();
        for (ForecastWindow w : p.windows()) {
            int working = 0;
            for (LocalDate d : w.weekdays()) {
                if (p.calendar().isWorkingDay(d)) {
                    working++;
                }
            }
            windows.add(map("index", w.index(), "start", str(w.start()), "end", str(w.end()), "working_days", working));
        }
        List<Object> horizonList = new ArrayList<>();
        for (int h : p.horizons()) {
            horizonList.add(h);
        }
        facts.put("run", map("id", str(runId), "as_of", str(p.asOf()), "windows", windows, "generated_at", generatedAt.toString(),
                "origin", str(p.origin()), "horizons", horizonList));
        facts.put("team", team(out, data, projects));
```

and pass `daysByMember.getOrDefault(m.id(), List.of())` to `member(...)` as a new argument after `rows`. Replace `team(...)`'s signature with `team(TeamOutcome out, ForecastData data, Map<UUID, ProjectRow> projects)` and its totals and team-capacity loops with

```java
        List<Object> totals = new ArrayList<>();
        for (ForecastWindow w : out.prepared().windows()) {
            double demand = 0;
            double capacity = 0;
            double planned = 0;
            for (MemberWindowForecast r : out.memberWindows()) {
                if (r.windowIndex() == w.index()) {
                    demand += r.demandHrs();
                    capacity += r.capacityHrs();
                    planned += r.plannedHrs();
                }
            }
            totals.add(map("window", w.index(), "start", str(w.start()), "end", str(w.end()), "demand", round1(demand), "capacity", round1(capacity),
                    "planned", round1(planned)));
        }
        Set<LocalDate> horizonWeeks = new TreeSet<>();
        for (LocalDate d : Horizon.days(out.prepared().windows())) {
            horizonWeeks.add(Weeks.mondayOf(d));
        }
        List<Object> teamCapacity = new ArrayList<>();
        for (TeamCapacityRow r : data.teamCapacity()) {
            if (r.teamId().equals(out.teamId()) && horizonWeeks.contains(r.weekStart())) {
                teamCapacity.add(map("week", str(r.weekStart()), "total", round2(r.totalCapacity()), "allocated", round2(r.allocated())));
            }
        }
```

In `member(...)` (signature gains `List<MemberDayForecast> days` after `rows`), replace the `forecast` loop with

```java
        List<Object> forecast = new ArrayList<>();
        for (MemberWindowForecast r : rows) {
            double due = 0;
            for (TaskFacts f : open) {
                LocalDate d = f.task().dueDate();
                if (d != null && !d.isBefore(r.windowStart()) && !d.isAfter(r.windowEnd())) {
                    due += f.remaining() != null ? f.remaining() : f.estimate();
                }
            }
            forecast.add(map("window", r.windowIndex(), "start", str(r.windowStart()), "end", str(r.windowEnd()), "demand", r.demandHrs(), "low", r.lowHrs(),
                    "high", r.highHrs(), "capacity", r.capacityHrs(), "overload", r.overloadHrs(), "open_hours", r.openHrs(), "new_hours", r.newHrs(),
                    "planned_hours", r.plannedHrs(), "working_days", r.workingDays(), "absence_hours", r.absenceHrs(), "due_hours", round2(due)));
        }
        List<Object> dayList = new ArrayList<>();
        for (MemberDayForecast d : days) {
            dayList.add(map("day", str(d.day()), "window", d.windowIndex(), "demand", d.demandHrs(), "capacity", d.capacityHrs(), "overload", d.overloadHrs(),
                    "open_hours", d.openHrs(), "new_hours", d.newHrs(), "planned_hours", d.plannedHrs(), "working_day", d.workingDay()));
        }
```

put `"days", dayList` right after `"forecast", forecast` in the member map, and replace `"expected_week", piece.expectedWeek() == null ? "after_window" : str(piece.expectedWeek())` with `"expected_window", piece.expectedWindow() == null ? "after_window" : piece.expectedWindow()`. In `model(...)` replace `"horizons", List.of(p.horizons()[0], p.horizons()[1])` with `"horizons", java.util.Arrays.stream(p.horizons()).boxed().toList()`. In `rebalancing(...)` the map and loop types become `MemberWindowForecast` (the arithmetic is unchanged).

- [ ] **Step 8: The contract, the tools, the prompt and the fake gateway**

`ai/NarrativeContract.java`: in `FIELDS`, `"week"` becomes `"window"` in both `move` and `adjustment`; the records become `Move(String fromMemberId, String toMemberId, LocalDate window, ...)` and `Adjustment(String memberId, LocalDate window, ...)`; the two `w.date(..., "week")` parses read `"window"`; in `validateAgainstFacts` the set is built as

```java
        Set<LocalDate> windows = new HashSet<>();
        for (JsonNode w : facts.path("run").path("windows")) {
            windows.add(LocalDate.parse(w.path("start").asText().substring(0, 10)));
        }
```

every `weeks.contains(...)`/`mv.week()`/`a.week()` uses `windows`/`mv.window()`/`a.window()`; the messages become `rebalancing window <date> is not a forecast window`, `adjustment window <date> is not a forecast window`, and `in the week of ` becomes `in the window starting `; `forecastRow(member, window)` matches `row.path("start")`.

`resources/ai/contract.schema.json`: in `move`, `required` lists `"window"` instead of `"week"` and the property is `"window": {"type": "string", "format": "date", "description": "A forecast window's first day, ISO 8601"}`; in `adjustment`, `required` and the property likewise (`"window": {"type": "string", "format": "date"}`).

`ai/FactsTools.java`: `memberCapacity` returns `map("member_id", id, "name", ..., "windows", windows, "days", plain(m.path("days")))` where each entry of `windows` is `map("window", plain(row.path("window")), "start", row.path("start").asText(), "end", row.path("end").asText(), "capacity", ..., "demand", ..., "overload", ..., "working_days", ..., "absence_hours", ...)`; `projectTimelines` returns `map("windows", plain(facts.path("run").path("windows")), "projects", ...)`. (Tool descriptions change in Task 4.)

`ai/Prompts.java`: in `userPrompt`, replace the `weeks` joiner with

```java
        StringJoiner windows = new StringJoiner(" and ");
        for (JsonNode w : run.path("windows")) {
            windows.add(w.path("start").asText() + ".." + w.path("end").asText());
        }
```

and `", forecast weeks " + weeks` with `", forecast windows " + windows`.

Test double `test/.../ai/FakeGateway.java`, `goodNarrative`: `" h capacity in the week of "` becomes `" h capacity in the window starting "` and `row.path("week")` becomes `row.path("start")`.

- [ ] **Step 9: The evaluation harness**

Replace `eval/DemandRow.java` with:

```java
package com.workloadhub.forecast.eval;

import java.time.LocalDate;
import java.util.UUID;

/** One member-window demand forecast replayed from one origin, against what was actually logged on the window's days. */
public record DemandRow(String model, LocalDate origin, UUID teamId, UUID memberId, int windowIndex, LocalDate windowStart, LocalDate windowEnd,
        double forecast, double truth, double capacity, double openHours, double newHours, double plannedHours) {
}
```

In `eval/Truth.java` add (import `com.workloadhub.forecast.features.MemberDay`):

```java
    /** The hours people logged, per member and day. */
    public static SortedMap<MemberDay, Double> realisedHoursByDay(ForecastData data) {
        SortedMap<MemberDay, Double> out = new TreeMap<>();
        for (TimeLogRow l : data.timeLogs()) {
            out.merge(new MemberDay(l.userId(), l.day()), l.hours(), Double::sum);
        }
        out.replaceAll((k, v) -> Math.round(v * 1e6) / 1e6);
        return out;
    }
```

In `eval/Harness.java`'s `demandLevel`: `truth` becomes `SortedMap<MemberDay, Double> truth = Truth.realisedHoursByDay(data);` and the row loop becomes

```java
                    for (MemberWindowForecast w : outcome.memberWindows()) {
                        ForecastWindow window = prepared.windows().get(w.windowIndex() - 1);
                        double realised = 0;
                        for (LocalDate d : window.weekdays()) {
                            realised += truth.getOrDefault(new MemberDay(w.userId(), d), 0.0);
                        }
                        rows.add(new DemandRow(model, origin, team, w.userId(), w.windowIndex(), w.windowStart(), w.windowEnd(), w.demandHrs(),
                                Math.round(realised * 1e6) / 1e6, w.capacityHrs(), w.openHrs(), w.newHrs(), w.plannedHrs()));
                    }
```

(imports: `MemberWindowForecast`, `ForecastWindow`, `MemberDay`; drop `MemberWeek` and `MemberWeekForecast` if unused). `HORIZONS = {1, 2}` stays.

In `eval/Report.java`: the header becomes `"model,origin,team_id,member_id,window,window_start,window_end,forecast,truth,capacity,open_hours,new_hours,planned_hours\n"` and each row appends `r.windowIndex()`, `r.windowStart()`, `r.windowEnd()` in place of `r.weekStart()`; in `summary(...)`, where Level B is introduced, say `per member-window` where it said `per member-week` (if it does).

- [ ] **Step 10: The CLI run table**

In `cli/RunCommand.java`: import `MemberWindowForecast` instead of `MemberWeekForecast`; the summary line becomes

```java
        long members = r.memberWindows().stream().map(MemberWindowForecast::userId).distinct().count();
        StringJoiner windows = new StringJoiner(", ");
        r.memberWindows().stream().filter(w -> !r.memberWindows().isEmpty() && w.userId().equals(r.memberWindows().get(0).userId()))
                .forEach(w -> windows.add("window " + w.windowIndex() + " " + w.windowStart() + ".." + w.windowEnd()));
        System.out.printf("Run %s: champion %s (MASE %s), %d members, %s%n", r.run().id(), r.run().championModel(), mase, members,
                windows.length() == 0 ? "no windows" : windows);
```

(import `java.util.StringJoiner`) and the table becomes

```java
        System.out.printf("%-28s %-6s %-10s %-10s %7s %7s %7s %7s %7s %7s %8s %8s%n", "member", "window", "start", "end", "open", "new", "planned", "demand",
                "low", "high", "capacity", "overload");
        for (MemberWindowForecast w : r.memberWindows()) {
            System.out.printf("%-28s %-6d %-10s %-10s %7.1f %7.1f %7.1f %7.1f %7.1f %7.1f %8.1f %8.1f%n", names.getOrDefault(w.userId(), w.userId().toString()),
                    w.windowIndex(), w.windowStart(), w.windowEnd(), w.openHrs(), w.newHrs(), w.plannedHrs(), w.demandHrs(), w.lowHrs(), w.highHrs(),
                    w.capacityHrs(), w.overloadHrs());
        }
```

The `@Command` description becomes `Run a forecast for one team and print the champion, the scores and the member-window table.`

- [ ] **Step 11: The tests**

Apply these changes; every other test is untouched.

`planned/PlannedWorkTest`: add `import com.workloadhub.forecast.calendar.ForecastWindow;`, `import com.workloadhub.forecast.calendar.Horizon;`; replace the constant `F1` with

```java
    static final List<ForecastWindow> WINDOWS = Horizon.windows(AS_OF);          // Thu 2026-09-03 .. Wed 09-09, Thu 09-10 .. Wed 09-16
    static final LocalDate F1 = WINDOWS.get(0).start();
    static final LocalDate WINDOW_END = WINDOWS.get(1).end();
```

every `new LocalDate[] {F1, F1.plusWeeks(1)}` becomes `WINDOWS`; `assertEquals(F1, p.expectedWeek())` becomes `assertEquals(1, p.expectedWindow())` (with message `"created + 3 days is in the past: expected in window 1"`); the message `"expected dates are floored at the first forecast week"` says `day`; `a.hours().keySet().stream().allMatch(k -> k.week().equals(F1) || k.week().equals(F1.plusWeeks(1)))` becomes `a.hours().keySet().stream().allMatch(k -> !k.day().isBefore(F1) && !k.day().isAfter(WINDOW_END) && CAL.isWorkingDay(k.day()))`; `a.hours().containsKey(new MemberWeek(ANA.id(), F1))` becomes `a.hours().keySet().stream().anyMatch(k -> k.member().equals(ANA.id()))`; `anaOff = Set.copyOf(CAL.workingDays(F1, F1.plusWeeks(1).plusDays(6), Set.of()))` becomes `Set.copyOf(CAL.workingDays(F1, WINDOW_END, Set.of()))`; in the seeded test `LocalDate[] weeks = ...forecastWeeks(asOf)` becomes `List<ForecastWindow> windows = Horizon.windows(asOf)` and the request passes `windows`. Remove the `MemberWeek` import if unused.

`model/EffortModelTest` line 127: `LocalDate f1 = com.workloadhub.forecast.calendar.Horizon.windows(SeededData.asOf()).get(0).start();`.

`run/ForecastRunnerTest`: import `Horizon`, `ForecastWindow`, `MemberDayForecast`, `MemberWindowForecast` (drop `MemberWeekForecast`), `assertArrayEquals`; replace `timeFrameFollowsTheAsOfDate` with

```java
    @Test
    void timeFrameFollowsTheRunDay() {
        LocalDate asOf = SeededData.asOf();                                   // Sunday 2026-09-06
        assertEquals(Weeks.lastCompleteWeek(asOf), prepared.origin());
        assertEquals(Horizon.windows(asOf), prepared.windows());
        assertEquals(LocalDate.of(2026, 9, 7), prepared.windows().get(0).start());
        assertEquals(LocalDate.of(2026, 9, 18), prepared.windows().get(1).end());
        assertArrayEquals(new int[] {2, 3}, prepared.horizons(), "a weekend run: the week just ended is complete, the windows touch the next two");
        assertTrue(prepared.historyWeeks() >= 25 && prepared.historyWeeks() <= 30, "30 seeded weeks: " + prepared.historyWeeks());
        assertFalse(prepared.backtestOrigins().isEmpty());
    }

    @Test
    void aMidweekRunTouchesThreeHorizonWeeks() {
        LocalDate wednesday = LocalDate.of(2026, 9, 2);
        Prepared p = runner.prepare(data, wednesday, Backtest.FLOOR, ForecastRunner.ProgressListener.NONE);
        assertArrayEquals(new int[] {1, 2, 3}, p.horizons());
        assertEquals(LocalDate.of(2026, 9, 3), p.windows().get(0).start());
        assertEquals(LocalDate.of(2026, 9, 16), p.windows().get(1).end());
        for (int h : p.horizons()) {
            assertNotNull(p.bandOffsets().get(h));
        }
        TeamOutcome out = runner.forTeam(p, team, null);
        assertEquals(out.members().size() * 10, out.memberDays().size());
    }
```

in `championIsScoredAndPredictionsExistForEveryMemberWithAnOriginRow` replace `originRows * 2` with `originRows * prepared.horizons().length` (message `"one prediction per horizon per member with an origin row"`); replace `teamOutcomeHoldsTheInvariantsForEveryMemberAndWeek` with

```java
    @Test
    void teamOutcomeHoldsTheInvariantsForEveryMemberWindowAndDay() {
        TeamOutcome out = runner.forTeam(prepared, team, null);
        List<MemberWindowForecast> rows = out.memberWindows();
        assertEquals(out.members().size() * 2, rows.size());
        assertEquals(out.members().size() * 10, out.memberDays().size());
        for (MemberWindowForecast r : rows) {
            assertEquals(r.demandHrs(), ForecastRunner.round2(r.openHrs() + r.newHrs() + r.plannedHrs()), 1e-9);
            assertEquals(r.overloadHrs(), ForecastRunner.round2(Math.max(0, r.demandHrs() - r.capacityHrs())), 1e-9);
            assertTrue(r.lowHrs() <= r.demandHrs() + 1e-9 && r.demandHrs() <= r.highHrs() + 1e-9);
            assertTrue(r.lowHrs() >= r.openHrs() + r.plannedHrs() - 1e-9, "the band never cuts into placed or planned work");
            assertTrue(r.capacityHrs() >= 0 && r.workingDays() >= 0 && r.workingDays() <= 5);
            ForecastWindow w = prepared.windows().get(r.windowIndex() - 1);
            assertEquals(w.start(), r.windowStart());
            assertEquals(w.end(), r.windowEnd());
            List<MemberDayForecast> days = out.memberDays().stream().filter(d -> d.userId().equals(r.userId()) && d.windowIndex() == r.windowIndex()).toList();
            assertEquals(5, days.size());
            assertEquals(w.weekdays(), days.stream().map(MemberDayForecast::day).toList());
            assertEquals(r.openHrs(), days.stream().mapToDouble(MemberDayForecast::openHrs).sum(), 0.05, "a window's open hours are the sum of its days");
            assertEquals(r.newHrs(), days.stream().mapToDouble(MemberDayForecast::newHrs).sum(), 0.05);
            assertEquals(r.plannedHrs(), days.stream().mapToDouble(MemberDayForecast::plannedHrs).sum(), 0.05);
            assertEquals(r.capacityHrs(), days.stream().mapToDouble(MemberDayForecast::capacityHrs).sum(), 0.05);
            assertEquals(r.workingDays(), days.stream().filter(MemberDayForecast::workingDay).count());
            for (MemberDayForecast d : days) {
                assertEquals(d.demandHrs(), ForecastRunner.round2(d.openHrs() + d.newHrs() + d.plannedHrs()), 1e-9);
                assertEquals(d.overloadHrs(), ForecastRunner.round2(Math.max(0, d.demandHrs() - d.capacityHrs())), 1e-9);
                assertTrue(d.workingDay() || d.capacityHrs() == 0.0, "no capacity on a holiday");
            }
        }
        assertTrue(out.plannedWorkEnabled());
        TeamOutcome noPlanned = runner.forTeam(prepared, team, false);
        assertTrue(noPlanned.memberWindows().stream().allMatch(r -> r.plannedHrs() == 0.0));
        assertEquals(0, noPlanned.planned().pieces().size());
    }
```

in `shortHistoryStillCompletesWithTheFloor` and `twoRunsOnTheSameDataAreIdentical` replace `memberWeeks()` with `memberWindows()` and the row type with `MemberWindowForecast`.

`facts/FactsBuilderTest`: in `topLevelShapeMatchesThePythonFactsPlusTheAdditions` replace the `weeks` assertion with

```java
        List<?> windows = (List<?>) run.get("windows");
        assertEquals(2, windows.size());
        assertEquals(Map.of("index", 1, "start", "2026-09-07", "end", "2026-09-11", "working_days", ((Map<?, ?>) windows.get(0)).get("working_days")), windows.get(0));
        assertEquals("2026-09-14", ((Map<?, ?>) windows.get(1)).get("start"));
        assertEquals(List.of(2, 3), run.get("horizons"));
        assertEquals(1, ((Map<?, ?>) ((List<?>) team.get("totals")).get(0)).get("window"));
```

(keep `assertEquals(2, ((List<?>) team.get("totals")).size())`); in `everyMemberCarriesHistoryForecastPatternsAndLikelyWork` rename the local `week` to `window`, replace `outcome.memberWeeks().get(0).demandHrs()` with `outcome.memberWindows().get(0).demandHrs()`, add

```java
        assertEquals(1, window.get("window"));
        assertEquals("2026-09-07", window.get("start"));
        List<?> days = (List<?>) first.get("days");
        assertEquals(10, days.size());
        Map<?, ?> day = (Map<?, ?>) days.get(0);
        assertEquals("2026-09-07", day.get("day"));
        assertTrue(day.containsKey("demand") && day.containsKey("capacity") && day.containsKey("working_day"));
        for (Object p : (List<?>) likely.get("planned")) {
            Object expected = ((Map<?, ?>) p).get("expected_window");
            assertTrue(expected.equals(1) || expected.equals(2) || expected.equals("after_window"), String.valueOf(expected));
        }
```

after the `likely` keys assertion (the `likely` variable exists there).

`ai/NarrativeContractTest`: the `FACTS` fixture's run becomes `{"id": "r", "as_of": "2026-09-03", "windows": [{"index": 1, "start": "2026-09-07", "end": "2026-09-11"}, {"index": 2, "start": "2026-09-14", "end": "2026-09-18"}]}` and every forecast row `{"week": "2026-09-07", ...}` becomes `{"window": 1, "start": "2026-09-07", ...}` (and `2` / `"2026-09-14"` for the second); in `good()` the move's `"week": "2026-09-07"` becomes `"window": "2026-09-07"`, the summary and warning say `in the window starting 2026-09-07`; line 65 asserts `.window()`; line 96/97 put `"window"` and expect the prefix `rebalancing[0].window`; line 119 puts `"window"`; line 157 puts `"window"`. Any assertion on a message containing `week` says `window`.

`ai/NumberVerifierTest`: the fixture's run becomes `{"id": "r", "windows": [{"index": 1, "start": "2026-09-07", "end": "2026-09-11"}], "generated_at": "2026-09-03T10:00:00", "horizons": [1, 2]}`, `totals` entries `{"window": 1, "start": "2026-09-07", "demand": 72.5, "capacity": 88.0}`, forecast rows `{"window": 1, "start": "2026-09-07", ...}`; the move and adjustment JSON use `"window": "2026-09-07"`. Text assertions that speak of weeks stay as they are (the verifier does not read dates).

`ai/PromptsTest`: the fixture's run becomes `{"id": "...", "as_of": "2026-09-06", "windows": [{"index": 1, "start": "2026-09-07", "end": "2026-09-11"}, {"index": 2, "start": "2026-09-14", "end": "2026-09-18"}]}`; the assertion checks `en.contains("forecast windows 2026-09-07..2026-09-11 and 2026-09-14..2026-09-18")`.

`ai/NarratorTest` line 361: `((ObjectNode) broken.path("run")).set("windows", ExportFiles.mapper().createArrayNode().add(ExportFiles.mapper().createObjectNode().put("start", "2026-09")));`.

`ai/FactsToolsTest`: `assertEquals(Set.of("member_id", "name", "windows", "days"), capacity.keySet());` then `windows` typed from `capacity.get("windows")`, size 2, keys `Set.of("window", "start", "end", "capacity", "demand", "overload", "working_days", "absence_hours")`; `assertEquals(Set.of("windows", "projects"), TOOLS.projectTimelines().keySet());`; line 67 compares `path("start")` with `rows.get(0).get("start")`.

`store/JdbcRunStoreTest`: replace `rows()`, the two `finish(...)` calls, `manyRows` and the assertions with windows and days:

```java
    static List<MemberWindowForecast> windows() {
        return List.of(
                new MemberWindowForecast(USER, 1, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11), 10, 5.5, 2, 17.5, 15, 20, 40, 0, 5, 0),
                new MemberWindowForecast(USER, 2, LocalDate.of(2026, 9, 14), LocalDate.of(2026, 9, 18), 4, 6, 0, 10, 10, 12, 32, 0, 4, 8));
    }

    /** Ten days of one member for a run made on {@code asOf}, every day carrying {@code demand} so a later run's values are recognisable. */
    static List<MemberDayForecast> days(LocalDate asOf, double demand) {
        List<MemberDayForecast> out = new ArrayList<>();
        for (ForecastWindow w : Horizon.windows(asOf)) {
            for (LocalDate d : w.weekdays()) {
                out.add(new MemberDayForecast(USER, d, w.index(), demand, 0, 0, demand, 8, Math.max(0, demand - 8), true));
            }
        }
        return out;
    }
```

`store.finish(id, "xgboost", 0.83, "{\"scores\":[]}", windows(), days(LocalDate.of(2026, 9, 6), 3), "{\"run\":{}}", T0.plusMinutes(1));` then `assertEquals(windows(), store.memberWindows(id));`, `assertEquals(days(LocalDate.of(2026, 9, 6), 3), store.memberDays(id));`, and `assertEquals(10, store.currentDays(TEAM, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).size());`. The NaN run passes `List.of(), List.of()`. `manyRows(450)` becomes 450 windows of 450 distinct users (`UUID.nameUUIDFromBytes(("user-" + i).getBytes(StandardCharsets.UTF_8))`, window 1, 2026-09-07..09-11), sorted by `userId().toString()` before the equality assertion, with `List.of()` days. Add the overwrite test, run on both dialects like `lifecycle`:

```java
    /** Spec 2026-09-10, section 7: a later run overwrites the days ahead of it and leaves the days only the earlier run covered. */
    void aLaterRunOverwritesOnlyTheDaysItCovers(DataSource ds) {
        JdbcRunStore store = new JdbcRunStore(ds, Dialect.of(ds));
        LocalDate wednesday = LocalDate.of(2026, 9, 2);
        LocalDate monday = LocalDate.of(2026, 9, 7);
        UUID first = store.create(new RunRequest(TEAM, USER, wednesday, null, null), T0);
        store.finish(first, "xgboost", 0.8, "{}", List.of(), days(wednesday, 1), "{}", T0.plusMinutes(1));
        UUID second = store.create(new RunRequest(TEAM, USER, monday, null, null), T0.plusDays(5));
        store.finish(second, "xgboost", 0.8, "{}", List.of(), days(monday, 2), "{}", T0.plusDays(5).plusMinutes(1));
        List<CurrentDayForecast> current = store.currentDays(TEAM, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        assertEquals(13, current.size(), "three days only the first run covered, ten the second overwrote");
        for (CurrentDayForecast c : current) {
            boolean beforeSecond = c.day().isBefore(LocalDate.of(2026, 9, 8));
            assertEquals(beforeSecond ? first : second, c.runId(), c.day().toString());
            assertEquals(beforeSecond ? 1.0 : 2.0, c.demandHrs(), 1e-9, c.day().toString());
            assertEquals(beforeSecond ? T0.plusMinutes(1) : T0.plusDays(5).plusMinutes(1), c.forecastAt());
        }
        assertEquals(LocalDate.of(2026, 9, 3), current.get(0).day());
        assertEquals(LocalDate.of(2026, 9, 21), current.get(current.size() - 1).day());
        assertTrue(store.currentDays(UUID.randomUUID(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).isEmpty());
        assertEquals(2, store.currentDays(TEAM, LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 4)).size(), "the range is inclusive");
    }
```

with `@Test void sqliteOverwrite() { aLaterRunOverwritesOnlyTheDaysItCovers(sqlite()); }` and a `postgresOverwrite` twin built like `postgresLifecycle`.

`store/ForecastMigrationsTest`: the expected table set becomes `forecast_current_days, forecast_facts, forecast_member_days, forecast_member_windows, forecast_narratives, forecast_runs, forecast_schema_history`; `check` also asserts `hasColumn(ds, "forecast_member_windows", "demand_hrs")` and `hasColumn(ds, "forecast_current_days", "forecast_at")`; `checkStepwise` runs `ForecastMigrations.run(ds, "2")` after the `"1"` step and asserts `tables(ds).contains("forecast_member_weeks")` there, then after the full run asserts `!tables(ds).contains("forecast_member_weeks")` and `tables(ds).contains("forecast_current_days")`; rename the stepwise tests `v3AppliesOnADatabaseThatRanV1AndV2Sqlite`/`...Postgresql`.

`service/DefaultForecastServiceTest`: `memberWeeks()` → `memberWindows()` (three places); add after the first successful run: `assertEquals(r.memberWindows().size() * 5, r.memberDays().size());`.

`web/ForecastControllerTest`: `new RunResult(summary(), List.of(), Map.of(), Map.of(), List.of(), List.of(), "{}")`.

`samplehost/SampleHostIntegrationTest`: `result.path("memberWindows").size() > 0` and add `assertTrue(result.path("memberDays").size() > 0);`.

`eval/HarnessTest` line 62: `assertTrue(r.windowStart().equals(r.origin().plusWeeks(1).plusDays(1)) || r.windowStart().equals(r.origin().plusWeeks(2).plusDays(1)), "the replay runs on the Monday after the origin, so its windows start on Tuesdays");` and add `assertTrue(r.windowIndex() == 1 || r.windowIndex() == 2);`.

`eval/ReportTest`: the two rows become `new DemandRow("xgboost", origin, team, member, 1, origin.plusWeeks(1).plusDays(1), origin.plusWeeks(2), 30, 28, 40, 20, 8, 2)` and `new DemandRow("xgboost", origin, team, member, 2, origin.plusWeeks(2).plusDays(1), origin.plusWeeks(3), 45, 30, 40, 40, 5, 0)`; the header assertion uses the new columns.

`eval/TruthTest`: add

```java
    @Test
    void sumsLogsPerUserAndDay() {
        MemberRow ana = TestData.member("ana", TestData.TEAM);
        TaskRow t = TestData.task("1", ana.id(), LocalDate.of(2026, 8, 3).atTime(9, 0), 8);
        ForecastData data = TestData.data(List.of(ana), List.of(t), List.of(), List.of(
                TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 5), 3), TestData.log(t.id(), ana.id(), LocalDate.of(2026, 8, 5), 1.5)));
        assertEquals(4.5, Truth.realisedHoursByDay(data).get(new MemberDay(ana.id(), LocalDate.of(2026, 8, 5))), 1e-9);
        assertEquals(1, Truth.realisedHoursByDay(data).size());
    }
```

`cli/RunCommandTest`: `json.contains("\"memberWindows\"")` and add `assertTrue(json.contains("\"memberDays\""), json);`; `run.contains("window 1")` may be added to the text assertions.

- [ ] **Step 12: Compile and run the focused suites**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='HorizonTest,HourPlacementTest,PlannedWorkTest,EffortModelTest,ForecastRunnerTest,FactsBuilderTest,NarrativeContractTest,NumberVerifierTest,PromptsTest,NarratorTest,FactsToolsTest,ContractSchemaTest,JdbcRunStoreTest,ForecastMigrationsTest,DefaultForecastServiceTest,ForecastControllerTest,HarnessTest,ReportTest,TruthTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. Fix compilation errors in the files this task lists before looking anywhere else.

- [ ] **Step 13: Whole gate and commit**

Run: `cd server && mvn -B -q verify`
Expected: exit 0 (the CLI module's `RunCommandTest` included).

```bash
git add server
git commit -m "feat(server): forecast two windows of five weekdays from the day after the run, per day

The run pipeline places open, predicted and planned hours per working
day, sums them into two windows starting the first weekday after the run
day, computes capacity per day, and stores windows, days and a per-day
current forecast (V3). Facts, contract, tools, prompt and the evaluation
harness speak of windows; the arrival level of the evaluation is unchanged."
```

---

### Task 3: The run day from the clock, the REST body, the current forecast, the CLI

**Files:**
- Create: `web/RunRequestBody.java`, `cli/CurrentCommand.java`
- Modify: `api/RunRequest.java`, `api/ForecastService.java`, `service/DefaultForecastService.java`, `ForecastAutoConfiguration.java`, `web/ForecastController.java`, `web/ForecastWebConfiguration.java`, `store/JdbcRunStore.java`, `cli/RunCommand.java`, `cli/ForecastCli.java`, `cli/Services.java`
- Tests (modify): `api/RunRequestTest`, `service/DefaultForecastServiceTest`, `store/JdbcRunStoreTest`, `web/ForecastControllerTest`, `samplehost/SampleHostApplication`, `samplehost/SampleHostIntegrationTest`, `cli/RunCommandTest`

**Interfaces:**
- Consumes: Task 2's records and `JdbcRunStore.currentDays`.
- Produces: `RunRequest(UUID teamId, UUID requestedBy, String forcedModel, Boolean plannedWork)`; `ForecastService.currentForecast(UUID teamId, LocalDate from, LocalDate to)`; `DefaultForecastService(..., CopilotGateway gateway, Clock clock)` (the clock is the last constructor argument), `runNow(RunRequest)`, `runNow(RunRequest, LocalDate asOf)`; `JdbcRunStore.create(RunRequest request, LocalDate asOf, LocalDateTime createdAt)`; REST `POST /runs` with `RunRequestBody(teamId, requestedBy, asOf, forcedModel, plannedWork)` and `GET /teams/{teamId}/current?from&to`; CLI `current`.

- [ ] **Step 1: Failing tests**

`api/RunRequestTest`: drop `AS_OF`; the constructor calls become `new RunRequest(TEAM, null, " ", null)`, `new RunRequest(TEAM, null, "xgboost", false)`, `new RunRequest(null, null, null, null)`, `new RunRequest(TEAM, null, "chronos", null)`; the second test is renamed `rejectsMissingTeamOrUnknownModel` and loses its middle assertion.

`service/DefaultForecastServiceTest`: `build(...)` passes `Clock.fixed(SeededData.asOf().atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)` as the last constructor argument (imports `java.time.Clock`, `java.time.ZoneOffset`); every `new RunRequest(team, x, SeededData.asOf(), y, z)` becomes `new RunRequest(team, x, y, z)`; every `raw.create(request, LocalDateTime.now())` becomes `raw.create(request, SeededData.asOf(), LocalDateTime.now())`; the empty-team call becomes `service.runNow(new RunRequest(emptyTeam, null, null, null))`. Add:

```java
    @Test
    void theRunDayComesFromTheClockUnlessAnExperimentOverridesIt() {
        RunResult r = service.runNow(new RunRequest(team, null, "seasonal_naive", null));
        assertEquals(SeededData.asOf(), r.run().asOf(), "the fixed clock's day");
        assertEquals(LocalDate.of(2026, 9, 7), r.memberWindows().get(0).windowStart());
        RunResult wednesday = service.runNow(new RunRequest(team, null, "seasonal_naive", null), LocalDate.of(2026, 9, 2));
        assertEquals(LocalDate.of(2026, 9, 2), wednesday.run().asOf());
        assertEquals(LocalDate.of(2026, 9, 3), wednesday.memberWindows().get(0).windowStart());
    }

    @Test
    void theCurrentForecastIsTheLatestRunsDays() {
        RunResult r = service.runNow(new RunRequest(team, null, "seasonal_naive", null));
        List<CurrentDayForecast> current = service.currentForecast(team, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18));
        assertEquals(r.memberDays().size(), current.size());
        assertTrue(current.stream().allMatch(c -> c.runId().equals(r.run().id())), "the newest run wins every day it covers");
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class,
                () -> service.currentForecast(team, LocalDate.of(2026, 9, 18), LocalDate.of(2026, 9, 7))).code());
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class,
                () -> service.currentForecast(UUID.randomUUID(), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 18))).code());
    }
```

`store/JdbcRunStoreTest`: every `store.create(new RunRequest(TEAM, u, LocalDate.of(2026, 9, 6), m, p), t)` becomes `store.create(new RunRequest(TEAM, u, m, p), LocalDate.of(2026, 9, 6), t)`; in the overwrite test the two creates pass `wednesday` and `monday` as the second argument.

`web/ForecastControllerTest`: add `@MockitoBean Clock clock;` is NOT used; instead add to `Boot` a `@Bean Clock clock() { return Clock.fixed(LocalDate.of(2026, 9, 9).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC); }` (imports `java.time.Clock`, `java.time.ZoneOffset`, `org.springframework.context.annotation.Bean`); the start test's body loses `"asOf": "2026-09-06", ` and verifies `verify(service).startRun(new RunRequest(TEAM, USER, "xgboost", null));`; add

```java
    @Test
    void aRunDayInTheBodyIsRefused() throws Exception {
        mvc.perform(post("/forecast-api/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\": \"" + TEAM + "\", \"requestedBy\": \"" + USER + "\", \"asOf\": \"2026-09-06\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("asOf is not accepted: a run always starts from today"));
    }

    @Test
    void readsTheCurrentForecastWithDefaultsFromTheClock() throws Exception {
        CurrentDayForecast row = new CurrentDayForecast(TEAM, USER, LocalDate.of(2026, 9, 10), RUN, 4, 2, 0, 6, 8, 0, LocalDateTime.of(2026, 9, 9, 10, 0));
        when(service.currentForecast(TEAM, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 16))).thenReturn(List.of(row));
        when(service.currentForecast(TEAM, LocalDate.of(2026, 9, 9), LocalDate.of(2026, 9, 29))).thenReturn(List.of(row, row));
        mvc.perform(get("/forecast-api/teams/" + TEAM + "/current?from=2026-09-10&to=2026-09-16")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].demandHrs").value(6.0)).andExpect(jsonPath("$[0].day").value("2026-09-10"));
        mvc.perform(get("/forecast-api/teams/" + TEAM + "/current")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/forecast-api/teams/" + TEAM + "/current?from=not-a-date")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
```

`samplehost/SampleHostApplication`: add `@Bean Clock clock() { return Clock.fixed(SeededData.asOf().atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC); }` with a comment `The host pins the run day to the seed's end so the horizon lands where the data is; a real host has none of this.` `SampleHostIntegrationTest`: the POST body loses the `asOf` field; after the `DONE` assertions add

```java
        assertEquals(SeededData.asOf().toString(), result.path("run").path("asOf").asText(), "the host's clock");
        JsonNode current = json(mvc.perform(get("/api/forecast/teams/" + team + "/current?from=2026-09-07&to=2026-09-18")).andExpect(status().isOk()).andReturn());
        assertEquals(result.path("memberDays").size(), current.size());
        assertEquals(run.toString(), current.get(0).path("runId").asText());
```

`cli/RunCommandTest`: after the `--json` run add

```java
        String current = capture(cli, 0, "current", "--db", db.toString(), "--team", team, "--from", "2026-09-07", "--to", "2026-09-18");
        assertTrue(current.contains("2026-09-07") && current.contains("demand"), current);
        String currentJson = capture(cli, 0, "current", "--db", db.toString(), "--team", team, "--from", "2026-09-07", "--to", "2026-09-18", "--json");
        assertTrue(currentJson.trim().startsWith("[") && currentJson.contains("\"demandHrs\""), currentJson);
        assertEquals(2, cli.execute("current", "--db", db.toString(), "--team", team, "--from", "2026-09-18", "--to", "2026-09-07"));
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='RunRequestTest,DefaultForecastServiceTest,JdbcRunStoreTest,ForecastControllerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 2: The request, the interface, the store's create**

Replace `api/RunRequest.java` with:

```java
package com.workloadhub.forecast.api;

import com.workloadhub.forecast.run.ModelRegistry;
import java.util.UUID;

/** What a caller asks for: a team, optionally a forced model and the planned-work switch. The run day is the server's today. */
public record RunRequest(UUID teamId, UUID requestedBy, String forcedModel, Boolean plannedWork) {

    public RunRequest {
        if (teamId == null) {
            throw ForecastException.invalidRequest("teamId is required");
        }
        forcedModel = forcedModel == null || forcedModel.isBlank() ? null : forcedModel.trim();
        if (forcedModel != null && !ModelRegistry.isKnown(forcedModel)) {
            throw ForecastException.invalidRequest("unknown model " + forcedModel + "; known: " + ModelRegistry.NAMES);
        }
    }

    public boolean plannedWorkOr(boolean defaultValue) {
        return plannedWork == null ? defaultValue : plannedWork;
    }
}
```

In `api/ForecastService.java` add (imports `java.time.LocalDate`):

```java
    /** The team's current forecast per member and day between two days inclusive: the latest run that covered each day. */
    List<CurrentDayForecast> currentForecast(UUID teamId, LocalDate from, LocalDate to);
```

In `store/JdbcRunStore.java`, `create` becomes `create(RunRequest request, LocalDate asOf, LocalDateTime createdAt)` and binds `asOf.toString()` where it bound `request.asOf().toString()`.

- [ ] **Step 3: The service and the auto-configuration**

In `service/DefaultForecastService.java`: add a field `private final Clock clock;` (import `java.time.Clock`), a last constructor parameter `Clock clock` assigned to it; replace `startRun`, `runNow`, `enqueue` and the head of `execute` with

```java
    @Override
    public UUID startRun(RunRequest request) {
        LocalDate asOf = LocalDate.now(clock);
        UUID id = enqueue(request, asOf);
        executor.submit(() -> execute(id, request, asOf));
        return id;
    }

    /** The same run, synchronously, for the CLI and the tests; throws when the run fails. */
    public RunResult runNow(RunRequest request) {
        return runNow(request, LocalDate.now(clock));
    }

    /** The synchronous run with an explicit run day: an experiment entry for seeded databases, never used by the server. */
    public RunResult runNow(RunRequest request, LocalDate asOf) {
        UUID id = enqueue(request, asOf);
        RuntimeException failure = execute(id, request, asOf);
        if (failure != null) {
            throw failure;
        }
        return getRun(id);
    }

    private UUID enqueue(RunRequest request, LocalDate asOf) {
        requireTeam(request.teamId());
        UUID id = store.create(request, asOf, LocalDateTime.now());
        progress.start(id);
        return id;
    }
```

`execute(UUID id, RunRequest request, LocalDate asOf)` calls `runner.prepare(data, asOf, request.forcedModel(), ...)`. Add:

```java
    @Override
    public List<CurrentDayForecast> currentForecast(UUID teamId, LocalDate from, LocalDate to) {
        if (teamId == null || from == null || to == null) {
            throw ForecastException.invalidRequest("teamId, from and to are required");
        }
        if (from.isAfter(to)) {
            throw ForecastException.invalidRequest("from " + from + " is after to " + to);
        }
        requireTeam(teamId);
        return store.currentDays(teamId, from, to);
    }
```

In `ForecastAutoConfiguration.java` add (import `java.time.Clock`)

```java
    /** The run day is today by this clock; a host that keeps its own time zone or pins time in tests provides its own bean. */
    @Bean
    @ConditionalOnMissingBean
    Clock forecastClock() {
        return Clock.systemDefaultZone();
    }
```

and pass a `Clock clock` parameter of `forecastService(...)` as the last constructor argument. In `cli/Services.java` pass `Clock.systemDefaultZone()` (import `java.time.Clock`).

- [ ] **Step 4: The REST body and the current route**

Create `web/RunRequestBody.java`:

```java
package com.workloadhub.forecast.web;

import java.time.LocalDate;
import java.util.UUID;

/** The body of POST /runs. {@code asOf} is here only to be refused with a clear message: a run always starts from today. */
public record RunRequestBody(UUID teamId, UUID requestedBy, LocalDate asOf, String forcedModel, Boolean plannedWork) {
}
```

In `web/ForecastController.java`: the constructor gains `Clock clock` (field, import `java.time.Clock`; `ForecastWebConfiguration.forecastController` gains a `Clock clock` parameter and passes it); `start` becomes

```java
    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartedRun start(@RequestBody RunRequestBody body) {
        if (body.asOf() != null) {
            throw ForecastException.invalidRequest("asOf is not accepted: a run always starts from today");
        }
        return new StartedRun(service.startRun(new RunRequest(body.teamId(), body.requestedBy(), body.forcedModel(), body.plannedWork())));
    }
```

and add (imports `CurrentDayForecast`, `java.time.LocalDate`, `org.springframework.format.annotation.DateTimeFormat`):

```java
    public static final int CURRENT_DEFAULT_DAYS = 20;

    @GetMapping("/teams/{teamId}/current")
    public List<CurrentDayForecast> current(@PathVariable UUID teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        return service.currentForecast(teamId, from == null ? today : from, to == null ? today.plusDays(CURRENT_DEFAULT_DAYS) : to);
    }
```

A malformed `from` already maps to 400 `INVALID_REQUEST` through `ForecastExceptionHandler` (type mismatch); if the test shows otherwise, add `MethodArgumentTypeMismatchException` to the handler's 400 branch next to the existing path-variable case.

- [ ] **Step 5: The CLI**

`cli/RunCommand.java`: the `--as-of` description becomes `Run day, ISO (default: today; an experiment override, the server always uses today)`; the request is `new RunRequest(teamId, userId, model, noPlanned ? Boolean.FALSE : null)` and the call `s.service().runNow(request, date)`.

Create `cli/CurrentCommand.java`:

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.data.ExportFiles;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "current", description = "Print the team's current forecast per member and day: the latest run that covered each day.")
public class CurrentCommand implements Callable<Integer> {

    static final int DEFAULT_DAYS = 20;

    @Mixin DbOptions db;

    @Option(names = "--team", required = true, description = "Team name or id")
    String team;

    @Option(names = "--from", description = "First day, ISO (default: today)")
    String from;

    @Option(names = "--to", description = "Last day, ISO (default: today + 20 days)")
    String to;

    @Option(names = "--json", description = "Print the rows as JSON")
    boolean json;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            UUID teamId;
            LocalDate first;
            LocalDate last;
            try {
                teamId = TeamArg.resolve(s.jdbc(), s.dialect(), team);
                first = from == null ? LocalDate.now() : LocalDate.parse(from);
                last = to == null ? LocalDate.now().plusDays(DEFAULT_DAYS) : LocalDate.parse(to);
            } catch (IllegalArgumentException | DateTimeParseException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            List<CurrentDayForecast> rows;
            try {
                rows = s.service().currentForecast(teamId, first, last);
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return "INVALID_REQUEST".equals(e.code()) || "TEAM_NOT_FOUND".equals(e.code()) ? 2 : 1;
            }
            if (json) {
                System.out.println(ExportFiles.mapper().writeValueAsString(rows));
                return 0;
            }
            Map<UUID, String> names = new HashMap<>();
            s.jdbc().sql("SELECT id, full_name FROM users").query().listOfRows()
                    .forEach(row -> names.put(UUID.fromString(row.get("id").toString()), String.valueOf(row.get("full_name"))));
            System.out.printf("%-28s %-10s %-36s %7s %7s %7s %7s %8s %8s%n", "member", "day", "run", "open", "new", "planned", "demand", "capacity", "overload");
            for (CurrentDayForecast c : rows) {
                System.out.printf("%-28s %-10s %-36s %7.1f %7.1f %7.1f %7.1f %8.1f %8.1f%n", names.getOrDefault(c.userId(), c.userId().toString()), c.day(),
                        c.runId(), c.openHrs(), c.newHrs(), c.plannedHrs(), c.demandHrs(), c.capacityHrs(), c.overloadHrs());
            }
            return 0;
        }
    }
}
```

Register it in `cli/ForecastCli.java`'s `subcommands` after `RunsCommand.class`.

- [ ] **Step 6: Focused tests, whole gate, commit**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='RunRequestTest,DefaultForecastServiceTest,JdbcRunStoreTest,ForecastControllerTest,SampleHostIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. Then `cd server && mvn -B -q verify` (the CLI tests included).
Expected: exit 0.

```bash
git add server
git commit -m "feat(server): the run day is the server's today; the current forecast is readable

RunRequest loses asOf and the service takes the day from a Clock bean
the host may override; a body that still sends asOf gets a 400. The
per-day current forecast is exposed through ForecastService, a REST
route and the CLI's current command; the CLI keeps --as-of on run as an
experiment override for seeded databases."
```

---

### Task 4: Skills, tool descriptions, prompt wording and documents

**Files:**
- Modify: `resources/skills/whf-domain/SKILL.md`, `whf-forecast-interpretation/SKILL.md`, `whf-rebalancing-advice/SKILL.md`, `whf-report-style/SKILL.md`, `whf-likely-work/SKILL.md`, `ai/FactsTools.java` (descriptions only), `ai/Prompts.java` (rule 5), `test/.../ai/SkillTextsTest.java`, `server/README.md`, `CLAUDE.md`, `docs/backlog.md`, `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`, `docs/requirements/requirements-v1.md`

- [ ] **Step 1: The skill vocabulary test**

In `test/.../ai/SkillTextsTest.java`, `theSkillsSpeakTheJavaFactsVocabulary`, add:

```java
        assertTrue(all.contains("window") && all.contains("expected_window") && all.contains("five weekdays"), "the rolling horizon vocabulary");
        assertTrue(!all.contains("expected_week") && !all.contains("two-week forecast") && !all.contains("per member and week"), "the weekly horizon is gone");
```

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=SkillTextsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on the new assertions.

- [ ] **Step 2: The skills**

`whf-domain/SKILL.md`: in "Organisation", after `public holidays are off days.` add ` A **forecast window** is five weekdays starting the first weekday after the run day (a run on a Friday or a weekend starts on Monday); a run covers two contiguous windows; a holiday inside a window stays inside with zero capacity and no predicted arrivals. Weeks remain the unit of history.` Rename the heading `## Forecast rows (\`forecast\`, per member and week)` to `## Forecast rows (\`forecast\`, per member and window)`, and in that section: the row identifies its window by `window` (1 or 2), `start` and `end` (ISO dates); `capacity` reads `available hours after holidays, absences and the team's capacity plan (default 40 h per week, 8 h per working day, summed over the window's working days)`; `due_hours` says `that fall due inside the window`; add a bullet `- **days**: one entry per weekday of the horizon (\`day\`, \`window\`, \`demand\`, \`capacity\`, \`overload\`, \`open_hours\`, \`new_hours\`, \`planned_hours\`, \`working_day\`); a window's figures are the sums of its days.` In "Team and run facts": `team.totals (demand, capacity and planned per window)`; add `run.windows` (`index`, `start`, `end`, `working_days`) to the run facts sentence.

`whf-forecast-interpretation/SKILL.md`: description `How to explain a member's forecast over the two windows, capacity, overload, interval and the model quality facts without adding or changing any number.`; `overload hours per week` → `overload hours per window`; `Capacity below 40 h means` → `Capacity below 40 h in a window means`; `Overdue open tasks are placed forward from the first forecast week; they inflate week one by design.` → `Overdue open tasks are placed forward from the first forecast day; they inflate window one by design.`; `due_hours above capacity in a week` → `in a window`; add the bullet `- A single day in \`days\` whose demand is well above its capacity is worth naming when the window total hides it: say the day (ISO date) and the two figures as given.`; risk levels: `in either week` → `in either window`.

`whf-rebalancing-advice/SKILL.md`: `in the same week` → `in the same window` (rule 1), `not absent that week` → `not absent in that window` (rule 2), `in that week` → `in that window` (rule 4), `naming the week and the hours` → `naming the window (its first day) and the hours` (rule 7).

`whf-report-style/SKILL.md`: `and week (ISO date)` → `and window (its first day as an ISO date, in English and in French alike; never spell dates out)`; `starting with the week` → `starting with the window's first day`.

`whf-likely-work/SKILL.md`: `\`expected_week\` or \`after_window\`` → `\`expected_window\` (1 or 2) or \`after_window\``.

`ai/FactsTools.java` descriptions: `get_member_forecast` → `Forecast rows per window (five weekdays each: demand, low, high, capacity, overload, open, new and planned hours, due hours) for one member.`; `get_member_capacity` → `Capacity, demand and overload per forecast window and per day for one member, with working days and absence hours.`; `get_project_timelines` → `... plus the forecast windows.`; `get_rebalancing_candidates` → `... over the two windows.`. `ai/Prompts.java` rule 5: `in the same week` → `in the same window`.

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='SkillTextsTest,PromptsTest,FactsToolsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 3: The documents**

`server/README.md`:
- CLI table: the `run` row's description becomes `Runs a forecast for one team from the run day (default: today; \`--as-of\` is an experiment override for seeded databases, the server always uses today) and prints the champion, the scores and the member-window table; ...`; add a row `| \`current\` | \`--team <name or id> [--from] [--to] [--db] [--json]\` | Prints the team's current forecast per member and day (the latest run that covered each day) between \`--from\` (default: today) and \`--to\` (default: today + 20 days). Exits 2 on a usage error. |`.
- REST table: the `POST /runs` row becomes `| \`POST /runs\` | \`RunRequestBody\` (\`teamId\`, \`requestedBy\`, \`forcedModel\`, \`plannedWork\`; an \`asOf\` field is refused with 400) | 202, \`{"id": "<uuid>"}\` |`; `GET /runs/{id}` says `(the run, its member windows and days, scores and facts)`; add `| \`GET /teams/{teamId}/current?from=YYYY-MM-DD&to=YYYY-MM-DD\` | | 200, \`CurrentDayForecast[]\` per member and day (defaults: today and today + 20 days) |`.
- Properties section: after the table add the paragraph `The run day is today by the \`java.time.Clock\` bean; the auto-configuration registers \`Clock.systemDefaultZone()\` unless the host provides one (a fixed clock in tests, a zoned clock in production). A forecast covers the ten weekdays after the run day in two windows of five (\`docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md\`).`
- Parity section: add `Since 2026-09-10 the Java harness's \`demand.csv\` is per member-window while the archived Python harness's is per member-week; the gate compares \`scores.csv\` only.`

`CLAUDE.md`: in "Where the project stands", after the archival sentence add `Then the rolling forecast windows (\`docs/superpowers/plans/2026-09-10-rolling-forecast-windows.md\`): a run starts the first weekday after the run day, covers ten weekdays in two windows, is computed per day and keeps a per-day current forecast.`; in "Domain vocabulary" add `window (five weekdays; a run covers two, starting the first weekday after the run day), current forecast (the latest run's value per member and day)`; the `forecast-cli` command list gains `current`.

`docs/backlog.md`: under `## Java migration` add a bullet `- **Rolling forecast windows** (2026-09-10): a forecast starts the first weekday after the run day and covers ten weekdays in two windows of five, computed per day; each run upserts a per-day current forecast (\`forecast_current_days\`) that the accuracy evaluation will read; the caller no longer chooses the run day (REST refuses \`asOf\`; the CLI keeps \`--as-of\` for seeded experiments); the evaluation's demand level is per member-window while its arrival level, the parity gate, is unchanged. Spec \`docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md\`.`; in the "Accuracy evaluation, design first" item replace `make sure version 1 already records it, because it cannot be recovered retroactively` with `the per-day current forecast (\`forecast_current_days\`, since 2026-09-10) records the forecast made for each day before it arrived; what remains is the comparison with the logged hours once real weeks have passed`.

`docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`: under the `## 5. Data access and domain rules` heading, after the intro paragraph, add `> Amended on 2026-09-10: capacity is computed per day and summed per forecast window; see \`docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md\`, section 5.`; under `## 9. The run pipeline` add `> Amended on 2026-09-10: the horizon is two windows of five weekdays from the first weekday after the run day, computed per day; steps 2 to 7 are as in \`docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md\`, sections 3 to 7.`; under `### 10.1 Facts` add `> Amended on 2026-09-10: \`run.windows\`, per-window forecast rows with a \`days\` list, and \`expected_window\`; see the rolling-windows design, section 9.`; under `## 11. Public API` add `> Amended on 2026-09-10: \`RunRequest\` has no \`asOf\` (the run day is the server's clock), \`RunResult\` carries member windows and days, and \`currentForecast(teamId, from, to)\` reads the per-day current forecast; see the rolling-windows design, section 8.`

`docs/requirements/requirements-v1.md`: after F1 add `  - **[amended 2026-09-10]** The horizon is the next ten weekdays after the run day, in two windows of five (a run on a Friday or a weekend starts on Monday); the user does not choose the start day; a later run overwrites the days that have not arrived yet. Design: \`docs/superpowers/specs/2026-09-10-rolling-forecast-windows-design.md\`.`; after F2 add `  - **[amended 2026-09-10]** Reported per window and per day.`

- [ ] **Step 4: Verify and commit**

Run: `git grep -n "forecastWeeks\|MemberWeekForecast\|memberWeeks\|expected_week\|run.weeks" -- server CLAUDE.md README.md docs/backlog.md`
Expected: no output. Then `cd server && mvn -B -q verify`, exit 0.

```bash
git add server CLAUDE.md docs
git commit -m "docs(server): the skills, tool descriptions and documents speak of forecast windows

Copilot is told what a window is and to cite its first day as an ISO date;
the README, CLAUDE.md, the backlog, the Java spec and requirement F1
record the rolling horizon and the current forecast."
```

---

## Closing notes for the executor and the reviewer

**Rulings this plan takes, to report to the owner at the end:**

1. The build is red between Task 2's Step 1 and Step 12: the switch from weeks to windows is one compile unit (pipeline, facts, store, service, evaluation, CLI table), so Task 2 is one commit. Task 1 is purely additive and Task 3 is the caller-facing change, each green on its own.
2. On a weekend run the origin stays the week before the one that just ended (`Weeks.lastCompleteWeek` unchanged), so the windows use horizons `{2, 3}`; the arrival-level evaluation and the Python parity keep their origin arithmetic.
3. The evaluation replays keep `asOf = origin + 1 week` (a Monday), so replayed windows run Tuesday to Monday; truth is summed over the window's days; `demand.csv` is no longer row-comparable with the archived Python harness (the gate is `scores.csv`).
4. `RunRequestBody` refuses `asOf` explicitly rather than ignoring it, so a host cannot believe it chose the run day.
5. The sample host pins a fixed `Clock` at the seed's end date; a real host provides none or its own zoned clock.
6. `placeNewArrivals` (weekly) stays as a thin wrapper for its tests; the runner uses the per-day forms only.
7. The current-forecast overwrite rule is pinned by an example over two run days (a Wednesday, then the next Monday) on both dialects rather than a jqwik property, because each try needs a migrated database; the horizon, placement, capacity and window-sum invariants carry the properties.

**What comes next:** the live Copilot check on a seeded database (the narrative now speaks of windows), the host integration design, then the accuracy evaluation reading `forecast_current_days`.
