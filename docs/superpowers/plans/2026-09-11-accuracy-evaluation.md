# Accuracy Evaluation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Java method `ForecastService.accuracy(teamId, from, to)` and a CLI command `accuracy` that compare the forecasts made for days that have passed with the hours members logged, reporting MAE, bias, MASE and overload precision and recall per member, per team and by lead.

**Architecture:** Three tasks. First the pure computation (`eval/Accuracy`) with its records in `api/`, pinned by an example and jqwik properties. Then the persistence and service seam (one store query, the service method, a `ForecastService` default-free interface addition, tests on the seeded database and on both dialects). Then the report writer, the CLI command and the documents. `mvn -B -q verify` in `server/` must be green after every task (four to six minutes, 600000 ms timeout; jqwik's "If you are an AI Agent..." banner is library output to ignore).

**Tech Stack:** Java 21, Spring `JdbcClient`, JUnit 6, jqwik, picocli, SQLite and PostgreSQL (Testcontainers when Docker is present).

**Spec:** `docs/superpowers/specs/2026-09-11-accuracy-evaluation-design.md` (read it first).

## Global Constraints

- Records, exactly: `AccuracyRow(UUID userId, LocalDate day, UUID runId, int lead, double forecastHrs, double loggedHrs, double capacityHrs, boolean forecastOverload, boolean actualOverload)`; `AccuracyScore(String scope, String key, int n, double mae, double bias, double mase, double overloadPrecision, double overloadRecall)` with `scope` one of `"team"`, `"member"`, `"lead"`; `AccuracyResult(UUID teamId, LocalDate from, LocalDate to, LocalDate evaluatedAt, List<AccuracyRow> current, List<AccuracyScore> scores)`. All in `com.workloadhub.forecast.api`.
- `ForecastService.accuracy(UUID teamId, LocalDate from, LocalDate to)`: `INVALID_REQUEST` when an argument is null or `from` is after `to`; `TEAM_NOT_FOUND` for an unknown team; `to` later than yesterday (the service clock's day minus one) is clamped to yesterday; days compared are weekdays `d` with `from <= d <= to`; `evaluatedAt` is the clock's day.
- Truth: `eval.Truth.realisedHoursByDay(data)`; a forecast day without a log counts as 0 logged hours; a day without a forecast row is skipped. `forecastHrs` is the row's `demandHrs`; `capacityHrs` the row's; `forecastOverload = overloadHrs > 0`; `actualOverload = loggedHrs > capacityHrs`.
- Lead: the number of weekdays strictly after the run's `asOf` up to and including the day (`Horizon.firstDay(asOf)` is lead 1; a run's ten days are leads 1 to 10).
- Metrics: `Metrics.mae`, `Metrics.bias`, `Backtest.mase(y, yHat, yNaive)` where `yNaive[i]` is the truth of the same member seven days earlier (`Truth` map, 0 when absent) — rows whose day minus seven days is before the earliest logged day of that member are still scored (0 naive); `Metrics.overloadPrecisionRecall`. `NaN` on empty input; `n` is the number of rows.
- Scores ordering: the `team` score first (key = the team id), then `member` scores by `Ids.UUID_ORDER` of the user id (key = the user id), then `lead` scores 1 to 10 (key = the lead as a string), only leads with `n > 0`.
- The `member` and `team` scores are computed over `current` rows (`forecast_current_days`); the `lead` scores over every `DONE` run's `forecast_member_days` rows in the range (a day may appear once per run).
- Nothing stored; no REST endpoint; no Copilot. English only in code and documents.
- Package prefix `com.workloadhub.forecast` under `server/forecast-core/src/main/java/...` (main), `server/forecast-core/src/test/java/...` (tests), `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/` (CLI).
- Commit messages: imperative subject, a short body saying why, then after a blank line the two trailer lines the dispatch names. Do not push. No model identifier in committed content.

---

## File structure

| Task | Creates | Modifies |
|---|---|---|
| 1 | `api/AccuracyRow.java`, `api/AccuracyScore.java`, `api/AccuracyResult.java`, `eval/Accuracy.java`, `eval/RunDayForecast.java`, tests `eval/AccuracyTest.java`, `eval/AccuracyProperties.java` | |
| 2 | | `api/ForecastService.java`, `store/JdbcRunStore.java`, `service/DefaultForecastService.java`, tests `store/JdbcRunStoreTest.java`, `service/DefaultForecastServiceTest.java`, `samplehost/HostForecastFacade.java` (nothing: it does not implement the interface) |
| 3 | `eval/AccuracyReport.java`, `cli/AccuracyCommand.java`, test `eval/AccuracyReportTest.java` | `cli/ForecastCli.java`, `cli/RunCommandTest.java`, `server/README.md`, `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`, `docs/backlog.md`, `CLAUDE.md` |

---

### Task 1: The pure computation

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/AccuracyRow.java`, `.../api/AccuracyScore.java`, `.../api/AccuracyResult.java`, `.../eval/RunDayForecast.java`, `.../eval/Accuracy.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/eval/AccuracyTest.java`, `.../eval/AccuracyProperties.java`

**Interfaces:**
- Consumes: `api.CurrentDayForecast` (fields `userId, day, runId, demandHrs, capacityHrs, overloadHrs`), `api.MemberDayForecast`, `features.MemberDay`, `eval.Metrics`, `backtest.Backtest.mase`, `calendar.Horizon.firstDay`, `data.Ids.UUID_ORDER`.
- Produces: `eval.RunDayForecast(UUID runId, LocalDate asOf, MemberDayForecast day)` and
  `Accuracy.evaluate(UUID teamId, LocalDate from, LocalDate to, LocalDate evaluatedAt, List<CurrentDayForecast> current, List<RunDayForecast> runDays, SortedMap<MemberDay, Double> logged) -> AccuracyResult`, plus `static int Accuracy.lead(LocalDate asOf, LocalDate day)`.

- [ ] **Step 1: The records**

```java
package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.UUID;

/** One member on one weekday that has passed: what was forecast for it before it arrived, and what was logged. */
public record AccuracyRow(UUID userId, LocalDate day, UUID runId, int lead, double forecastHrs, double loggedHrs, double capacityHrs,
        boolean forecastOverload, boolean actualOverload) {
}
```

```java
package com.workloadhub.forecast.api;

/** Accuracy over a set of rows: {@code scope} is "team", "member" or "lead"; {@code key} the team id, the user id or the lead. NaN when empty. */
public record AccuracyScore(String scope, String key, int n, double mae, double bias, double mase, double overloadPrecision, double overloadRecall) {
}
```

```java
package com.workloadhub.forecast.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** The accuracy of a team's forecasts between two days: the current-forecast rows compared and the scores by team, member and lead. */
public record AccuracyResult(UUID teamId, LocalDate from, LocalDate to, LocalDate evaluatedAt, List<AccuracyRow> current, List<AccuracyScore> scores) {
}
```

```java
package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.api.MemberDayForecast;
import java.time.LocalDate;
import java.util.UUID;

/** One day row of one run, with the run day it was made on. */
public record RunDayForecast(UUID runId, LocalDate asOf, MemberDayForecast day) {
}
```

- [ ] **Step 2: Write the failing example test**

```java
package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.features.MemberDay;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccuracyTest {

    static final UUID TEAM = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID A = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID RUN1 = UUID.fromString("50000000-0000-0000-0000-000000000001");
    static final UUID RUN2 = UUID.fromString("50000000-0000-0000-0000-000000000002");
    static final LocalDate MON = LocalDate.of(2026, 8, 24);
    static final LocalDate TUE = MON.plusDays(1);
    static final LocalDate WED = MON.plusDays(2);
    static final LocalDate SAT = MON.plusDays(5);

    static CurrentDayForecast current(UUID user, LocalDate day, UUID run, double demand, double capacity) {
        return new CurrentDayForecast(TEAM, user, day, run, demand, 0, 0, demand, capacity, Math.max(0, demand - capacity), LocalDateTime.of(2026, 8, 20, 9, 0));
    }

    static RunDayForecast runDay(UUID run, LocalDate asOf, UUID user, LocalDate day, double demand, double capacity) {
        return new RunDayForecast(run, asOf, new MemberDayForecast(user, day, 1, demand, 0, 0, demand, capacity, Math.max(0, demand - capacity), true));
    }

    @Test
    void leadCountsWeekdaysAfterTheRunDay() {
        LocalDate wednesday = LocalDate.of(2026, 8, 19);
        assertEquals(1, Accuracy.lead(wednesday, LocalDate.of(2026, 8, 20)));
        assertEquals(2, Accuracy.lead(wednesday, LocalDate.of(2026, 8, 21)));
        assertEquals(3, Accuracy.lead(wednesday, LocalDate.of(2026, 8, 24)), "the weekend is not counted");
        assertEquals(10, Accuracy.lead(wednesday, LocalDate.of(2026, 9, 2)));
        LocalDate saturday = LocalDate.of(2026, 8, 22);
        assertEquals(1, Accuracy.lead(saturday, LocalDate.of(2026, 8, 24)), "a weekend run starts on Monday");
    }

    @Test
    void scoresATeamByMemberAndByLead() {
        // A: forecast 8 on Monday (logged 6), 8 on Tuesday (logged 10, capacity 8: actual overload, forecast no overload).
        // B: forecast 10 on Monday (capacity 8: forecast overload, logged 4: not actual), Tuesday forecast 8 with no log at all (truth 0).
        // Wednesday is after `to`; Saturday rows never exist. Run 1 was made on Thursday 2026-08-20 (lead 2 on Monday, 3 on Tuesday);
        // run 2 on Friday 2026-08-21 (lead 1 on Monday, 2 on Tuesday) and is the current one.
        List<CurrentDayForecast> current = List.of(
                current(A, MON, RUN2, 8, 8), current(A, TUE, RUN2, 8, 8), current(A, WED, RUN2, 8, 8),
                current(B, MON, RUN2, 10, 8), current(B, TUE, RUN2, 8, 8));
        List<RunDayForecast> runDays = List.of(
                runDay(RUN1, LocalDate.of(2026, 8, 20), A, MON, 7, 8), runDay(RUN1, LocalDate.of(2026, 8, 20), A, TUE, 7, 8),
                runDay(RUN2, LocalDate.of(2026, 8, 21), A, MON, 8, 8), runDay(RUN2, LocalDate.of(2026, 8, 21), A, TUE, 8, 8),
                runDay(RUN2, LocalDate.of(2026, 8, 21), B, MON, 10, 8), runDay(RUN2, LocalDate.of(2026, 8, 21), B, TUE, 8, 8));
        SortedMap<MemberDay, Double> logged = new TreeMap<>();
        logged.put(new MemberDay(A, MON), 6.0);
        logged.put(new MemberDay(A, TUE), 10.0);
        logged.put(new MemberDay(A, MON.minusDays(7)), 5.0);
        logged.put(new MemberDay(B, MON), 4.0);
        AccuracyResult r = Accuracy.evaluate(TEAM, MON, TUE, SAT, current, runDays, logged);

        assertEquals(4, r.current().size(), "Wednesday is outside the range");
        AccuracyRow aMon = r.current().get(0);
        assertEquals(new AccuracyRow(A, MON, RUN2, 1, 8, 6, 8, false, false), aMon);
        assertEquals(new AccuracyRow(A, TUE, RUN2, 2, 8, 10, 8, false, true), r.current().get(1));
        assertEquals(new AccuracyRow(B, MON, RUN2, 1, 10, 4, 8, true, false), r.current().get(2));
        assertEquals(new AccuracyRow(B, TUE, RUN2, 2, 8, 0, 8, false, false), r.current().get(3), "no log means zero hours");

        AccuracyScore team = r.scores().get(0);
        assertEquals("team", team.scope());
        assertEquals(TEAM.toString(), team.key());
        assertEquals(4, team.n());
        assertEquals((2 + 2 + 6 + 8) / 4.0, team.mae(), 1e-9);
        assertEquals((2 - 2 + 6 + 8) / 4.0, team.bias(), 1e-9);
        // naive: A Monday -> 5 (logged a week earlier); every other row -> 0 (no log a week earlier)
        assertEquals((2 + 2 + 6 + 8) / (double) (Math.abs(6 - 5) + 10 + 4 + 0), team.mase(), 1e-9);
        assertEquals(0.0, team.overloadPrecision(), 1e-9, "one forecast overload (B Monday), not actual");
        assertEquals(0.0, team.overloadRecall(), 1e-9, "one actual overload (A Tuesday), not forecast");

        AccuracyScore a = r.scores().get(1);
        assertEquals("member", a.scope());
        assertEquals(A.toString(), a.key());
        assertEquals(2, a.n());
        assertEquals(2.0, a.mae(), 1e-9);
        assertEquals(0.0, a.bias(), 1e-9);
        assertTrue(Double.isNaN(a.overloadPrecision()), "A never had a forecast overload");
        assertEquals(0.0, a.overloadRecall(), 1e-9);
        AccuracyScore b = r.scores().get(2);
        assertEquals(B.toString(), b.key());
        assertEquals(7.0, b.mae(), 1e-9);
        assertTrue(Double.isNaN(b.overloadRecall()), "B never had an actual overload");

        List<AccuracyScore> leads = r.scores().stream().filter(s -> s.scope().equals("lead")).toList();
        assertEquals(List.of("1", "2", "3"), leads.stream().map(AccuracyScore::key).toList());
        assertEquals(2, leads.get(0).n(), "lead 1: run 2's Monday rows for A and B");
        assertEquals((2 + 6) / 2.0, leads.get(0).mae(), 1e-9);
        assertEquals(3, leads.get(1).n(), "lead 2: run 1's Monday row for A, run 2's Tuesday rows for A and B");
        assertEquals((Math.abs(7 - 6) + 2 + 8) / 3.0, leads.get(1).mae(), 1e-9);
        assertEquals(1, leads.get(2).n(), "lead 3: run 1's Tuesday row for A");
        assertEquals(3.0, leads.get(2).mae(), 1e-9);
        assertEquals(SAT, r.evaluatedAt());
    }

    @Test
    void anEmptyRangeGivesNaNScoresAndNoRows() {
        AccuracyResult r = Accuracy.evaluate(TEAM, MON, TUE, SAT, List.of(), List.of(), new TreeMap<>());
        assertTrue(r.current().isEmpty());
        assertEquals(1, r.scores().size(), "the team score is always there");
        assertEquals(0, r.scores().get(0).n());
        assertTrue(Double.isNaN(r.scores().get(0).mae()));
    }

    @Test
    void weekendsAndDaysOutsideTheRangeAreIgnored() {
        List<CurrentDayForecast> current = List.of(current(A, SAT, RUN2, 8, 8), current(A, MON.minusDays(3), RUN2, 8, 8));
        AccuracyResult r = Accuracy.evaluate(TEAM, MON, TUE, SAT, current, List.of(), new TreeMap<>());
        assertTrue(r.current().isEmpty());
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=AccuracyTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure (`Accuracy`, `RunDayForecast` and the records do not exist).

- [ ] **Step 4: Implement `Accuracy`**

```java
package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.MemberDayForecast;
import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.calendar.Horizon;
import com.workloadhub.forecast.data.Ids;
import com.workloadhub.forecast.features.MemberDay;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/** Forecast versus logged hours per member and weekday that has passed (design 2026-09-11). Pure: no JDBC, no clock. */
public final class Accuracy {

    public static final String TEAM = "team";
    public static final String MEMBER = "member";
    public static final String LEAD = "lead";

    private Accuracy() {
    }

    /** Weekdays strictly after {@code asOf} up to and including {@code day}: the first weekday after the run day is lead 1. */
    public static int lead(LocalDate asOf, LocalDate day) {
        int lead = 0;
        for (LocalDate d = asOf.plusDays(1); !d.isAfter(day); d = d.plusDays(1)) {
            if (isWeekday(d)) {
                lead++;
            }
        }
        return lead;
    }

    static boolean isWeekday(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    public static AccuracyResult evaluate(UUID teamId, LocalDate from, LocalDate to, LocalDate evaluatedAt, List<CurrentDayForecast> current,
            List<RunDayForecast> runDays, SortedMap<MemberDay, Double> logged) {
        List<AccuracyRow> rows = new ArrayList<>();
        for (CurrentDayForecast c : current) {
            if (inRange(c.day(), from, to)) {
                rows.add(row(c.userId(), c.day(), c.runId(), lead(asOfOf(c, runDays), c.day()), c.demandHrs(), c.capacityHrs(), c.overloadHrs(), logged));
            }
        }
        rows.sort((a, b) -> a.userId().equals(b.userId()) ? a.day().compareTo(b.day()) : Ids.UUID_ORDER.compare(a.userId(), b.userId()));
        List<AccuracyScore> scores = new ArrayList<>();
        scores.add(score(TEAM, teamId.toString(), rows, logged));
        SortedMap<UUID, List<AccuracyRow>> byMember = new TreeMap<>(Ids.UUID_ORDER);
        for (AccuracyRow r : rows) {
            byMember.computeIfAbsent(r.userId(), k -> new ArrayList<>()).add(r);
        }
        byMember.forEach((user, rs) -> scores.add(score(MEMBER, user.toString(), rs, logged)));
        SortedMap<Integer, List<AccuracyRow>> byLead = new TreeMap<>();
        for (RunDayForecast rd : runDays) {
            MemberDayForecast d = rd.day();
            if (inRange(d.day(), from, to)) {
                int lead = lead(rd.asOf(), d.day());
                byLead.computeIfAbsent(lead, k -> new ArrayList<>())
                        .add(row(d.userId(), d.day(), rd.runId(), lead, d.demandHrs(), d.capacityHrs(), d.overloadHrs(), logged));
            }
        }
        byLead.forEach((lead, rs) -> scores.add(score(LEAD, Integer.toString(lead), rs, logged)));
        return new AccuracyResult(teamId, from, to, evaluatedAt, List.copyOf(rows), List.copyOf(scores));
    }

    private static boolean inRange(LocalDate day, LocalDate from, LocalDate to) {
        return isWeekday(day) && !day.isBefore(from) && !day.isAfter(to);
    }

    /** The run day of the current row's run; the current row carries only the run id, so it is looked up among the run days (else lead 0). */
    private static LocalDate asOfOf(CurrentDayForecast c, List<RunDayForecast> runDays) {
        for (RunDayForecast rd : runDays) {
            if (rd.runId().equals(c.runId())) {
                return rd.asOf();
            }
        }
        return c.day();
    }

    private static AccuracyRow row(UUID user, LocalDate day, UUID runId, int lead, double forecast, double capacity, double overload,
            SortedMap<MemberDay, Double> logged) {
        double actual = logged.getOrDefault(new MemberDay(user, day), 0.0);
        return new AccuracyRow(user, day, runId, lead, forecast, actual, capacity, overload > 0, actual > capacity);
    }

    static AccuracyScore score(String scope, String key, List<AccuracyRow> rows, SortedMap<MemberDay, Double> logged) {
        int n = rows.size();
        double[] y = new double[n];
        double[] p = new double[n];
        double[] naive = new double[n];
        boolean[] actualOver = new boolean[n];
        boolean[] forecastOver = new boolean[n];
        for (int i = 0; i < n; i++) {
            AccuracyRow r = rows.get(i);
            y[i] = r.loggedHrs();
            p[i] = r.forecastHrs();
            naive[i] = logged.getOrDefault(new MemberDay(r.userId(), r.day().minusDays(7)), 0.0);
            actualOver[i] = r.actualOverload();
            forecastOver[i] = r.forecastOverload();
        }
        double[] pr = Metrics.overloadPrecisionRecall(actualOver, forecastOver);
        return new AccuracyScore(scope, key, n, Metrics.mae(y, p), Metrics.bias(y, p), n == 0 ? Double.NaN : Backtest.mase(y, p, naive), pr[0], pr[1]);
    }
}
```

Note for the implementer: the example test expects the current row's lead from its run's `asOf`; the test passes run days for both runs, so `asOfOf` finds them. Task 2 always loads the run days of every `DONE` run in the range, so the lookup succeeds there too. If a current row's run has no day rows in the list (a run whose days were all outside the range cannot be current for a day inside it, so this does not happen), the lead is 0.

- [ ] **Step 5: Run the example test to verify it passes**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=AccuracyTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS. If a hand-computed number disagrees with the code, recompute by hand from the comment in the test before touching the code: the test's numbers are the specification.

- [ ] **Step 6: Write the jqwik properties**

```java
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
```

- [ ] **Step 7: Run both test classes**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest='AccuracyTest,AccuracyProperties' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS (jqwik prints its banner; ignore it).

- [ ] **Step 8: Full verify, then commit**

Run: `cd server && mvn -B -q verify` (600000 ms). Expected: exit 0.

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/api/AccuracyRow.java server/forecast-core/src/main/java/com/workloadhub/forecast/api/AccuracyScore.java server/forecast-core/src/main/java/com/workloadhub/forecast/api/AccuracyResult.java server/forecast-core/src/main/java/com/workloadhub/forecast/eval/RunDayForecast.java server/forecast-core/src/main/java/com/workloadhub/forecast/eval/Accuracy.java server/forecast-core/src/test/java/com/workloadhub/forecast/eval/AccuracyTest.java server/forecast-core/src/test/java/com/workloadhub/forecast/eval/AccuracyProperties.java
git commit -m "feat(server): score forecasts against logged hours per member, team and lead" -m "The accuracy evaluation compares the day forecasts made before each weekday with the hours logged on it; the computation is pure so the seams stay thin."
```

(Then the two trailer lines from the dispatch, after a blank line.)

---

### Task 2: The store query and the service method

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/ForecastService.java`, `.../store/JdbcRunStore.java`, `.../service/DefaultForecastService.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/JdbcRunStoreTest.java`, `.../service/DefaultForecastServiceTest.java`

**Interfaces:**
- Consumes: `Accuracy.evaluate(...)`, `RunDayForecast`, `Truth.realisedHoursByDay(ForecastData)`, `ForecastRepository.loadAll()`, `store.currentDays(teamId, from, to)`, the service's `clock` and `requireTeam`.
- Produces: `JdbcRunStore.runDays(UUID teamId, LocalDate from, LocalDate to) -> List<RunDayForecast>` (rows of the team's `DONE` runs whose `day` is in the range, with the run's `as_of`, ordered by run id, user id, day); `ForecastService.accuracy(UUID teamId, LocalDate from, LocalDate to) -> AccuracyResult`.

- [ ] **Step 1: Write the failing store test**

Add to `JdbcRunStoreTest` (it has `sqlite()`, `days(asOf, demand)`, `windows()`, `TEAM`, `USER`, `T0`, and a `lifecycle(DataSource)` showing how `create`, `markRunning` and `finish` are called; copy that call shape):

```java
    void runDaysJoinTheRunDayOfEveryDoneRunInTheRange(DataSource ds) {
        JdbcRunStore store = new JdbcRunStore(ds, Dialect.of(ds));
        LocalDate wednesday = LocalDate.of(2026, 8, 19);
        UUID first = store.create(new RunRequest(TEAM, USER, null, null), wednesday, T0);
        store.markRunning(first);
        store.finish(first, "seasonal_naive", 1.0, "{}", windows(), days(wednesday, 7), "{}", T0.plusMinutes(1));
        LocalDate friday = LocalDate.of(2026, 8, 21);
        UUID second = store.create(new RunRequest(TEAM, USER, null, null), friday, T0.plusHours(1));
        store.markRunning(second);
        store.finish(second, "seasonal_naive", 1.0, "{}", windows(), days(friday, 8), "{}", T0.plusHours(2));
        UUID failed = store.create(new RunRequest(TEAM, USER, null, null), friday, T0.plusHours(3));
        store.markRunning(failed);
        store.fail(failed, "boom", T0.plusHours(4));

        List<RunDayForecast> rows = store.runDays(TEAM, LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 25));
        assertEquals(4, rows.size(), "two runs, Monday and Tuesday each; the failed run has no day rows");
        assertTrue(rows.stream().anyMatch(r -> r.runId().equals(first) && r.asOf().equals(wednesday) && r.day().demandHrs() == 7));
        assertTrue(rows.stream().anyMatch(r -> r.runId().equals(second) && r.asOf().equals(friday) && r.day().demandHrs() == 8));
        assertTrue(rows.stream().allMatch(r -> !r.day().day().isBefore(LocalDate.of(2026, 8, 24)) && !r.day().day().isAfter(LocalDate.of(2026, 8, 25))));
        assertTrue(store.runDays(UUID.randomUUID(), LocalDate.of(2026, 8, 24), LocalDate.of(2026, 8, 25)).isEmpty());
    }

    @Test
    void sqliteRunDays() {
        runDaysJoinTheRunDayOfEveryDoneRunInTheRange(sqlite());
    }

    @Test
    void postgresRunDays() {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ForecastMigrations.run(ds);
        runDaysJoinTheRunDayOfEveryDoneRunInTheRange(ds);
    }
```

Check the exact `finish(...)` signature in `JdbcRunStore` (line ~83) and adapt the argument order to it; the test above follows the current one: `finish(runId, champion, championMase, backtestJson, windows, days, factsJson, finishedAt)`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=JdbcRunStoreTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure (`runDays` missing).

- [ ] **Step 3: Implement the query**

In `JdbcRunStore`, next to `memberDays`:

```java
    /** Every day row of the team's DONE runs between two days inclusive, with the run day it was made on; by run, member, day. */
    public List<RunDayForecast> runDays(UUID teamId, LocalDate from, LocalDate to) {
        List<RunDayForecast> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.sql("SELECT d.run_id, r.as_of, d.user_id, d.day, d.window_index, d.open_hrs, d.new_hrs, d.planned_hrs, d.demand_hrs,"
                + " d.capacity_hrs, d.overload_hrs, d.working_day FROM forecast_member_days d JOIN forecast_runs r ON r.id = d.run_id"
                + " WHERE r.team_id = " + ph("uuid") + " AND r.status = ? AND d.day >= " + ph("date") + " AND d.day <= " + ph("date")
                + " ORDER BY d.run_id, d.user_id, d.day")
                .param(teamId.toString()).param(RunStatus.DONE.name()).param(from.toString()).param(to.toString()).query().listOfRows()) {
            out.add(new RunDayForecast(UUID.fromString(str(r, "run_id")), date(r, "as_of"),
                    new MemberDayForecast(UUID.fromString(str(r, "user_id")), date(r, "day"), (int) num(r, "window_index"), num(r, "open_hrs"),
                            num(r, "new_hrs"), num(r, "planned_hrs"), num(r, "demand_hrs"), num(r, "capacity_hrs"), num(r, "overload_hrs"),
                            dialect.asBoolean(r.get("working_day")))));
        }
        return out;
    }
```

Import `com.workloadhub.forecast.eval.RunDayForecast`. The `status` column is compared with a plain `?` as `create` does for the status parameter.

- [ ] **Step 4: Run the store test to verify it passes**

Run: as Step 2. Expected: PASS on SQLite; PostgreSQL skipped without Docker (a message), passes with Docker.

- [ ] **Step 5: Write the failing service test**

Add to `DefaultForecastServiceTest`:

```java
    @Test
    void accuracyComparesTheForecastsMadeBeforeEachWeekdayWithTheLoggedHours() {
        LocalDate wednesday = LocalDate.of(2026, 8, 19);
        RunResult r = service.runNow(new RunRequest(team, null, "seasonal_naive", null), wednesday);
        assertEquals(LocalDate.of(2026, 8, 20), r.memberDays().get(0).day());
        AccuracyResult acc = service.accuracy(team, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 2));
        assertEquals(SeededData.asOf(), acc.evaluatedAt(), "the clock's day");
        assertEquals(LocalDate.of(2026, 9, 2), acc.to());
        assertEquals(LocalDate.of(2026, 8, 20), acc.from());
        assertEquals(LocalDate.of(2026, 9, 5), service.accuracy(team, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30)).to(), "clamped to yesterday");
        AccuracyResult future = service.accuracy(team, LocalDate.of(2026, 9, 6), LocalDate.of(2026, 9, 30));
        assertTrue(future.current().isEmpty(), "nothing has passed yet");
        assertEquals(0, future.scores().get(0).n());
        int members = (int) r.memberDays().stream().map(d -> d.userId()).distinct().count();
        assertEquals(members * 10, acc.current().size(), "ten weekdays per member, 2026-08-20 to 2026-09-02, all in the past");
        assertTrue(acc.current().stream().allMatch(row -> row.runId().equals(r.run().id())));
        assertTrue(acc.current().stream().allMatch(row -> row.lead() >= 1 && row.lead() <= 10));
        assertTrue(acc.current().stream().anyMatch(row -> row.loggedHrs() > 0), "the seed logged hours in those weeks");
        AccuracyScore teamScore = acc.scores().get(0);
        assertEquals("team", teamScore.scope());
        assertEquals(members * 10, teamScore.n());
        assertTrue(teamScore.mae() >= 0);
        assertEquals(members, acc.scores().stream().filter(s -> s.scope().equals("member")).count());
        List<AccuracyScore> leads = acc.scores().stream().filter(s -> s.scope().equals("lead")).toList();
        assertEquals(10, leads.size());
        assertTrue(leads.stream().allMatch(s -> s.n() >= members), "every run in the range contributes to each lead");
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> service.accuracy(team, LocalDate.of(2026, 9, 2), LocalDate.of(2026, 8, 20))).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> service.accuracy(team, null, LocalDate.of(2026, 8, 20))).code());
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class, () -> service.accuracy(UUID.randomUUID(), LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 2))).code());
    }
```

Imports: `AccuracyResult`, `AccuracyScore`. Note: other tests in the class also run `runNow` for the same team (as of 2026-09-06 and 2026-09-02, whose days start on 2026-09-03 and later), which is why the range ends on 2026-09-02: inside it only this test's run exists, whatever the test order.

- [ ] **Step 6: Run it to verify it fails**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=DefaultForecastServiceTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure (`accuracy` missing on the service).

- [ ] **Step 7: Add the interface method and the service implementation**

`ForecastService`, after `currentForecast`:

```java
    /** How the forecasts made before each weekday between two days compared with the hours logged on it (design 2026-09-11). */
    AccuracyResult accuracy(UUID teamId, LocalDate from, LocalDate to);
```

`DefaultForecastService`, after `currentForecast`:

```java
    @Override
    public AccuracyResult accuracy(UUID teamId, LocalDate from, LocalDate to) {
        if (teamId == null || from == null || to == null) {
            throw ForecastException.invalidRequest("teamId, from and to are required");
        }
        if (from.isAfter(to)) {
            throw ForecastException.invalidRequest("from " + from + " is after to " + to);
        }
        requireTeam(teamId);
        LocalDate today = LocalDate.now(clock);
        LocalDate last = to.isBefore(today) ? to : today.minusDays(1);
        if (from.isAfter(last)) {
            return new AccuracyResult(teamId, from, last, today, List.of(), List.of(new AccuracyScore(Accuracy.TEAM, teamId.toString(), 0,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)));
        }
        ForecastData data = new ForecastRepository(JdbcClient.create(dataSource), dialect).loadAll();
        return Accuracy.evaluate(teamId, from, last, today, store.currentDays(teamId, from, last), store.runDays(teamId, from, last),
                Truth.realisedHoursByDay(data));
    }
```

Imports: `AccuracyResult`, `AccuracyScore`, `eval.Accuracy`, `eval.Truth`. Any other implementation of `ForecastService` in the tree (grep `implements ForecastService`) gets the method too; the sample host's facade wraps the service and does not implement it.

- [ ] **Step 8: Run the service test to verify it passes**

Run: as Step 6. Expected: PASS (the class takes about a minute).

- [ ] **Step 9: Full verify, then commit**

Run: `cd server && mvn -B -q verify`. Expected: exit 0.

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/api/ForecastService.java server/forecast-core/src/main/java/com/workloadhub/forecast/store/JdbcRunStore.java server/forecast-core/src/main/java/com/workloadhub/forecast/service/DefaultForecastService.java server/forecast-core/src/test/java/com/workloadhub/forecast/store/JdbcRunStoreTest.java server/forecast-core/src/test/java/com/workloadhub/forecast/service/DefaultForecastServiceTest.java
git commit -m "feat(server): the service reports a team's forecast accuracy over past weekdays" -m "One store query joins every finished run's day rows with its run day; the service clamps the range to yesterday and hands the pure computation the current rows, the run rows and the logged hours."
```

---

### Task 3: The report, the CLI command and the documents

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/AccuracyReport.java`, `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/AccuracyCommand.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/eval/AccuracyReportTest.java`, `server/forecast-cli/src/test/java/com/workloadhub/forecast/cli/RunCommandTest.java` (extend the existing scenario that already runs `run` and `current`)
- Modify: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/ForecastCli.java`, `server/README.md`, `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`, `docs/backlog.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: `AccuracyResult`, `AccuracyRow`, `AccuracyScore`, `Report.csv(double)` (package-private in `eval`, reuse it), `Services.open`, `TeamArg.resolve`, `RunCommand.JSON_MAPPER` (made package-private).
- Produces: `AccuracyReport.write(AccuracyResult result, Path outDir) -> Path` writing `accuracy.csv` and `summary.md`; CLI `accuracy`.

- [ ] **Step 1: Write the failing report test**

```java
package com.workloadhub.forecast.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccuracyReportTest {

    @Test
    void writesTheRowsAndTheScores(@TempDir Path dir) throws Exception {
        UUID team = UUID.fromString("40000000-0000-0000-0000-000000000001");
        UUID user = UUID.fromString("30000000-0000-0000-0000-000000000001");
        UUID run = UUID.fromString("50000000-0000-0000-0000-000000000001");
        AccuracyResult result = new AccuracyResult(team, LocalDate.of(2026, 8, 20), LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 6),
                List.of(new AccuracyRow(user, LocalDate.of(2026, 8, 20), run, 1, 8, 6.5, 8, false, false),
                        new AccuracyRow(user, LocalDate.of(2026, 8, 21), run, 2, 10, 9, 8, true, true)),
                List.of(new AccuracyScore("team", team.toString(), 2, 1.25, 1.25, 0.5, 1.0, 1.0),
                        new AccuracyScore("member", user.toString(), 2, 1.25, 1.25, Double.NaN, 1.0, 1.0),
                        new AccuracyScore("lead", "1", 1, 2.0, 2.0, 0.4, Double.NaN, Double.NaN)));
        AccuracyReport.write(result, dir);
        List<String> csv = Files.readAllLines(dir.resolve("accuracy.csv"));
        assertEquals("member_id,day,run_id,lead,forecast,truth,capacity,forecast_overload,actual_overload", csv.get(0));
        assertEquals(user + ",2026-08-20," + run + ",1,8,6.5,8,0,0", csv.get(1));
        assertEquals(user + ",2026-08-21," + run + ",2,10,9,8,1,1", csv.get(2));
        assertEquals(3, csv.size());
        String summary = Files.readString(dir.resolve("summary.md"));
        assertTrue(summary.startsWith("# Forecast accuracy, team " + team + ", 2026-08-20 to 2026-09-02, evaluated 2026-09-06"));
        assertTrue(summary.contains("Truth: " + Truth.SOURCE));
        assertTrue(summary.contains("| scope | key | n | mae | bias | mase | overload_precision | overload_recall |"));
        assertTrue(summary.contains("| team | " + team + " | 2 | 1.25 | 1.25 | 0.5 | 1 | 1 |"));
        assertTrue(summary.contains("| member | " + user + " | 2 | 1.25 | 1.25 |  | 1 | 1 |"), "NaN is an empty cell");
        assertTrue(summary.contains("| lead | 1 | 1 | 2 | 2 | 0.4 |  |  |"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd server && mvn -B -q -pl forecast-core -Dtest=AccuracyReportTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: compilation failure.

- [ ] **Step 3: Implement `AccuracyReport`**

```java
package com.workloadhub.forecast.eval;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyRow;
import com.workloadhub.forecast.api.AccuracyScore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** accuracy.csv (one row per member and day) and summary.md (the scores by scope). */
public final class AccuracyReport {

    static final String CSV_HEADER = "member_id,day,run_id,lead,forecast,truth,capacity,forecast_overload,actual_overload";
    static final String TABLE_HEADER = "| scope | key | n | mae | bias | mase | overload_precision | overload_recall |";

    private AccuracyReport() {
    }

    public static Path write(AccuracyResult result, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        StringBuilder csv = new StringBuilder(CSV_HEADER).append('\n');
        for (AccuracyRow r : result.current()) {
            csv.append(r.userId()).append(',').append(r.day()).append(',').append(r.runId()).append(',').append(r.lead()).append(',')
                    .append(Report.csv(r.forecastHrs())).append(',').append(Report.csv(r.loggedHrs())).append(',').append(Report.csv(r.capacityHrs()))
                    .append(',').append(r.forecastOverload() ? 1 : 0).append(',').append(r.actualOverload() ? 1 : 0).append('\n');
        }
        Files.writeString(outDir.resolve("accuracy.csv"), csv.toString(), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve("summary.md"), summary(result), StandardCharsets.UTF_8);
        return outDir;
    }

    static String summary(AccuracyResult result) {
        StringBuilder md = new StringBuilder();
        md.append("# Forecast accuracy, team ").append(result.teamId()).append(", ").append(result.from()).append(" to ").append(result.to())
                .append(", evaluated ").append(result.evaluatedAt()).append("\n\n");
        md.append("Truth: ").append(Truth.SOURCE).append(", summed per member and weekday; a weekday without a log counts as zero hours. Forecast: the")
                .append(" current forecast made before each day (team and member rows) and every finished run's day rows (lead rows; lead 1 is the")
                .append(" first weekday after the run day). MASE is against the same weekday one week earlier. Overload precision and recall compare")
                .append(" forecast overload with logged hours above capacity.\n\n");
        md.append(TABLE_HEADER).append('\n').append("|---|---|---|---|---|---|---|---|\n");
        for (AccuracyScore s : result.scores()) {
            md.append("| ").append(s.scope()).append(" | ").append(s.key()).append(" | ").append(s.n()).append(" | ").append(Report.csv(s.mae()))
                    .append(" | ").append(Report.csv(s.bias())).append(" | ").append(Report.csv(s.mase())).append(" | ")
                    .append(Report.csv(s.overloadPrecision())).append(" | ").append(Report.csv(s.overloadRecall())).append(" |\n");
        }
        return md.toString();
    }
}
```

`Report.csv` is `static String csv(double)` in `Report` (package-private): it prints integers without a decimal and NaN as an empty string, so `1.0` prints `1` and `NaN` prints nothing, as the test expects.

- [ ] **Step 4: Run the report test to verify it passes**

Run: as Step 2. Expected: PASS.

- [ ] **Step 5: Write the failing CLI assertions**

In `RunCommandTest`, in the test that already runs `run` then `current` on a seeded database (the one with `capture(cli, 0, "current", ...)` around line 65), append after the `current` assertions. The scenario's run is made `--as-of 2026-09-06`, whose days are all in the future, so run one more forecast as of 2026-08-19 first:

```java
        assertEquals(0, cli.execute("run", "--db", db.toString(), "--team", team, "--as-of", "2026-08-19", "--model", "seasonal_naive"));
        String accuracy = capture(cli, 0, "accuracy", "--db", db.toString(), "--team", team, "--from", "2026-08-20", "--to", "2026-09-02");
        assertTrue(accuracy.contains("team") && accuracy.contains("mae") && accuracy.contains("lead"), accuracy);
        Path out = dir.resolve("accuracy");
        assertEquals(0, cli.execute("accuracy", "--db", db.toString(), "--team", team, "--from", "2026-08-20", "--to", "2026-09-02", "--out", out.toString()));
        assertTrue(Files.readString(out.resolve("accuracy.csv")).startsWith("member_id,day,run_id,lead,forecast,truth"));
        assertTrue(Files.readString(out.resolve("summary.md")).startsWith("# Forecast accuracy, team "));
        String accuracyJson = capture(cli, 0, "accuracy", "--db", db.toString(), "--team", team, "--from", "2026-08-20", "--to", "2026-09-02", "--json");
        assertTrue(accuracyJson.contains("\"scores\"") && accuracyJson.contains("\"current\""), accuracyJson);
        assertEquals(2, cli.execute("accuracy", "--db", db.toString(), "--team", team, "--from", "2026-09-02", "--to", "2026-08-20"));
        assertEquals(2, cli.execute("accuracy", "--db", db.toString(), "--team", "no-such-team"));
```

`dir` is the test's `@TempDir` (check the method signature; if the scenario has no `Path dir`, add `@TempDir Path dir` to it or use `db.getParent()`). Add the `Files`/`Path` imports if missing. Note the CLI's `accuracy` uses `LocalDate.now()` as today, so the `--to` above (2026-09-02) is in the past relative to any real today; the test does not depend on the clamp.

- [ ] **Step 6: Run it to verify it fails**

Run: `cd server && mvn -B -q -pl forecast-cli -am -Dtest=RunCommandTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL (`accuracy` is an unknown command: picocli exits 2 and `capture(..., 0, ...)` asserts 0).

- [ ] **Step 7: Implement the command and register it**

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.AccuracyScore;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.eval.AccuracyReport;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "accuracy", description = "Compare the forecasts made before each past weekday with the logged hours: scores per team, member and lead.")
public class AccuracyCommand implements Callable<Integer> {

    static final int DEFAULT_DAYS = 20;

    @Mixin DbOptions db;

    @Option(names = "--team", required = true, description = "Team name or id")
    String team;

    @Option(names = "--from", description = "First day, ISO (default: yesterday - 20 days)")
    String from;

    @Option(names = "--to", description = "Last day, ISO (default: yesterday)")
    String to;

    @Option(names = "--out", description = "Write accuracy.csv and summary.md into this folder")
    Path out;

    @Option(names = "--json", description = "Print the result as JSON")
    boolean json;

    @Override
    public Integer call() throws Exception {
        try (Services s = Services.open(db.dataSource())) {
            UUID teamId;
            LocalDate first;
            LocalDate last;
            try {
                teamId = TeamArg.resolve(s.jdbc(), s.dialect(), team);
                LocalDate yesterday = LocalDate.now().minusDays(1);
                last = to == null ? yesterday : LocalDate.parse(to);
                first = from == null ? last.minusDays(DEFAULT_DAYS) : LocalDate.parse(from);
            } catch (IllegalArgumentException | DateTimeParseException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            AccuracyResult result;
            try {
                result = s.service().accuracy(teamId, first, last);
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return "INVALID_REQUEST".equals(e.code()) || "TEAM_NOT_FOUND".equals(e.code()) ? 2 : 1;
            }
            if (out != null) {
                AccuracyReport.write(result, out);
                System.err.println("wrote " + out.resolve("accuracy.csv") + " and " + out.resolve("summary.md"));
            }
            if (json) {
                System.out.println(RunCommand.JSON_MAPPER.writeValueAsString(result));
                return 0;
            }
            Map<UUID, String> names = new HashMap<>();
            s.jdbc().sql("SELECT id, full_name FROM users").query().listOfRows()
                    .forEach(row -> names.put(UUID.fromString(row.get("id").toString()), String.valueOf(row.get("full_name"))));
            System.out.println("accuracy of team " + team + ", " + result.from() + " to " + result.to() + ", " + result.current().size() + " member-days");
            System.out.printf("%-7s %-36s %5s %7s %7s %7s %10s %10s%n", "scope", "key", "n", "mae", "bias", "mase", "over_prec", "over_rec");
            for (AccuracyScore sc : result.scores()) {
                String key = sc.scope().equals("member") ? names.getOrDefault(UUID.fromString(sc.key()), sc.key()) : sc.key();
                System.out.printf("%-7s %-36s %5d %7s %7s %7s %10s %10s%n", sc.scope(), key, sc.n(), cell(sc.mae()), cell(sc.bias()), cell(sc.mase()),
                        cell(sc.overloadPrecision()), cell(sc.overloadRecall()));
            }
            return 0;
        }
    }

    static String cell(double v) {
        return Double.isNaN(v) ? "-" : String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
```

Register `AccuracyCommand.class` in `ForecastCli.Root`'s `subcommands`, after `CurrentCommand.class`. `RunCommand.JSON_MAPPER` (the mapper that writes a non-finite double as JSON `null`, so `--json` never contains `NaN`) is `private` today: make it package-private (`static final JsonMapper JSON_MAPPER`) and reuse it; add to the CLI test `assertFalse(accuracyJson.contains("NaN"), accuracyJson);` after the `--json` assertion (a lead or member score with no overload has NaN precision).

- [ ] **Step 8: Run the CLI test to verify it passes**

Run: as Step 6. Expected: PASS.

- [ ] **Step 9: Documents**

`server/README.md`:
- CLI table: a row after `current`: `| \`accuracy\` | \`--team <name or id> [--from] [--to] [--db] [--out dir] [--json]\` | Compares the forecasts made before each past weekday with the logged hours between \`--from\` (default: yesterday minus 20 days) and \`--to\` (default: yesterday; later dates are clamped): MAE, bias, MASE and overload precision and recall per team, per member and by lead (weekdays between the run day and the day). \`--out\` writes \`accuracy.csv\` and \`summary.md\`; \`--json\` prints the result. Exits 2 on a usage error, 1 on a failed call. |`
- Under "Integrating from the server's own code", one bullet after the "A run" bullets: `- **Accuracy**: \`accuracy(teamId, from, to)\` returns the current-forecast rows compared with the logged hours and the scores by team, member and lead; nothing is stored, every call recomputes. Show it to the roles that can view the team.`
- The command example block (`# 6. ...`): add `$CLI accuracy --team "Platform" --db ~/whf/workloadhub.db --from 2026-08-20 --to 2026-09-02 --out ~/whf/accuracy`.

`docs/superpowers/specs/2026-09-09-java-forecast-module-design.md`: after the existing amendment notes in section 11 (public API), a blockquote: `> Amended on 2026-09-11: \`accuracy(teamId, from, to)\` compares the forecasts made before each past weekday with the logged hours (accuracy evaluation design, 2026-09-11).` and after the section 12 CLI table's notes: `> Amended on 2026-09-11: \`accuracy --team ... [--from] [--to] [--out] [--json]\` (accuracy evaluation design).`

`docs/backlog.md`: replace the "Accuracy evaluation, design first." item with `- **Accuracy evaluation.** Built (2026-09-11, \`docs/superpowers/specs/2026-09-11-accuracy-evaluation-design.md\`): \`ForecastService.accuracy\` and the CLI \`accuracy\` command compare \`forecast_current_days\` and each run's day rows with the logged hours. Left open: a history of evaluations (a table and a chart) if the owner wants to see accuracy evolve; results are recomputed on every call today.` Under "## Java migration", add before the host-integration entry: `- **Accuracy evaluation (2026-09-11).** Rulings: no REST endpoint (the server calls the Java method); nothing stored; a weekday without a log counts as zero hours; MASE against the same weekday one week earlier; lead counts weekdays after the run day.`

`CLAUDE.md`: in the Layout block, the CLI command list adds `accuracy` after `current`; in "Where the project stands", after the host integration sentence: `Then the accuracy evaluation (\`docs/superpowers/specs/2026-09-11-accuracy-evaluation-design.md\`): \`accuracy(teamId, from, to)\` and the CLI \`accuracy\` compare the forecasts made before each past weekday with the logged hours.` and drop "then the accuracy evaluation" wherever the Next clause or the plans' "What comes next" list it (only in `CLAUDE.md`; the older plans stay as written).

- [ ] **Step 10: Full verify, then commit**

Run: `cd server && mvn -B -q verify`. Expected: exit 0.

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/eval/AccuracyReport.java server/forecast-core/src/test/java/com/workloadhub/forecast/eval/AccuracyReportTest.java server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/AccuracyCommand.java server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/ForecastCli.java server/forecast-cli/src/test/java/com/workloadhub/forecast/cli/RunCommandTest.java server/README.md docs/superpowers/specs/2026-09-09-java-forecast-module-design.md docs/backlog.md CLAUDE.md
git commit -m "feat(cli): an accuracy command and report, with the documents" -m "The owner reads the accuracy from the terminal or from two files; the README, the module design and the backlog record the feature and its rulings."
```

---

## Closing notes for the executor and the reviewer

**Rulings this plan takes, to report to the owner at the end:**

1. A current row's lead is found through its run's day rows (loaded for the same range), not through a new column; a run whose days are all outside the range cannot be current inside it.
2. MASE's naive value is the same member's logged hours seven days earlier. Corrected in the final fix wave: a row whose member has no log that day is left out of MASE alone, and the number of rows MASE was scored on is reported as `maseN`; the first implementation substituted zero hours, which made MASE depend on how sparse the logs are.
3. The `team` and `member` scores read the current forecast; the `lead` scores read every finished run's day rows, so a day counts once per run there.
4. The CLI's today is the system clock (`LocalDate.now()`), the service's today is its `Clock`; the CLI does not pass a clock, it passes the dates.
5. Corrected in the final fix wave: `accuracy.csv` prints numbers with `Report.csv` (a reader loads them as they are) and the markdown table with `Report.fmt` (three decimals and `nan`, so the columns line up).

**Rulings taken during execution and the final review (2026-09-11):**

6. MASE is scored over the rows whose member has a log entry on the same weekday one week earlier, and each
   score reports that count (`maseN`); a zero substitute for a missing prior-week log made MASE depend on how
   sparse the logs were (this reverses ruling 2). MASE here is daily and is named apart from the run's
   weekly MASE in the CLI, the summary and the README.
7. Public holidays (the working calendar is company-wide; a personal absence only zeroes the capacity and
   stays scored) are not scored in any scope and are counted in `AccuracyResult.nonWorkingDays`; a current
   row's run day and working-day flag come from its run's day row through a `(runId, userId, day)` map, and a
   current row without one is skipped.
8. The default range stays the last 20 days ending yesterday; the summary and the README say that the most
   recent days read low until hours are logged, that lead rows pool every finished run while team and
   member rows read the current forecast, and that `accuracy.csv` holds the current rows only.
9. The service test picks a team that logged hours in the range rather than the class's shared team, whose
   members had stopped logging before it.
10. The markdown summary formats with `Report.fmt`, the CSV with `Report.csv`; `RunCommand.JSON_MAPPER` also
    maps a primitive `double` NaN to JSON null; `Horizon.isWeekday` is the one definition of a weekday;
    the CLI's user-name lookup lives in `cli/Names`.
11. Follow-ups left in the backlog: the number of distinct runs behind the lead rows (a weekly run cadence
    confounds lead with weekday); a narrower truth load than `loadAll()` if the call ever shows in a profile.

**What comes next:** the live Copilot check on a seeded database, the real export through the seed and the parity procedure, and the server's own integration code in the WorkloadHub repository.
