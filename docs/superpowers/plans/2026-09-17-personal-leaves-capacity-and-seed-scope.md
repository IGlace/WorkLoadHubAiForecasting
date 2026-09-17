# Personal leaves, calendar-only capacity and the seed's scope: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read absence from `personal_leaves`, compute capacity from the calendar, the approved leaves and the 44 h default alone, apply the owner's feature-matrix rulings, and make the seed write only the five work tables in real mode.

**Architecture:** A new pure function `LeaveDays` turns leave rows into per-day absence hours; `CapacityRule` reads that and one default instead of `user_capacity`; `ForecastData` carries leaves instead of capacity and absence rows. The feature matrix drops one column, replaces three, adds one per horizon and leaves "nothing to measure" cells blank. The seed writes leaves only, marks the assigner on history rows, and in real mode returns a five-table envelope that the SQL writer lands with deletes and an upsert.

**Tech Stack:** Java 21, Maven 3.9, Spring Boot 4.1 (JdbcClient), JUnit 6, jqwik, SQLite (tests and experiments), PostgreSQL (Testcontainers when Docker is present).

**Spec:** `docs/superpowers/specs/2026-09-17-personal-leaves-capacity-and-seed-scope-design.md`

## Global Constraints

- The language model never produces a forecast number; every number it writes is verified against the facts. Nothing in this plan touches `NumberVerifier`.
- Demand is never capped by capacity; overload is reported.
- Test-driven development for every change; jqwik property tests for arithmetic invariants. No test talks to Copilot.
- The gate is `bash scripts/check.sh` (`cd server && mvn -B -q verify`), run by hand. CI is paused; never say "CI will catch it". Read totals from `forecast-core/target/surefire-reports/TEST-*.xml`.
- Capacity default: `whf.default-weekly-hours`, 44.0, unchanged. A full day is `default / 5` = 8.8 h.
- Only `APPROVED` leaves reduce capacity; `PENDING` ones are facts only; `REJECTED` and `CANCELLED` are never loaded.
- The `absences`, `user_capacity` and `team_capacity` tables stay in the schema files and in `WorkloadHubSchema.TABLE_ORDER`; the module never reads them and the seed never writes rows into them.
- The real export and any real-mode seed output stay outside the repository.
- Commit messages: imperative subject, short body explaining why. Every commit ends with the two attribution trailers of the session (`Co-Authored-By` and `Claude-Session`).
- Every text file stays LF.
- Run a single test class with `cd server && mvn -B -q -pl forecast-core test -Dtest=<ClassName> -Dsurefire.failIfNoSpecifiedTests=false`. The whole suite takes 13 to 17 minutes; run it once per task at the end, not per step.

---

### Task 1: `LeaveRow` and `LeaveDays`, the deal-out of a leave into absent days

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows/LeaveRow.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/capacity/LeaveDays.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/capacity/LeaveDaysTest.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/capacity/LeaveDaysPropertyTest.java`

**Interfaces:**
- Consumes: `WorkingCalendar.isWorkingDay(LocalDate)` and `WorkingCalendar.fromHolidays(List<HolidayRow>)` (existing, `calendar/WorkingCalendar.java`).
- Produces: `public record LeaveRow(UUID employeeId, LocalDate start, LocalDate end, LocalTime beginTime, LocalTime endTime, Double absenceHours, String status, String leaveType)` and `public static Map<UUID, NavigableMap<LocalDate, Double>> LeaveDays.expand(List<LeaveRow> leaves, WorkingCalendar cal, double fullDayHours)`. Task 2 reads both.

- [ ] **Step 1: Write the failing example tests**

```java
package com.workloadhub.forecast.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.LeaveRow;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LeaveDaysTest {

    static final UUID M = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final double FULL = 8.8;
    static final LocalDate MON = LocalDate.of(2026, 9, 7);
    static final LocalDate TUE = MON.plusDays(1);
    static final LocalDate WED = MON.plusDays(2);
    static final LocalDate THU = MON.plusDays(3);
    static final LocalDate FRI = MON.plusDays(4);
    // Thursday 10 September is a confirmed, active public holiday in this calendar.
    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of(new HolidayRow(THU, THU, true, true, "Test holiday")));
    static final WorkingCalendar PLAIN = WorkingCalendar.fromHolidays(List.of());

    static LeaveRow leave(LocalDate start, LocalDate end, Double hours, LocalTime begin, LocalTime endTime) {
        return new LeaveRow(M, start, end, begin, endTime, hours, "APPROVED", "PAID_LEAVE");
    }

    static NavigableMap<LocalDate, Double> days(LeaveRow l, WorkingCalendar cal) {
        Map<UUID, NavigableMap<LocalDate, Double>> out = LeaveDays.expand(List.of(l), cal, FULL);
        return out.getOrDefault(M, new java.util.TreeMap<>());
    }

    @Test
    void aWholeLeaveDealsAFullDayToEachWorkingDay() {
        assertEquals(Map.of(MON, 8.8, TUE, 8.8, WED, 8.8), days(leave(MON, WED, 26.4, null, null), PLAIN));
    }

    @Test
    void aLeaveEndingAtNoonLeavesTheRemainderOnItsLastDay() {
        assertEquals(Map.of(MON, 8.8, TUE, 8.8, WED, 4.4), days(leave(MON, WED, 22.0, null, LocalTime.of(12, 0)), PLAIN));
    }

    @Test
    void aLeaveStartingAtOneLeavesTheRemainderOnItsFirstDay() {
        assertEquals(Map.of(MON, 4.4, TUE, 8.8, WED, 8.8), days(leave(MON, WED, 22.0, LocalTime.of(13, 0), null), PLAIN));
    }

    @Test
    void weekendsAndHolidaysCostNothing() {
        LocalDate fri = LocalDate.of(2026, 9, 4);
        assertEquals(Map.of(fri, 8.8, MON, 8.8), days(leave(fri, MON, 17.6, null, null), PLAIN), "Saturday and Sunday are skipped");
        assertEquals(Map.of(WED, 8.8, FRI, 8.8), days(leave(WED, FRI, 17.6, null, null), CAL), "the Thursday holiday is skipped");
        assertTrue(days(leave(THU, THU, 8.8, null, null), CAL).isEmpty(), "a leave on the holiday alone contributes nothing");
    }

    @Test
    void aHalfDayOnOneDateAndAOneDayLeaveOverTwoDates() {
        assertEquals(Map.of(THU, 4.0), days(leave(THU, THU, 4.0, null, null), PLAIN));
        assertEquals(Map.of(THU, 8.8), days(leave(THU, FRI, 8.8, null, null), PLAIN), "the second day receives zero and is not an absent day");
    }

    @Test
    void nullOrNonPositiveHoursMeanTheWholePeriodAndTooManyHoursAreCapped() {
        assertEquals(Map.of(MON, 8.8, TUE, 8.8, WED, 8.8, THU, 8.8, FRI, 8.8), days(leave(MON, FRI, null, null, null), PLAIN));
        assertEquals(Map.of(MON, 8.8, TUE, 8.8), days(leave(MON, TUE, 0.0, null, null), PLAIN));
        assertEquals(Map.of(MON, 8.8, TUE, 8.8), days(leave(MON, TUE, 40.0, null, null), PLAIN), "capped at n × full day");
    }

    @Test
    void twoLeavesOnOneDayAddUpAndMembersAreKeptApart() {
        UUID other = UUID.fromString("30000000-0000-0000-0000-000000000003");
        Map<UUID, NavigableMap<LocalDate, Double>> out = LeaveDays.expand(List.of(
                leave(MON, MON, 4.0, null, null), leave(MON, MON, 2.0, null, null),
                new LeaveRow(other, TUE, TUE, null, null, null, "APPROVED", "SICK_LEAVE")), PLAIN, FULL);
        assertEquals(Map.of(MON, 6.0), out.get(M));
        assertEquals(Map.of(TUE, 8.8), out.get(other));
    }
}
```

- [ ] **Step 2: Run the test class to verify it fails to compile**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=LeaveDaysTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation error, `LeaveRow` and `LeaveDays` do not exist.

- [ ] **Step 3: Write `LeaveRow` and `LeaveDays`**

`server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows/LeaveRow.java`:

```java
package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One row of `personal_leaves`. {@code absenceHours} is the total over the whole leave as the application
 * records it, or null when it did not; {@code beginTime} and {@code endTime} mark a leave that starts or ends
 * on a partial day (design 2026-09-17, section 2).
 */
public record LeaveRow(UUID employeeId, LocalDate start, LocalDate end, LocalTime beginTime, LocalTime endTime,
        Double absenceHours, String status, String leaveType) {
}
```

`server/forecast-core/src/main/java/com/workloadhub/forecast/capacity/LeaveDays.java`:

```java
package com.workloadhub.forecast.capacity;

import com.workloadhub.forecast.Numbers;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.rows.LeaveRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Absence hours per member and day, from leaves (design 2026-09-17, section 2.2): a leave covers its working
 * days only; its {@code absence_hours} total is dealt one full day at a time from the first day forward, the
 * last day taking what is left — or from the last day backward when only {@code begin_time} is set, so the
 * partial day is the first one. A null or non-positive total means the whole period. A day dealt zero hours
 * is not an absent day.
 */
public final class LeaveDays {

    private LeaveDays() {
    }

    public static Map<UUID, NavigableMap<LocalDate, Double>> expand(List<LeaveRow> leaves, WorkingCalendar cal, double fullDayHours) {
        Map<UUID, NavigableMap<LocalDate, Double>> out = new HashMap<>();
        for (LeaveRow l : leaves) {
            List<LocalDate> days = new ArrayList<>();
            for (LocalDate d = l.start(); !d.isAfter(l.end()); d = d.plusDays(1)) {
                if (cal.isWorkingDay(d)) {
                    days.add(d);
                }
            }
            if (days.isEmpty()) {
                continue;
            }
            double[] hours = dealOut(days.size(), l.absenceHours(), fullDayHours, l.beginTime() != null && l.endTime() == null);
            NavigableMap<LocalDate, Double> byDay = out.computeIfAbsent(l.employeeId(), k -> new TreeMap<>());
            for (int i = 0; i < days.size(); i++) {
                if (hours[i] > 0) {
                    byDay.merge(days.get(i), hours[i], Double::sum);
                }
            }
        }
        out.replaceAll((k, v) -> {
            v.replaceAll((d, h) -> Numbers.round2(h));
            return v;
        });
        return out;
    }

    /** Hours per working day, in day order. */
    static double[] dealOut(int n, Double total, double fullDay, boolean partialFirstDay) {
        double[] out = new double[n];
        if (total == null || total <= 0) {
            java.util.Arrays.fill(out, fullDay);
            return out;
        }
        double left = Math.min(total, n * fullDay);
        for (int k = 0; k < n; k++) {
            int i = partialFirstDay ? n - 1 - k : k;
            double h = Math.min(fullDay, left);
            out[i] = h;
            left -= h;
        }
        return out;
    }
}
```

`Numbers.round2` exists in `com.workloadhub.forecast.Numbers` (root package) and rounds to two decimals; 8.8 and 4.4 survive it unchanged.

- [ ] **Step 4: Run the example tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=LeaveDaysTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, 7 tests.

- [ ] **Step 5: Write the property test**

`server/forecast-core/src/test/java/com/workloadhub/forecast/capacity/LeaveDaysPropertyTest.java` (the `*PropertyTest` suffix is what surefire's include picks up for jqwik files; keep it):

```java
package com.workloadhub.forecast.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.LeaveRow;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;

class LeaveDaysPropertyTest {

    static final UUID M = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final double FULL = 8.8;
    static final LocalDate BASE = LocalDate.of(2026, 8, 31);
    static final WorkingCalendar CAL = WorkingCalendar.fromHolidays(List.of(
            new HolidayRow(LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 10), true, true, "Holiday")));

    @Property(tries = 300)
    void hoursLandOnWorkingDaysInsideThePeriodAndSumToTheDealtTotal(
            @ForAll @IntRange(min = 0, max = 30) int startOffset, @ForAll @IntRange(min = 0, max = 14) int length,
            @ForAll @DoubleRange(min = -5, max = 120) double total, @ForAll boolean hoursKnown,
            @ForAll @IntRange(min = 0, max = 2) int times) {
        LocalDate start = BASE.plusDays(startOffset);
        LocalDate end = start.plusDays(length);
        LocalTime begin = times == 1 ? LocalTime.of(13, 0) : null;
        LocalTime finish = times == 2 ? LocalTime.of(12, 0) : null;
        LeaveRow l = new LeaveRow(M, start, end, begin, finish, hoursKnown ? total : null, "APPROVED", "PAID_LEAVE");
        NavigableMap<LocalDate, Double> days = LeaveDays.expand(List.of(l), CAL, FULL).getOrDefault(M, new TreeMap<>());
        int n = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (CAL.isWorkingDay(d)) {
                n++;
            }
        }
        for (Map.Entry<LocalDate, Double> e : days.entrySet()) {
            assertTrue(CAL.isWorkingDay(e.getKey()) && !e.getKey().isBefore(start) && !e.getKey().isAfter(end), "day " + e.getKey());
            assertTrue(e.getValue() > 0 && e.getValue() <= FULL + 1e-9, "hours " + e.getValue());
        }
        double expected = (!hoursKnown || total <= 0) ? n * FULL : Math.min(total, n * FULL);
        assertEquals(expected, days.values().stream().mapToDouble(Double::doubleValue).sum(), 0.02, "total dealt");
        double remainder = hoursKnown && total > 0 && total < n * FULL - 1e-9 ? total - Math.floor(total / FULL) * FULL : 0.0;
        if (remainder > 1e-6 && n > 1) {
            // a total that is an exact number of full days has no partial day at all (the last day gets zero and is dropped)
            LocalDate partial = times == 1 ? days.firstKey() : days.lastKey();
            assertTrue(days.get(partial) < FULL, "the partial day is the first with begin_time only, else the last");
        }
    }
}
```

- [ ] **Step 6: Run both classes**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='LeaveDaysTest,LeaveDaysPropertyTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If the property finds a counterexample, the bug is in `dealOut`, not in the property: fix the code.

- [ ] **Step 7: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows/LeaveRow.java server/forecast-core/src/main/java/com/workloadhub/forecast/capacity/LeaveDays.java server/forecast-core/src/test/java/com/workloadhub/forecast/capacity/LeaveDaysTest.java server/forecast-core/src/test/java/com/workloadhub/forecast/capacity/LeaveDaysPropertyTest.java
git commit -m "feat(capacity): deal a personal leave out into its absent working days"
```

---

### Task 2: leaves in, capacity rows out: `ForecastData`, the repository, `Truncation`, `CapacityRule`

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastData.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastRepository.java:113-129`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Truncation.java:50-52`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/capacity/CapacityRule.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/run/ForecastRunner.java:183`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/facts/FactsBuilder.java:145-156` (the `team_capacity` block goes; the rest of the facts is Task 6)
- Delete: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows/AbsenceRow.java`, `CapacityRow.java`, `TeamCapacityRow.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/testing/TestData.java:63-66`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/capacity/CapacityRuleTest.java` (rewritten)
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/data/ForecastRepositoryTest.java:62-87`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/facts/FactsBuilderTest.java` (only if it names `team_capacity`; grep first)
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/FactsToolsTest.java` (only if it names `team_capacity`; grep first)

**Interfaces:**
- Consumes: `LeaveRow`, `LeaveDays.expand` (Task 1).
- Produces: `ForecastData(members, teams, projects, tasks, transitions, timeLogs, List<LeaveRow> leaves, List<LeaveRow> pendingLeaves, holidays, users, statusCategoryByName)`; `ForecastData.leaves()`, `ForecastData.pendingLeaves()`; `CapacityRule.offDays(UUID member, ForecastData data, WorkingCalendar cal)` (the `MemberRow` argument is gone); `CapacityRule.dayAbsenceHours(UUID, LocalDate, ForecastData)`, `absenceHours(UUID, LocalDate monday, ForecastData, WorkingCalendar)`, `capacity(MemberRow, LocalDate monday, ForecastData, WorkingCalendar)`, `dayCapacity(MemberRow, LocalDate, ForecastData, WorkingCalendar)` keep their signatures. `ForecastData.capacity()`, `absences()`, `teamCapacity()`, `withTeamCapacity()` no longer exist.

- [ ] **Step 1: Rewrite `CapacityRuleTest` against the new rule (it will not compile yet)**

Replace the whole file with:

```java
package com.workloadhub.forecast.capacity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.LeaveRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.UserRef;
import com.workloadhub.forecast.testing.SeededData;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class CapacityRuleTest {

    static final UUID M = UUID.fromString("30000000-0000-0000-0000-000000000002");
    static final UUID T = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final LocalDate MON = LocalDate.of(2026, 4, 27);   // the week of Labour Day (Friday 1 May): four working days

    static ForecastData data(List<LeaveRow> leaves) {
        MemberRow m = new MemberRow(M, "Eng Two", "e@example.test", "MEMBER", "Calibration Engineer", List.of(T), T, LocalDate.of(2026, 1, 5), null);
        return new ForecastData(List.of(m), List.of(new TeamRow(T, "CT2 · X", null, null)), List.of(), List.of(), List.of(), List.of(),
                leaves, List.of(), List.of(new HolidayRow(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1), true, true, "Labour Day")),
                List.of(new UserRef(M, "Eng Two", "e@example.test", "eng")), Map.of());
    }

    static LeaveRow leave(LocalDate start, LocalDate end, Double hours, LocalTime begin, LocalTime finish) {
        return new LeaveRow(M, start, end, begin, finish, hours, "APPROVED", "PAID_LEAVE");
    }

    @Test
    void capacityIsTheDefaultOverTheWeeksWorkingDaysMinusLeaveHours() {
        ForecastData d = data(List.of(leave(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 4, 28), 8.8, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(44.0);
        assertEquals(44 * 4 / 5.0 - 8.8, rule.capacity(d.members().get(0), MON, d, cal), 1e-9, "four working days, one on leave");
        assertEquals(8.8, rule.absenceHours(M, MON, d, cal), 1e-9);
        ForecastData none = data(List.of());
        assertEquals(44.0, rule.capacity(none.members().get(0), LocalDate.of(2026, 3, 16), none, cal), 1e-9);
        assertEquals(0.0, rule.absenceHours(M, LocalDate.of(2026, 3, 16), none, cal), 1e-9);
    }

    @Test
    void capacityNeverGoesBelowZero() {
        ForecastData d = data(List.of(leave(MON, LocalDate.of(2026, 5, 1), null, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        assertEquals(0.0, new CapacityRule(44.0).capacity(d.members().get(0), MON, d, cal), 1e-9, "the whole week on leave");
    }

    @Test
    void dayCapacityIsZeroOffWorkingDaysAndTheDefaultOverFiveMinusTheDaysLeave() {
        ForecastData d = data(List.of(
                leave(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 4, 28), 4.0, null, null),
                leave(LocalDate.of(2026, 4, 29), LocalDate.of(2026, 4, 29), null, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(44.0);
        MemberRow m = d.members().get(0);
        assertEquals(8.8, rule.dayCapacity(m, MON, d, cal), 1e-9, "44 h over five days");
        assertEquals(4.8, rule.dayCapacity(m, LocalDate.of(2026, 4, 28), d, cal), 1e-9, "minus the 4 h of leave that day");
        assertEquals(0.0, rule.dayCapacity(m, LocalDate.of(2026, 4, 29), d, cal), 1e-9, "a whole day of leave");
        assertEquals(0.0, rule.dayCapacity(m, LocalDate.of(2026, 5, 1), d, cal), 1e-9, "Labour Day");
        assertEquals(0.0, rule.dayCapacity(m, LocalDate.of(2026, 5, 2), d, cal), 1e-9, "Saturday");
        assertEquals(4.0, rule.dayAbsenceHours(M, LocalDate.of(2026, 4, 28), d), 1e-9);
        assertEquals(8.8, rule.dayAbsenceHours(M, LocalDate.of(2026, 4, 29), d), 1e-9);
        assertEquals(0.0, rule.dayAbsenceHours(M, MON, d), 1e-9);
    }

    @Test
    void aHalfDayLeaveIsNotAnOffDayAndAWholeDayIs() {
        ForecastData d = data(List.of(
                leave(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 4, 28), 4.0, null, null),
                leave(LocalDate.of(2026, 4, 29), LocalDate.of(2026, 4, 30), 17.6, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        assertEquals(Set.of(LocalDate.of(2026, 4, 29), LocalDate.of(2026, 4, 30)), new CapacityRule(44.0).offDays(M, d, cal));
    }

    @Test
    void twoLeavesOnOneDayAreCappedAtAFullDay() {
        ForecastData d = data(List.of(
                leave(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 4, 28), 6.0, null, null),
                leave(LocalDate.of(2026, 4, 28), LocalDate.of(2026, 4, 28), 6.0, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(44.0);
        assertEquals(8.8, rule.dayAbsenceHours(M, LocalDate.of(2026, 4, 28), d), 1e-9, "12 h of leave on one day count as the whole day");
        assertEquals(Set.of(LocalDate.of(2026, 4, 28)), rule.offDays(M, d, cal));
        assertEquals(44 * 4 / 5.0 - 8.8, rule.capacity(d.members().get(0), MON, d, cal), 1e-9);
    }

    @Test
    void aPendingLeaveDoesNotReduceCapacity() {
        MemberRow m = new MemberRow(M, "Eng Two", "e@example.test", "MEMBER", "Calibration Engineer", List.of(T), T, LocalDate.of(2026, 1, 5), null);
        LeaveRow pending = new LeaveRow(M, LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 20), 44.0, null, null, "PENDING", "PAID_LEAVE");
        ForecastData d = new ForecastData(List.of(m), List.of(new TeamRow(T, "CT2 · X", null, null)), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(pending), List.of(), List.of(new UserRef(M, "Eng Two", "e@example.test", "eng")), Map.of());
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        assertEquals(44.0, new CapacityRule(44.0).capacity(m, LocalDate.of(2026, 3, 16), d, cal), 1e-9);
        assertEquals(1, d.pendingLeaves().size());
    }

    @Test
    void seededLeavesReduceSomeWeeksAndNeverBelowZero() {
        ForecastData d = SeededData.data();
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        CapacityRule rule = new CapacityRule(44.0);
        int reduced = 0;
        for (LeaveRow l : d.leaves()) {
            MemberRow m = d.memberById().get(l.employeeId());
            if (m == null) {
                continue;
            }
            LocalDate monday = com.workloadhub.forecast.calendar.Weeks.mondayOf(l.start());
            double c = rule.capacity(m, monday, d, cal);
            assertTrue(c >= 0 && c <= 44.0 + 1e-9, "capacity " + c);
            if (c < 44 * cal.workingDaysInWeek(monday) / 5.0 - 1e-9) {
                reduced++;
            }
        }
        assertTrue(reduced > 20, "leaves reduce weeks: " + reduced);
    }

    @Property
    boolean dayCapacityStaysBetweenZeroAndTheDefaultOverFive(@ForAll @DoubleRange(min = 0, max = 24) double hours, @ForAll @IntRange(min = 0, max = 13) int offset) {
        LocalDate day = LocalDate.of(2026, 4, 20).plusDays(offset);
        ForecastData d = data(List.of(leave(day, day, hours, null, null)));
        WorkingCalendar cal = WorkingCalendar.fromHolidays(d.holidays());
        double c = new CapacityRule(44.0).dayCapacity(d.members().get(0), day, d, cal);
        return c >= 0 && c <= 8.8 + 1e-9 && (cal.isWorkingDay(day) || c == 0.0);
    }
}
```

- [ ] **Step 2: Change `ForecastData`**

In `ForecastData.java`: remove the three imports (`AbsenceRow`, `CapacityRow`, `TeamCapacityRow`) and add `import com.workloadhub.forecast.data.rows.LeaveRow;`. Replace the fields `capacity`, `absences` and `teamCapacity` with:

```java
    private final List<LeaveRow> leaves;
    private final List<LeaveRow> pendingLeaves;
```

Replace the constructor signature and the two sort blocks:

```java
    public ForecastData(List<MemberRow> members, List<TeamRow> teams, List<ProjectRow> projects, List<TaskRow> tasks,
            List<TransitionRow> transitions, List<TimeLogRow> timeLogs, List<LeaveRow> leaves, List<LeaveRow> pendingLeaves,
            List<HolidayRow> holidays, List<UserRef> users, Map<String, String> statusCategoryByName) {
        ...
        this.leaves = sortedLeaves(leaves);
        this.pendingLeaves = sortedLeaves(pendingLeaves);
        ...
        (delete the line `this.teamCapacity = List.of();`)
    }

    private static List<LeaveRow> sortedLeaves(List<LeaveRow> rows) {
        return List.copyOf(rows.stream()
                .sorted(Comparator.comparing(LeaveRow::employeeId, Ids.UUID_ORDER).thenComparing(LeaveRow::start).thenComparing(LeaveRow::end))
                .toList());
    }
```

Replace `withProjects` and delete `withTeamCapacity`:

```java
    public ForecastData withProjects(List<ProjectRow> projects) {
        return new ForecastData(members, teams, projects, tasks, transitions, timeLogs, leaves, pendingLeaves, holidays, users,
                statusCategoryByName);
    }
```

Replace the accessors `capacity()`, `absences()` and `teamCapacity()` with:

```java
    /** The APPROVED personal leaves, by member then start date. Only these reduce capacity. */
    public List<LeaveRow> leaves() {
        return leaves;
    }

    /** The PENDING personal leaves, for the facts only; they never reduce capacity. */
    public List<LeaveRow> pendingLeaves() {
        return pendingLeaves;
    }
```

- [ ] **Step 3: Change `ForecastRepository.loadAll`**

Replace the `capacity`, `absences` and `teamCapacity` loops and the return statement (lines 113 to 129) with:

```java
        List<LeaveRow> leaves = new ArrayList<>();
        List<LeaveRow> pendingLeaves = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT employee_id, start_date, end_date, begin_time, end_time, absence_hours, status, leave_type"
                + " FROM personal_leaves WHERE status IN ('APPROVED', 'PENDING')")) {
            LeaveRow row = new LeaveRow(uuid(r, "employee_id"), date(r, "start_date"), date(r, "end_date"), time(r, "begin_time"),
                    time(r, "end_time"), dbl(r, "absence_hours"), str(r, "status"), str(r, "leave_type"));
            ("APPROVED".equals(row.status()) ? leaves : pendingLeaves).add(row);
        }
        List<HolidayRow> holidays = new ArrayList<>();
        for (Map<String, Object> r : rows("SELECT start_date, end_date, status, active, title FROM holidays")) {
            holidays.add(new HolidayRow(date(r, "start_date"), date(r, "end_date"), "CONFIRMED".equals(str(r, "status")),
                    dialect.asBoolean(r.get("active")), str(r, "title")));
        }
        return new ForecastData(members, teams, projects, tasks, transitions, logs, leaves, pendingLeaves, holidays, users, categoryByName);
```

Add the helper beside `date` and `dateTime` (the SQLite driver hands a `TEXT` time back as a `String`, PostgreSQL as `java.sql.Time` or `LocalTime`):

```java
    static LocalTime time(Map<String, Object> r, String col) {
        Object v = r.get(col);
        if (v == null) {
            return null;
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime();
        }
        if (v instanceof LocalTime t) {
            return t;
        }
        return LocalTime.parse(v.toString().length() > 8 ? v.toString().substring(0, 8) : v.toString());
    }
```

Fix the imports: add `LeaveRow` and `java.time.LocalTime`, remove `AbsenceRow`, `CapacityRow` and `TeamCapacityRow`. `dbl(r, col)` already returns a `Double` that is `null` for a null cell (`ForecastRepository.java:146`), which is what `absenceHours` needs.

- [ ] **Step 4: Change `Truncation`**

Line 50 to 52 become:

```java
        return new ForecastData(data.members(), data.teams(), data.projects(), tasks, keptTransitions, keptLogs, data.leaves(),
                data.pendingLeaves(), data.holidays(), data.users(), data.statusCategoryByName());
```

- [ ] **Step 5: Change `TestData.data`**

```java
        return new ForecastData(members, teams, List.of(), tasks, transitions, logs, List.of(), List.of(), List.of(), users, STATUS_CATEGORIES);
```

- [ ] **Step 6: Rewrite `CapacityRule`**

Replace the whole class body with the version below (the class Javadoc stays; update it to say the rule reads leaves and one default):

```java
public final class CapacityRule {

    public static final int WORKING_DAYS_PER_WEEK = 5;

    private final double defaultWeeklyHours;

    /**
     * One index per {@link ForecastData}, keyed by instance identity: the leaves are expanded once per data set
     * here instead of once per call, which FeatureBuilder makes on the order of member-weeks × horizons.
     */
    private final Map<ForecastData, Map<UUID, NavigableMap<LocalDate, Double>>> indexes = new IdentityHashMap<>();

    public CapacityRule(double defaultWeeklyHours) {
        this.defaultWeeklyHours = defaultWeeklyHours;
    }

    /** A working day's hours before any leave: the default week over five days (design 2026-09-17, section 3). */
    double fullDay() {
        return defaultWeeklyHours / WORKING_DAYS_PER_WEEK;
    }

    private Map<UUID, NavigableMap<LocalDate, Double>> indexFor(ForecastData data) {
        return indexes.computeIfAbsent(data, d -> LeaveDays.expand(d.leaves(), WorkingCalendar.fromHolidays(d.holidays()), fullDay()));
    }

    /** The member's leave hours on the week's working days. */
    public double absenceHours(UUID member, LocalDate monday, ForecastData data, WorkingCalendar cal) {
        NavigableMap<LocalDate, Double> byDay = indexFor(data).get(member);
        if (byDay == null || byDay.isEmpty()) {
            return 0.0;
        }
        double sum = 0;
        for (Map.Entry<LocalDate, Double> e : byDay.subMap(monday, true, monday.plusDays(6), true).entrySet()) {
            if (cal.isWorkingDay(e.getKey())) {
                sum += Math.min(fullDay(), e.getValue());
            }
        }
        return Numbers.round2(sum);
    }

    /** The default week over the week's working days, minus the member's leave hours in it, never below zero. */
    public double capacity(MemberRow member, LocalDate monday, ForecastData data, WorkingCalendar cal) {
        double hours = defaultWeeklyHours * cal.workingDaysInWeek(monday) / WORKING_DAYS_PER_WEEK - absenceHours(member.id(), monday, data, cal);
        return Numbers.round2(Math.max(0.0, hours));
    }

    /** Nothing on a weekend or holiday; else the default over five days minus that day's leave hours, never below zero. */
    public double dayCapacity(MemberRow member, LocalDate day, ForecastData data, WorkingCalendar cal) {
        if (!cal.isWorkingDay(day)) {
            return 0.0;
        }
        return Numbers.round2(Math.max(0.0, fullDay() - dayAbsenceHours(member.id(), day, data)));
    }

    /** The member's leave hours on that day, capped at a full day. */
    public double dayAbsenceHours(UUID member, LocalDate day, ForecastData data) {
        NavigableMap<LocalDate, Double> byDay = indexFor(data).get(member);
        return byDay == null ? 0.0 : Numbers.round2(Math.min(fullDay(), byDay.getOrDefault(day, 0.0)));
    }

    /**
     * The days a member is fully absent: their leave hours meet a full day. Its caller is the weekday split in
     * {@code ForecastRunner}: predicted hours must not land on a day the member is not there (ruling 18.4 of the
     * 2026-09-13 design). Judged against the full day, never against {@link #dayCapacity}, which has already
     * taken the leave off.
     */
    public Set<LocalDate> offDays(UUID member, ForecastData data, WorkingCalendar cal) {
        Set<LocalDate> out = new TreeSet<>();
        NavigableMap<LocalDate, Double> byDay = indexFor(data).get(member);
        if (byDay == null) {
            return out;
        }
        for (Map.Entry<LocalDate, Double> e : byDay.entrySet()) {
            if (cal.isWorkingDay(e.getKey()) && e.getValue() >= fullDay() - 1e-9) {
                out.add(e.getKey());
            }
        }
        return out;
    }
}
```

Imports needed: `com.workloadhub.forecast.Numbers`, `com.workloadhub.forecast.calendar.WorkingCalendar`, `com.workloadhub.forecast.data.ForecastData`, `com.workloadhub.forecast.data.rows.MemberRow`, `java.time.LocalDate`, `java.util.IdentityHashMap`, `java.util.Map`, `java.util.NavigableMap`, `java.util.Set`, `java.util.TreeSet`, `java.util.UUID`. Remove the `Optional`, `HashMap`, `TreeMap`, `AbsenceRow`, `CapacityRow`, `Weeks` imports if nothing else uses them.

- [ ] **Step 7: Fix the two callers and the facts block**

`ForecastRunner.java:183` becomes `offDaysByMember.put(m.id(), capacityRule.offDays(m.id(), data, p.calendar()));`.

`FactsBuilder.team(...)`: delete the `horizonWeeks` set, the `teamCapacity` list and its loop (lines 145 to 154), the `TeamCapacityRow` import, and the trailing `, "team_capacity", teamCapacity` of the returned map. If `Horizon`, `TreeSet` or `Weeks` become unused imports in that file, remove them.

Delete the three row files: `AbsenceRow.java`, `CapacityRow.java`, `TeamCapacityRow.java`.

- [ ] **Step 8: Update `ForecastRepositoryTest`**

Replace `holidaysCapacityAbsencesAndProjectsArePresent` with:

```java
    @Test
    void holidaysLeavesAndProjectsArePresentAndTheCapacityTablesAreNeverRead() {
        ForecastData data = SeededData.data();
        assertTrue(data.holidays().stream().anyMatch(h -> h.confirmed() && h.active()));
        long approved = SeededData.envelope().rows("personal_leaves").stream().filter(l -> "APPROVED".equals(l.get("status"))).count();
        long pending = SeededData.envelope().rows("personal_leaves").stream().filter(l -> "PENDING".equals(l.get("status"))).count();
        assertEquals(approved, data.leaves().size());
        assertEquals(pending, data.pendingLeaves().size());
        assertTrue(data.leaves().stream().allMatch(l -> "APPROVED".equals(l.status()) && l.absenceHours() != null && l.absenceHours() > 0));
        assertEquals(SeededData.envelope().rows("projects").size(), data.projects().size());
        MemberRow m = data.members().get(0);
        assertFalse(data.projectIdsOfTeamAndParent(m.primaryTeamId()).isEmpty());
        assertEquals(SeededData.envelope().rows("users").size(), data.users().size());
        for (int i = 1; i < data.leaves().size(); i++) {
            var prev = data.leaves().get(i - 1);
            var cur = data.leaves().get(i);
            int cmp = prev.employeeId().toString().compareTo(cur.employeeId().toString());
            assertTrue(cmp < 0 || (cmp == 0 && !cur.start().isBefore(prev.start())), "leaves out of order at " + i);
        }
        for (int i = 1; i < data.holidays().size(); i++) {
            var prev = data.holidays().get(i - 1);
            var cur = data.holidays().get(i);
            int cmp = prev.start().compareTo(cur.start());
            assertTrue(cmp < 0 || (cmp == 0 && !cur.end().isBefore(prev.end())), "holidays out of order at " + i);
        }
        // The three tables the module no longer reads: drop them and the load must still succeed.
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ExportImporter(ds).importAll(SeededData.envelope(), true);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TABLE user_capacity");
            st.execute("DROP TABLE team_capacity");
            st.execute("DROP TABLE absences");
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        ForecastData without = new ForecastRepository(JdbcClient.create(ds), Dialect.of(ds)).loadAll();
        assertEquals(data.leaves().size(), without.leaves().size());
    }

    @Test
    void rejectedAndCancelledLeavesAreNotLoadedAndTimesAreParsed() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ExportImporter(ds).importAll(SeededData.envelope(), true);
        String member = SeededData.envelope().rows("users").get(0).get("id").toString();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM personal_leaves");
            for (String status : List.of("APPROVED", "PENDING", "REJECTED", "CANCELLED")) {
                st.execute("INSERT INTO personal_leaves (id, employee_id, start_date, end_date, begin_time, end_time, absence_hours, status, leave_type)"
                        + " VALUES ('" + UUID.randomUUID() + "', '" + member + "', '2026-09-07', '2026-09-09', '13:00:00', NULL, 22.0, '" + status + "', 'PAID_LEAVE')");
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        ForecastData data = new ForecastRepository(JdbcClient.create(ds), Dialect.of(ds)).loadAll();
        assertEquals(1, data.leaves().size());
        assertEquals(1, data.pendingLeaves().size());
        assertEquals(java.time.LocalTime.of(13, 0), data.leaves().get(0).beginTime());
        assertEquals(22.0, data.leaves().get(0).absenceHours());
        assertEquals("PAID_LEAVE", data.leaves().get(0).leaveType());
    }
```

Add the imports: `com.workloadhub.forecast.store.DatabaseTestSupport`, `com.workloadhub.forecast.store.Dialect`, `com.workloadhub.forecast.store.WorkloadHubSchema`, `java.sql.Connection`, `java.sql.SQLException`, `java.sql.Statement`, `javax.sql.DataSource`, `org.springframework.jdbc.core.simple.JdbcClient`. The seed does not write `PENDING` rows until Task 3, so `pending` is 0 here and the assertion holds either way.

- [ ] **Step 9: Grep for the last references and compile**

Run: `grep -rn "CapacityRow\|AbsenceRow\|TeamCapacityRow\|withTeamCapacity\|\.absences()\|\.capacity()\|teamCapacity()\|team_capacity" server/forecast-core/src/main server/forecast-core/src/test server/examples server/tools | grep -v "/target/"`
Expected: hits only in `seed/` (the seed is Task 3: `CapacityWriter`, `SeedGenerator`, `AbsencePlanner`, `SeedGeneratorTest`, `CapacityWriterTest`, `AbsencePlannerTest`) and in `Experiment.java`'s summary list. The seed compiles because it builds rows as maps and never touches the deleted records; if `CapacityWriter` or a seed test references a deleted class, leave it to Task 3 only if it still compiles, otherwise fix the reference now.
`FactsBuilderTest` and `FactsToolsTest`: if either asserts on `team_capacity`, delete that assertion.

Run: `cd server && mvn -B -q -pl forecast-core test-compile`
Expected: compiles.

- [ ] **Step 10: Run the touched test classes, then the whole suite**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='CapacityRuleTest,ForecastRepositoryTest,TruncationTest,ForecastRunnerTest,FeatureBuilderTest,FactsBuilderTest,FactsToolsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `FeatureBuilderTest.availabilityIdentityAndTenure` asserts `available_hrs_h3 == 40.0` with `new CapacityRule(40)`: still true (40 × 5 / 5 − 0). `ForecastRunnerTest` cases that used `CapacityRow` fixtures, if any, are rewritten with a `LeaveRow` of the same effect.

Then: `cd server && mvn -B -q verify` (the gate). Expected: green apart from the seed tests that Task 3 rewrites; if `SeedGeneratorTest` or `CapacityWriterTest` fail here only because they read `user_capacity` rows the seed still writes, they still pass at this point since the seed is untouched. Record the totals.

- [ ] **Step 11: Commit**

```bash
git add -A server/forecast-core/src
git commit -m "feat(data): read personal leaves, compute capacity from the calendar and the default"
```

---

### Task 3a: the seed writes leaves only, marks the assigner, and no capacity rows

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/AbsencePlanner.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/SeedGenerator.java:206-282`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/WorkQueue.java:368`
- Delete: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/CapacityWriter.java`, `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/CapacityWriterTest.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/AbsencePlannerTest.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/SeedGeneratorTest.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkFamilyPropertyTest.java` (uses `CapacityWriter.BASE_HOURS`)
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/SeedGeneratorTest.java:96` and `:181-201` (`capacityRowsAreConsistent` goes)

**Interfaces:**
- Consumes: nothing new.
- Produces: `AbsencePlanner.Plan(Set<LocalDate> absentDays, List<LinkedHashMap<String,Object>> leaveRows, SeedCalendar calendar)` (the `absenceRows` component is gone); `AbsencePlanner.BASE_HOURS = 44.0` (moved from `CapacityWriter`); the seeded envelope has empty `absences`, `user_capacity` and `team_capacity`; every `task_history` row with `field_name = 'assignee'` carries the assigner in `user_id`.

- [ ] **Step 1: Write the failing seed tests**

In `SeedGeneratorTest`, replace `capacityRowsAreConsistent` with:

```java
    @Test
    void theSeedWritesLeavesNotAbsencesOrCapacityRows() {
        ExportEnvelope env = generated();
        assertTrue(env.rows("absences").isEmpty());
        assertTrue(env.rows("user_capacity").isEmpty());
        assertTrue(env.rows("team_capacity").isEmpty());
        List<LinkedHashMap<String, Object>> leaves = env.rows("personal_leaves");
        assertFalse(leaves.isEmpty());
        long halfDays = leaves.stream().filter(l -> l.get("end_time") != null).count();
        long pending = leaves.stream().filter(l -> "PENDING".equals(l.get("status"))).count();
        assertTrue(halfDays > 0, "some vacation blocks end on a half day");
        assertTrue(pending > 0, "some members have a pending leave after the as-of date");
        for (var l : leaves) {
            LocalDate start = LocalDate.parse((String) l.get("start_date"));
            LocalDate end = LocalDate.parse((String) l.get("end_date"));
            assertFalse(end.isBefore(start));
            assertTrue((Double) l.get("absence_hours") > 0);
            if ("PENDING".equals(l.get("status"))) {
                assertTrue(start.isAfter(CFG.lastDay()), "a pending leave lies in the future");
                assertTrue(!start.isAfter(CFG.lastDay().plusWeeks(4)), "inside the four weeks after the as-of date");
            } else {
                assertEquals("APPROVED", l.get("status"));
            }
            if (l.get("end_time") != null) {
                assertEquals("12:00", l.get("end_time"));
            }
        }
    }

    @Test
    void everyAssigneeHistoryRowNamesItsAssignerAndSelfPickedTasksAreAssignedByTheirAssignee() {
        ExportEnvelope env = generated();
        Map<String, String> assigneeByTask = new HashMap<>();
        Map<String, String> reporterByTask = new HashMap<>();
        for (var t : env.rows("tasks")) {
            assigneeByTask.put((String) t.get("id"), (String) t.get("assignee_id"));
            reporterByTask.put((String) t.get("id"), (String) t.get("reporter_id"));
        }
        int self = 0;
        int assigned = 0;
        for (var h : env.rows("task_history")) {
            if (!"assignee".equals(h.get("field_name"))) {
                continue;
            }
            String actor = (String) h.get("user_id");
            assertTrue(actor != null && !actor.isBlank());
            String task = (String) h.get("task_id");
            if (actor.equals(assigneeByTask.get(task))) {
                self++;
                assertEquals(actor, reporterByTask.get(task), "a self-picked task is reported by its assignee");
            } else {
                assigned++;
            }
        }
        assertTrue(self > 0 && assigned > 0, "both modes present: self " + self + ", assigned " + assigned);
    }
```

Replace lines 74 to 77 of `everyForeignKeyResolvesAndKeysAreUnique` (the `absences`, `user_capacity`, `team_capacity` resolves) with only `assertResolves(env, "personal_leaves", "employee_id", users);`. In `loggedHoursTrackAssignedEstimatesAndNeverExceedPresence` (line 122), the `absentDays` set is built from `absences`; build it from the leaves instead:

```java
        Set<String> absentDays = new HashSet<>();
        for (var l : env.rows("personal_leaves")) {
            if (!"APPROVED".equals(l.get("status"))) {
                continue;
            }
            for (LocalDate d = LocalDate.parse((String) l.get("start_date")); !d.isAfter(LocalDate.parse((String) l.get("end_date"))); d = d.plusDays(1)) {
                absentDays.add(l.get("employee_id") + "|" + d);
            }
        }
```

(the seed's own presence stays whole-day for the half-day block, per spec section 4, so a member still logs nothing on that afternoon, and this assertion holds).

In `historyCoversTheConfiguredWeeksWithRealisticVolume` (line 96) the `weeks` set is built from `user_capacity`; build it from `time_logs` instead:

```java
        Set<String> weeks = new HashSet<>();
        for (var r : env.rows("time_logs")) {
            weeks.add(SeedConfig.mondayOf(LocalDate.parse((String) r.get("log_date"))).toString());
        }
        assertTrue(weeks.size() >= CFG.mondays().size() - 2, "logs in nearly every week: " + weeks.size());
        assertTrue(weeks.contains(CFG.firstMonday().toString()) || weeks.contains(CFG.firstMonday().plusWeeks(1).toString()));
```

In `AbsencePlannerTest.absencesAreWorkingDaysWhileEmployedAndWithinBounds`, replace the `vacation`, `sick` and `distinct` lines with:

```java
        long vacation = plan.leaveRows().stream().filter(l -> "PAID_LEAVE".equals(l.get("leave_type"))).mapToLong(l -> daysOf(l)).sum();
        long sick = plan.leaveRows().stream().filter(l -> "SICK_LEAVE".equals(l.get("leave_type"))).count();
        boolean approvedInHistory = plan.leaveRows().stream()
                .filter(l -> !LocalDate.parse((String) l.get("start_date")).isAfter(cfg().lastDay()))
                .allMatch(l -> "APPROVED".equals(l.get("status")));
        boolean pendingInFuture = plan.leaveRows().stream()
                .filter(l -> "PENDING".equals(l.get("status")))
                .allMatch(l -> LocalDate.parse((String) l.get("start_date")).isAfter(cfg().lastDay()));
        return allWorking && vacation >= 5 && vacation <= 20 && sick <= 4 && approvedInHistory && pendingInFuture;
```

with the helper, counting the working days a leave row spans:

```java
    static long daysOf(java.util.LinkedHashMap<String, Object> l) {
        SeedCalendar cal = cal();
        long n = 0;
        for (LocalDate d = LocalDate.parse((String) l.get("start_date")); !d.isAfter(LocalDate.parse((String) l.get("end_date"))); d = d.plusDays(1)) {
            if (cal.isWorkingDay(d)) {
                n++;
            }
        }
        return n;
    }
```

In `WorkFamilyPropertyTest`, replace `CapacityWriter.BASE_HOURS` with `AbsencePlanner.BASE_HOURS` (two places).

- [ ] **Step 2: Run the seed tests to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='SeedGeneratorTest,AbsencePlannerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile failure on `absenceRows()` still existing / `AbsencePlanner.BASE_HOURS` missing, then assertion failures.

- [ ] **Step 3: Change `AbsencePlanner`**

- Add `public static final double BASE_HOURS = 44.0;` beside `HOURS_PER_DAY` with the comment "the seed's own week; the module's default is `whf.default-weekly-hours`".
- `Plan` loses `absenceRows`: `public record Plan(Set<LocalDate> absentDays, List<LinkedHashMap<String, Object>> leaveRows, SeedCalendar calendar)`. Every `new Plan(absent, absenceRows, leaveRows, cal)` becomes `new Plan(absent, leaveRows, cal)`; the `absenceRows` list and the per-day loop that filled it in `addLeave` are deleted (the `absent.add(d)` loop stays).
- `addLeave` gains one boolean, `halfDayEnd`; the vacation loop passes `b == 0 && rnd.chance(0.2)` (one block in five), the sick loop passes `false`:

```java
    static void addLeave(Person p, List<LocalDate> days, String leaveType, String note, boolean halfDayEnd, String status,
            Set<LocalDate> absent, List<LinkedHashMap<String, Object>> leaveRows, SeedRandom rnd) {
        String created = days.get(0).minusDays(14).atTime(10, 0).toString();
        for (LocalDate d : days) {
            absent.add(d);
        }
        LinkedHashMap<String, Object> l = new LinkedHashMap<>();
        l.put("absence_hours", HOURS_PER_DAY * days.size() - (halfDayEnd ? HOURS_PER_DAY / 2 : 0.0));
        l.put("begin_time", null);
        l.put("end_date", days.get(days.size() - 1).toString());
        l.put("end_time", halfDayEnd ? "12:00" : null);
        l.put("start_date", days.get(0).toString());
        l.put("created_at", created);
        l.put("updated_at", created);
        l.put("employee_id", p.id().toString());
        l.put("id", rnd.uuid().toString());
        l.put("note", note);
        l.put("leave_type", leaveType);
        l.put("processor", null);
        l.put("status", status);
        leaveRows.add(l);
    }
```

The `absenceType` argument (`"VACATION"`, `"SICK_LEAVE"` of the old `absences.type`) is dropped from the two call sites. A half-day block keeps its last day in `absent` (the seed's presence stays whole-day, spec section 4).

- After the sick days, one member in ten gets a pending leave after the as-of date; it must not enter `absent`:

```java
        if (rnd.chance(0.1)) {
            LocalDate first = cfg.lastDay().plusDays(1 + rnd.between(0, 20));
            int length = rnd.between(2, 5);
            List<LocalDate> block = new ArrayList<>();
            for (LocalDate d = first; block.size() < length; d = d.plusDays(1)) {
                if (cal.isWorkingDay(d)) {
                    block.add(d);
                }
            }
            addLeave(p, block, "PAID_LEAVE", "Requested leave", false, "PENDING", new TreeSet<>(), leaveRows, rnd);
        }
```

`SeedCalendar.isWorkingDay` accepts any date (weekday and not in its holiday set), so days after the seed's last day are fine.

- [ ] **Step 4: Change `SeedGenerator` and `WorkQueue`, delete `CapacityWriter`**

In `SeedGenerator.generate`:
- step 3: drop the `absences` list and `absences.addAll(plan.absenceRows())`; keep `leaves.addAll(plan.leaveRows())`.
- step 5 (capacity): delete the whole block, including `userCapacity`, `userCapacityRows`, `teamCapacityRows` and the `orderedTeams` loop.
- step 7: delete `data.put("absences", absences);`, `data.put("user_capacity", userCapacityRows);` and `data.put("team_capacity", teamCapacityRows);` (the keys stay present and empty through the `TABLE_ORDER` loop above them).
- Remove the unused imports.

In `WorkQueue.assign`, line 368: the history row's actor is the assigner:

```java
        UUID assigner = a.mode().equals("self") ? p.id() : leader;
        historyRows.add(Rows.history(rnd.uuid(), id, assigner, "assignee", null, p.fullName(), a.assignAt()));
```

and this row is written for every assignment, not only `if (backlog)`: remove the `if (backlog)` guard, so that every assigned task has an assignee row (as the application writes one on assignment). `reporter` stays as it is.

Delete `CapacityWriter.java` and `CapacityWriterTest.java`. Grep `CapacityWriter` across `server/` and fix the remaining references (`WorkFamilyPropertyTest` per Step 1; the README mentions it in the "capacity rows follow the application's formula" sentence, which Task 7 rewrites).

- [ ] **Step 5: Run the seed tests, then the lifecycle and repository tests that read seeded data**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='SeedGeneratorTest,AbsencePlannerTest,WorkFamilyPropertyTest,LifecycleTest,ForecastRepositoryTest,CapacityRuleTest,RoundTripTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. `LifecycleTest.seededDataResolvesEveryAssignmentAndSplitsFreshFromBacklog` now sees an assignee row on every task; its fresh/backlog assertions still hold because the assignment date does not change (the row's `changed_at` is `a.assignAt()`, the same instant `created_date` holds for a fresh task), and it asserts nothing about the rows themselves.

- [ ] **Step 6: Commit**

```bash
git add -A server/forecast-core/src
git commit -m "feat(seed): write leaves only, name the assigner on history rows, drop the capacity rows"
```

---

### Task 3b: the real-mode envelope is five tables, landed by deletes and an upsert

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/SeedGenerator.java` (the envelope at step 7)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/SqlExportWriter.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ExportImporter.java:49-66`
- Modify: `server/tools/Experiment.java:194-198`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/SeedGeneratorTest.java` (real-mode envelope)
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/data/SqlExportWriterTest.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/data/ExportImporterTest.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/ExperimentFlowTest.java` (only if it asserts on the summary's table lines)

**Interfaces:**
- Produces: `SeedGenerator.REAL_MODE_TABLES = List.of("projects", "tasks", "task_history", "time_logs", "personal_leaves")` (public constant); a real-mode envelope whose `data` has exactly those keys and whose `excludedTables` lists every other `TABLE_ORDER` table; `SqlExportWriter.write` emitting deletes and the `projects` upsert for such an envelope; `ExportImporter.importAll(env, true)` deleting only the envelope's tables.

- [ ] **Step 1: Write the failing tests**

In `SeedGeneratorTest`, the real-mode test reads the mini export fixture the way `ExperimentFlowTest` does (`Path.of("src/test/resources/fixtures/mini-export.json")`):

```java
    @Test
    void realModeWritesTheFiveWorkTablesAndExcludesTheRest() throws Exception {
        ExportEnvelope input = ExportFiles.read(java.nio.file.Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        assertEquals(SeedGenerator.REAL_MODE_TABLES, List.copyOf(env.data().keySet()));
        List<String> excluded = new java.util.ArrayList<>(WorkloadHubSchema.TABLE_ORDER);
        excluded.removeAll(SeedGenerator.REAL_MODE_TABLES);
        assertEquals(excluded, env.excludedTables());
        assertFalse(env.rows("tasks").isEmpty());
        Set<String> inputProjects = ids(input, "projects");
        assertTrue(ids(env, "projects").containsAll(inputProjects), "existing projects are re-emitted");
        assertEquals(input.database(), env.database());
    }

    @Test
    void syntheticModeStillWritesEveryTable() {
        ExportEnvelope env = generated();
        assertEquals(WorkloadHubSchema.TABLE_ORDER, List.copyOf(env.data().keySet()));
        assertEquals(List.of("refresh_tokens"), env.excludedTables());
    }
```

`ExportFiles` is already imported there. In `SqlExportWriterTest` add:

```java
    @Test
    void aFiveTableEnvelopeIsLandedWithDeletesAndAProjectsUpsert() throws Exception {
        ExportEnvelope input = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        String sql = out.toString();
        // the reverse of TABLE_ORDER, restricted to the envelope's tables other than projects
        int deletes = sql.indexOf("DELETE FROM personal_leaves;\nDELETE FROM time_logs;\nDELETE FROM task_history;\nDELETE FROM tasks;\n");
        assertTrue(deletes > 0 && deletes < sql.indexOf("INSERT INTO"), "children deleted first, before any insert: " + sql.substring(0, 200));
        assertTrue(!sql.contains("DELETE FROM projects"));
        assertTrue(sql.contains("INSERT INTO projects"));
        assertTrue(sql.contains("ON CONFLICT (id) DO UPDATE SET"), "projects are upserted");
        assertTrue(sql.contains("next_task_number = EXCLUDED.next_task_number"));
        int tasksInsert = sql.indexOf("INSERT INTO tasks");
        assertTrue(!sql.substring(tasksInsert, sql.indexOf(";\n", tasksInsert)).contains("ON CONFLICT"), "only projects carry the upsert");
        assertTrue(!sql.contains("INSERT INTO users"));
    }

    @Test
    void aSyntheticEnvelopeHasNoDeletes() throws Exception {
        ExportEnvelope env = SeedGenerator.generate(null, new SeedConfig(8, LocalDate.of(2026, 9, 6), 5, true, 12));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        assertTrue(!out.toString().contains("DELETE FROM"));
        assertTrue(!out.toString().contains("ON CONFLICT"));
    }

    @Test
    void aFiveTableScriptLandsTwiceOnPostgresql() throws Exception {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ExportEnvelope input = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        // the directory first, as the application's own database would hold it
        StringWriter directory = new StringWriter();
        SqlExportWriter.write(input, directory);
        ExportEnvelope env = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        StringWriter out = new StringWriter();
        SqlExportWriter.write(env, out);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(directory.toString());
            st.execute(out.toString());
            st.execute(out.toString());   // a second landing replaces, it does not duplicate
        }
        ExportEnvelope back = new ExportExporter(ds).exportAll();
        assertEquals(env.rows("tasks").size(), back.rows("tasks").size());
        assertEquals(env.rows("projects").size(), back.rows("projects").size());
        assertEquals(2, back.rows("users").size(), "the directory is untouched");
    }
```

The mini export's single task and its logs are in the directory script and deleted by the seed script's `DELETE FROM tasks`; the fixture has no comments or attachments, so the delete succeeds. Create `ExportImporterTest`:

```java
package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workloadhub.forecast.seed.SeedConfig;
import com.workloadhub.forecast.seed.SeedGenerator;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import java.nio.file.Path;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class ExportImporterTest {

    @Test
    void replaceDeletesOnlyTheTablesTheEnvelopeCarries() throws Exception {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        ExportEnvelope input = ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json"));
        new ExportImporter(ds).importAll(input, true);
        ExportEnvelope seeded = SeedGenerator.generate(input, new SeedConfig(8, LocalDate.of(2026, 9, 6), 3, false, 0));
        new ExportImporter(ds).importAll(seeded, true);
        ExportEnvelope back = new ExportExporter(ds).exportAll();
        assertEquals(input.rows("users").size(), back.rows("users").size(), "the directory survives a five-table replace");
        assertEquals(input.rows("holidays").size(), back.rows("holidays").size());
        assertEquals(seeded.rows("tasks").size(), back.rows("tasks").size(), "the work tables are replaced, not appended");
        assertEquals(seeded.rows("projects").size(), back.rows("projects").size());
        assertEquals(seeded.rows("personal_leaves").size(), back.rows("personal_leaves").size());
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='SeedGeneratorTest,SqlExportWriterTest,ExportImporterTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile failure on `SeedGenerator.REAL_MODE_TABLES`, then assertion failures.

- [ ] **Step 3: Change `SeedGenerator`'s envelope**

Add the constant and change step 7:

```java
    /** The tables a real-mode seed writes: the application's own database already holds every other one (design 2026-09-17, section 7). */
    public static final List<String> REAL_MODE_TABLES = List.of("projects", "tasks", "task_history", "time_logs", "personal_leaves");
```

```java
        // 7. envelope in table order: every table in synthetic mode (the experiment database is built from it),
        // the five work tables in real mode (the application's database holds the rest)
        LinkedHashMap<String, List<LinkedHashMap<String, Object>>> data = new LinkedHashMap<>();
        List<String> excluded;
        if (synthetic) {
            for (String table : WorkloadHubSchema.TABLE_ORDER) {
                data.put(table, new ArrayList<>());
            }
            data.put("user_roles", roles);
            data.put("job_titles", jobTitles);
            data.put("users", dir.userRows());
            data.put("teams", dir.teamRows());
            data.put("team_members", dir.teamMemberRows());
            data.put("task_statuses", statuses);
            data.put("task_types", types);
            data.put("holidays", cal.holidayRows());
            data.put("sync_metadata", syncMetadata);
            excluded = List.of("refresh_tokens");
        } else {
            excluded = new ArrayList<>();
            for (String table : WorkloadHubSchema.TABLE_ORDER) {
                if (REAL_MODE_TABLES.contains(table)) {
                    data.put(table, new ArrayList<>());
                } else {
                    excluded.add(table);
                }
            }
        }
        data.put("projects", outProjects);
        data.put("tasks", work.taskRows());
        data.put("task_history", work.historyRows());
        data.put("time_logs", work.timeLogRows());
        data.put("personal_leaves", leaves);
        String database = input == null ? "synthetic" : input.database();
        return new ExportEnvelope(database, "task_service", cfg.lastDay().atTime(18, 0).toString(), excluded, data);
```

Synthetic-with-input (`--synthetic --export`) is synthetic: every table. The `absences`, `user_capacity` and `team_capacity` keys stay present and empty in synthetic mode through the `TABLE_ORDER` loop.

- [ ] **Step 4: Change `SqlExportWriter`**

```java
    public static void write(ExportEnvelope env, Writer out) throws IOException {
        out.write("BEGIN;\nSET search_path TO task_service;\n");
        // partial: the envelope does not carry every table (a real-mode seed), so it lands into a database that
        // already holds the rest — delete the seeded work tables child-first and upsert the shared one
        boolean partial = !env.data().keySet().containsAll(
                WorkloadHubSchema.TABLE_ORDER.stream().filter(t -> !t.equals("refresh_tokens")).toList());
        if (partial) {
            List<String> reverse = new ArrayList<>(WorkloadHubSchema.TABLE_ORDER);
            Collections.reverse(reverse);
            for (String table : reverse) {
                if (env.data().containsKey(table) && !table.equals(UPSERTED)) {
                    out.write("DELETE FROM " + table + ";\n");
                }
            }
        }
        for (String table : WorkloadHubSchema.TABLE_ORDER) {
            List<LinkedHashMap<String, Object>> rows = WorkloadHubSchema.parentsFirst(table, env.rows(table));
            if (rows.isEmpty()) {
                continue;
            }
            List<String> columns = WorkloadHubSchema.columnsOf(rows);
            String upsert = partial && table.equals(UPSERTED) ? onConflict(columns) : "";
            for (int start = 0; start < rows.size(); start += ROWS_PER_STATEMENT) {
                out.write("INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES\n");
                int end = Math.min(rows.size(), start + ROWS_PER_STATEMENT);
                for (int i = start; i < end; i++) {
                    LinkedHashMap<String, Object> row = rows.get(i);
                    StringBuilder sb = new StringBuilder("(");
                    for (int c = 0; c < columns.size(); c++) {
                        sb.append(c == 0 ? "" : ", ").append(literal(row.get(columns.get(c))));
                    }
                    sb.append(i == end - 1 ? ")" + upsert + ";\n" : "),\n");
                    out.write(sb.toString());
                }
            }
        }
        out.write("COMMIT;\n");
    }

    /** The one table a real-mode seed shares with the application: existing rows are updated, new ones inserted. */
    static final String UPSERTED = "projects";

    static String onConflict(List<String> columns) {
        StringBuilder sb = new StringBuilder("\nON CONFLICT (id) DO UPDATE SET ");
        boolean first = true;
        for (String c : columns) {
            if (c.equals("id")) {
                continue;
            }
            sb.append(first ? "" : ", ").append(c).append(" = EXCLUDED.").append(c);
            first = false;
        }
        return sb.toString();
    }
```

Imports: `java.util.ArrayList`, `java.util.Collections`.

- [ ] **Step 5: Change `ExportImporter.importAll`**

Replace the `if (replace)` block:

```java
            if (replace) {
                // Only the tables the envelope carries, children first: a five-table seed lands into a database
                // that already holds the directory (design 2026-09-17, section 7.2).
                List<String> reverse = new ArrayList<>(WorkloadHubSchema.TABLE_ORDER);
                java.util.Collections.reverse(reverse);
                try (Statement st = c.createStatement()) {
                    for (String table : reverse) {
                        if (envelope.data().containsKey(table)) {
                            st.execute("DELETE FROM " + table);
                        }
                    }
                }
            }
```

and the insert loop iterates `TABLE_ORDER` as before (tables absent from the envelope insert nothing). Update the method's Javadoc: "with replace, deletes the envelope's own tables, children first".

- [ ] **Step 6: Change the driver's summary**

`Experiment.java:194-198`: print the tables the envelope holds, in order, instead of a fixed list:

```java
        for (String table : result.data().keySet()) {
            System.out.printf("  %-18s %8d%n", table, result.rows(table).size());
        }
```

`ExperimentFlowTest` asserts only on "synthetic identities", "Wrote" and `INSERT INTO tasks`, none of which change.

- [ ] **Step 7: Run the tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='SeedGeneratorTest,SqlExportWriterTest,ExportImporterTest,RoundTripTest,ExperimentFlowTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (`scriptLoadsIntoPostgresql` and the two-landing test skip without Docker and say so).

- [ ] **Step 8: Commit**

```bash
git add -A server/forecast-core/src server/tools/Experiment.java
git commit -m "feat(seed): real mode writes the five work tables, landed by deletes and a projects upsert"
```

---

### Task 4: the assignment mode is read from the history

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Mode.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Lifecycle.java:63-121,142-147`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/facts/MemberPattern.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/facts/Patterns.java:49-60,131-134`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/facts/Clustering.java:55`
- Modify: `server/forecast-core/src/main/resources/skills/whf-pattern-discovery/SKILL.md:12`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/lifecycle/LifecycleTest.java:139-155`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/facts/PatternsTest.java:59,93`, `ClusteringTest.java:14-16`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/testing/TestData.java` (a new helper)

**Interfaces:**
- Produces: `enum Mode { SELF_PICKED, ASSIGNED, UNKNOWN }`; `MemberPattern(..., Double shareSelfPicked, Double shareAssigned, ...)` in place of `(shareManual, shareSelfPicked, shareProject)`; facts keys `share_self_picked` and `share_assigned` (no `share_manual`, no `share_project`). Task 5 reads `TaskFacts.mode()`.
- `TestData.assignee(UUID task, String newValue, LocalDateTime at)` keeps the reporter as actor; a new `TestData.assignedBy(UUID task, UUID actor, String newValue, LocalDateTime at)` sets the actor.

- [ ] **Step 1: Write the failing tests**

In `TestData` add:

```java
    public static TransitionRow assignedBy(UUID task, UUID actor, String newValue, LocalDateTime at) {
        return new TransitionRow(task, actor, "assignee", null, newValue, at);
    }
```

Replace `LifecycleTest.familyAndModeFollowTheTables` (lines 139 to 155; keep its family assertions, replace the mode ones) with:

```java
    @Test
    void familyFollowsTheTypeTableAndTheParent() {
        TaskRow sub = TestData.task("1", ANA.id(), CREATED, 4).withType("Sub-task").withParent(TestData.id("task-2"));
        TaskRow bug = TestData.task("2", ANA.id(), CREATED, 4).withType("Bug");
        TaskRow spike = TestData.task("3", ANA.id(), CREATED, 4).withType("Spike");
        Lifecycle lc = Lifecycle.derive(TestData.data(List.of(ANA), List.of(sub, bug, spike), List.of(), List.of()));
        assertEquals(Family.DEFECT, lc.of(sub.id()).family(), "a sub-task of a bug is bug work");
        assertEquals(Family.DEFECT, lc.of(bug.id()).family());
        assertEquals(Family.SUPPORT, lc.of(spike.id()).family());
    }

    @Test
    void modeIsWhoAssignedTheTaskAccordingToTheHistory() {
        TaskRow self = TestData.task("1", ANA.id(), CREATED, 4);
        TaskRow byLead = TestData.task("2", ANA.id(), CREATED, 4);
        TaskRow noRow = TestData.task("3", ANA.id(), CREATED, 4).withReporter(ANA.id());
        ForecastData data = TestData.data(List.of(ANA, BEN), List.of(self, byLead, noRow), List.of(
                TestData.assignedBy(self.id(), ANA.id(), ANA.fullName(), CREATED.plusHours(1)),
                TestData.assignedBy(byLead.id(), BEN.id(), ANA.fullName(), CREATED.plusHours(1))), List.of());
        Lifecycle lc = Lifecycle.derive(data);
        assertEquals(Mode.SELF_PICKED, lc.of(self.id()).mode(), "the assignee did the assigning");
        assertEquals(Mode.ASSIGNED, lc.of(byLead.id()).mode(), "someone else did");
        assertEquals(Mode.UNKNOWN, lc.of(noRow.id()).mode(), "no assignee row: the reporter is not evidence");
    }
```

In `PatternsTest.recentWindowStatisticsFollowTheThirteenWeeks` the two window tasks `a` and `b` have no assignee row, so under the new rule both are `UNKNOWN` and both shares would be null. Give them rows, at their creation instants so nothing else moves: the `TestData.data(...)` call gets the transitions `List.of(TestData.assignedBy(a.id(), ANA.id(), ANA.fullName(), mon), TestData.assignedBy(b.id(), TestData.id("lead"), ANA.fullName(), mon.plusDays(1)))` instead of `List.of()`; the assertions become `assertEquals(0.5, p.shareSelfPicked(), 1e-9)` and `assertEquals(0.5, p.shareAssigned(), 1e-9)`, and `assertEquals(22, p.toMap().size())` becomes `21` (one key fewer). In `completionStatisticsUseTheWholeHistory`, `assertNull(p.shareManual())` becomes `assertNull(p.shareAssigned())`. In `ClusteringTest.pattern(...)` the `MemberPattern` constructor call drops one argument: `..., 0.0, 1 - shareManual, shareManual, ...` becomes `..., 0.0, 1 - shareManual, shareManual, ...` mapped to the new order `(shareSelfPicked, shareAssigned)`: pass `1 - shareManual` as `shareSelfPicked` and `shareManual` as `shareAssigned`, and rename the parameter `shareAssigned`.

- [ ] **Step 2: Run to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='LifecycleTest,PatternsTest,ClusteringTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile failures (`Mode.ASSIGNED`, `shareAssigned`).

- [ ] **Step 3: Implement**

`Mode.java`:

```java
/** How a task reached its assignee, from the task_history row that assigned it (design 2026-09-17, section 6). */
public enum Mode {
    SELF_PICKED, ASSIGNED, UNKNOWN;
    ...label() unchanged
}
```

`Lifecycle`: the private record becomes `Assignment(LocalDateTime at, boolean fallback, UUID assigner)`; in `assignment(...)` the found row returns `new Assignment(row.changedAt(), false, row.userId())` and the two fallbacks return `new Assignment(null, false, null)` and `new Assignment(t.createdDate(), sawAssigneeRow, null)`. In `derive`, `mode(t)` becomes `mode(t, a)`:

```java
    private static Mode mode(TaskRow t, Assignment a) {
        if (a.assigner() == null || t.assigneeId() == null) {
            return Mode.UNKNOWN;
        }
        return a.assigner().equals(t.assigneeId()) ? Mode.SELF_PICKED : Mode.ASSIGNED;
    }
```

`MemberPattern`: the components `Double shareManual, Double shareSelfPicked, Double shareProject` become `Double shareSelfPicked, Double shareAssigned`; `toMap` writes `share_self_picked` then `share_assigned` and no longer `share_manual` or `share_project`.

`Patterns.of`: replace the three counters with `int self = 0; int assigned = 0;` and the branch with:

```java
            if (f.mode() == Mode.SELF_PICKED) {
                self++;
            } else if (f.mode() == Mode.ASSIGNED) {
                assigned++;
            }
```

and in the constructor call `n == 0 ? null : (double) manual / n, n == 0 ? null : (double) self / n, n == 0 ? null : (double) project / n,` becomes:

```java
                known == 0 ? null : (double) self / known, known == 0 ? null : (double) assigned / known,
```

with `int known = self + assigned;` computed after the loop.

`Clustering.java:55`: the feature vector `z(p.shareManual()), z(p.shareSelfPicked()), z(p.shareProject())` becomes `z(p.shareSelfPicked()), z(p.shareAssigned())`.

`whf-pattern-discovery/SKILL.md` line 12: `| share_self_picked, share_assigned | share of the recent tasks the member picked themselves versus received from someone else, over the tasks whose assigner is recorded | one share is at least 0.7 (dominant style) |`.

- [ ] **Step 4: Run the tests and the skill test**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='LifecycleTest,PatternsTest,ClusteringTest,FactsBuilderTest,SkillTextsTest,NarrativeContractTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (`FactsBuilderTest` names no mode key).

- [ ] **Step 5: Commit**

```bash
git add -A server/forecast-core/src
git commit -m "feat(lifecycle): read who assigned a task from its history instead of guessing"
```

---

### Task 5: the feature matrix after the review

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/features/Features.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/features/FeatureBuilder.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/rows/TaskRow.java` (a `plannedWeek` component and `withPlannedWeek`)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/data/ForecastRepository.java:93-100` (select `planned_week`)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/lifecycle/Truncation.java` (the `new TaskRow(...)` call)
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/testing/TestData.java:46-49`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/features/FeaturesTest.java`, `FeatureBuilderTest.java`
- Modify: any other `new TaskRow(` in tests (grep; the seed builds maps, not records)

**Interfaces:**
- Consumes: `TaskFacts.mode()` in `{SELF_PICKED, ASSIGNED, UNKNOWN}` (Task 4); `CapacityRule.absenceHours`/`capacity` (Task 2).
- Produces: `Features.featureColumns(h)` with 45 entries (40 shared + `due_hrs_h`, `planned_hrs_h`, `working_days_h`, `absence_hrs_h`, `available_hrs_h`); `TaskRow.plannedWeek()` (`LocalDate`, nullable, normalised to a Monday); the blanks of spec section 5.2.

- [ ] **Step 1: Write the failing tests**

`FeaturesTest`: rename and change the two count tests:

```java
    @Test
    void fortyFiveColumnsPerHorizonInTheSpecOrderWithoutDuplicates() {
        for (int h : Features.horizons(WINDOWS)) {
            List<String> cols = Features.featureColumns(h);
            assertEquals(45, cols.size(), cols.toString());
            assertEquals(cols.size(), new HashSet<>(cols).size());
            assertEquals("lag1", cols.get(0));
            assertTrue(cols.contains("due_hrs_h" + h));
            assertTrue(cols.contains("planned_hrs_h" + h));
            assertTrue(cols.contains("available_hrs_h" + h));
            assertFalse(cols.contains(Features.target(h)));
            for (int k = 1; k <= 4; k++) {
                assertTrue(cols.contains("arrival_hrs_lag" + k));
            }
            assertFalse(cols.contains("logged_hours_lag1"));
            assertFalse(cols.contains("proj_first_due_weeks"));
            assertFalse(cols.contains("share_manual_13w"));
            assertFalse(cols.contains("share_project_13w"));
            assertTrue(cols.contains("share_self_picked_13w") && cols.contains("share_assigned_13w"));
            for (int other : Features.horizons(WINDOWS)) {
                if (other != h) {
                    assertFalse(cols.contains("due_hrs_h" + other));
                    assertFalse(cols.contains("planned_hrs_h" + other));
                }
            }
        }
    }

    @Test
    void sharedColumnsAreFortyAfterTheReview() {
        int h0 = Features.horizons(WINDOWS)[0];
        List<String> shared = Features.featureColumns(h0).stream().filter(c -> !c.endsWith("_h" + h0)).toList();
        assertEquals(40, shared.size(), shared.toString());
        ...keep the existing arrival/logged assertions
    }
```

`FeatureBuilderTest`:
- `thirteenWeekSharesCountEveryArrivalFreshOrNot`: Ana's fourth task (`fourth`, the Bug) has `withReporter(ANA.id())` but no assignee row from Ana; make it self-picked through the history: add `TestData.assignedBy(fourth.id(), ANA.id(), ANA.fullName(), ORIGIN.minusWeeks(1).atTime(9, 0))` to `ana()`'s transitions, and add a leader row for `first` and `third`: `TestData.assignedBy(first.id(), TestData.id("lead"), ANA.fullName(), w5)` and the same for `third` at its creation instant. `lagged` already has a row from `id("reporter")` (ASSIGNED). Then the assertions become `0.25` for `share_self_picked_13w`, `0.75` for `share_assigned_13w`, and the row-0 line becomes `assertTrue(Double.isNaN(m.get(0, "share_assigned_13w")), "no arrivals yet: blank, not one third");` plus `assertTrue(Double.isNaN(m.get(0, "share_defect_13w")))`, `assertTrue(Double.isNaN(m.get(origin, "reopen_rate_13w")), "nothing finished: blank")`, `assertTrue(Double.isNaN(m.get(origin, "estimate_ratio_13w")), "nothing finished: blank, not 1.0")`. The `assignedWeek` of `first` and `third` must stay the creation instant so the lags do not move: use exactly `w5` and `ORIGIN.minusWeeks(2).atTime(9, 0)`.
- `lagsAndRollingStatsFollowTheLoggedSeriesWhileGapAndArrivalLagsFollowArrivals` line 96: `assertEquals(52.0, m.get(0, "weeks_since_last_arrival"))` becomes `assertTrue(Double.isNaN(m.get(0, "weeks_since_last_arrival")), "never: blank, not 52")`.
- `teamColumnsSeeTheBacklogAndTheProjects`: delete the two `proj_first_due_weeks` assertions.
- `seededMatrixHasEveryFeatureColumnPopulated`: the `removeAll` list drops `proj_first_due_weeks` (no such column any more). `planned_hrs_h1` stays expected: `FeatureMatrix.nonEmptyColumns` keeps a column with any non-NaN value, and a zero is a value.
- A new test for `planned_hrs_h`:

```java
    @Test
    void plannedHoursAreTheOpenTasksEstimatesPlannedForTheTargetWeek() {
        TaskRow planned = TestData.task("1", ANA.id(), ORIGIN.minusWeeks(2).atTime(9, 0), 12).withPlannedWeek(ORIGIN.plusWeeks(2));
        TaskRow done = TestData.task("2", ANA.id(), ORIGIN.minusWeeks(2).atTime(9, 0), 5).withPlannedWeek(ORIGIN.plusWeeks(2))
                .withStatus("DONE").withFinished(ORIGIN.minusWeeks(1).atTime(9, 0));
        TaskRow midweek = TestData.task("3", ANA.id(), ORIGIN.minusWeeks(2).atTime(9, 0), 3).withPlannedWeek(ORIGIN.plusWeeks(2).plusDays(2));
        FeatureMatrix m = matrix(TestData.data(List.of(ANA), List.of(planned, done, midweek), List.of(), List.of()));
        int origin = row(m, ORIGIN);
        assertEquals(15.0, m.get(origin, "planned_hrs_h2"), "the open tasks planned for week +2, a Wednesday normalised to its Monday");
        assertEquals(0.0, m.get(origin, "planned_hrs_h1"));
        assertEquals(0.0, m.get(origin, "planned_hrs_h3"));
        int before = row(m, ORIGIN.minusWeeks(3));
        assertEquals(0.0, m.get(before, "planned_hrs_h2"), "not yet assigned in week −3, so not planned work of hers then");
    }
```

`TestData.task(...)` builds a `TaskRow` with one more argument: `null` planned week. `withPlannedWeek(LocalDate)` is a new copy method.

- [ ] **Step 2: Run to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='FeaturesTest,FeatureBuilderTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile failure (`withPlannedWeek`), then count failures.

- [ ] **Step 3: `TaskRow`, the repository and `Truncation`**

`TaskRow` gains `LocalDate plannedWeek` after `dueDate` (so the record is `(..., LocalDate dueDate, LocalDate plannedWeek, boolean reopened, boolean archived)`); every `with*` copies it; add:

```java
    public TaskRow withPlannedWeek(LocalDate plannedWeek) {
        return new TaskRow(id, key, title, projectId, assigneeId, reporterId, parentId, typeName, statusCategory,
                priority, estimate, remaining, createdDate, startedDate, finishedDate, dueDate, plannedWeek, reopened, archived);
    }
```

`ForecastRepository`: add `planned_week` to the SELECT and `Weeks.mondayOf(date(r, "planned_week"))` guarded for null to the constructor call (`date(...)` returns null for a null cell; write `LocalDate pw = date(r, "planned_week"); ... pw == null ? null : Weeks.mondayOf(pw)`). `Truncation`'s `new TaskRow(...)` passes `t.plannedWeek()`. `TestData.task` passes `null`. Grep `new TaskRow(` in tests and add the argument.

- [ ] **Step 4: `Features` and `FeatureBuilder`**

`Features.build()`: delete `c.add("proj_first_due_weeks");`; replace `share_self_picked_13w`, `share_manual_13w`, `share_project_13w` with `share_self_picked_13w`, `share_assigned_13w`. `horizonColumns(h)` returns `List.of("due_hrs_h" + h, "planned_hrs_h" + h, "working_days_h" + h, "absence_hrs_h" + h, "available_hrs_h" + h)`. Fix the `featureColumns` Javadoc to "45 features: 40 shared, then the five `_h{h}` columns". Add to the class Javadoc the missing-value rule, verbatim from the spec section 5.2 table (column, blank when).

`FeatureBuilder`:
- delete `NEVER_WEEKS` and the `proj_first_due_weeks` computation: `TeamContext.compute` returns `{backlog, active, planning}`, `fill` writes three columns, and the `projectDue`/`firstDue` variables go.
- `weeksSinceLastArrival`: `double since = Double.NaN;` in place of `NEVER_WEEKS`.
- `windowStats`: counters `self`, `assigned`, `known`; the branch `if (t.mode() == Mode.SELF_PICKED) { self++; known++; } else if (t.mode() == Mode.ASSIGNED) { assigned++; known++; }`; and the writes:

```java
        r[col.get("arrivals_13w")] = n;
        r[col.get("share_defect_13w")] = n == 0 ? Double.NaN : (double) defect / n;
        r[col.get("share_delivery_13w")] = n == 0 ? Double.NaN : (double) delivery / n;
        r[col.get("share_support_13w")] = n == 0 ? Double.NaN : (double) support / n;
        r[col.get("share_high_priority_13w")] = n == 0 ? Double.NaN : (double) high / n;
        r[col.get("share_self_picked_13w")] = known == 0 ? Double.NaN : (double) self / known;
        r[col.get("share_assigned_13w")] = known == 0 ? Double.NaN : (double) assigned / known;
        r[col.get("reopen_rate_13w")] = finished == 0 ? Double.NaN : (double) reopened / finished;
        r[col.get("estimate_ratio_13w")] = estimate == 0 ? Double.NaN : actual / estimate;
        r[col.get("cycle_days_13w")] = cycles.isEmpty() ? Double.NaN : median(cycles);
```

- `MemberContext` gains:

```java
        /** Estimated hours of the member's tasks open at the end of week {@code w} and planned for the week of {@code target}. */
        double plannedHours(LocalDate w, LocalDate target) {
            LocalDate end = w.plusDays(6);
            double sum = 0;
            for (TaskFacts t : tasks) {
                LocalDate pw = t.task().plannedWeek();
                if (pw != null && pw.equals(target) && t.openAtEndOf(end)) {
                    sum += t.estimate();
                }
            }
            return sum;
        }
```

and the horizon loop writes `r[col.get("planned_hrs_h" + h)] = mc.plannedHours(w, target);` beside `due_hrs_h`.

- [ ] **Step 5: Run the feature tests, then the leakage and retarget tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='FeaturesTest,FeatureBuilderTest,FeatureLeakageTest,RetargetTest,FeatureMatrixTest,WeeklySeriesTest,BacktestTest,XgboostHoursTest,ForecastRunnerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. A test that pinned `46` or `42` anywhere else fails and is corrected to `45` / `40` (grep `46` and `42` in `features/` and `model/` tests). `XgboostHours.fit` already skips a column that is blank on every training row (`nonEmptyColumns`), so a small fixture where every `share_*` is blank still fits.

- [ ] **Step 6: Commit**

```bash
git add -A server/forecast-core/src
git commit -m "feat(features): apply the review: no project deadline, modes from history, planned hours, blanks for nothing to measure"
```

---

### Task 6: the facts and the skills

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/facts/FactsBuilder.java` (member block: `pending_leaves`, `planned_hours`)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/FactsTools.java:107-122` (`planned_hours` in the capacity tool's windows)
- Modify: `server/forecast-core/src/main/resources/skills/whf-domain/SKILL.md`, `whf-forecast-interpretation/SKILL.md`, `whf-rebalancing-advice/SKILL.md`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/facts/FactsBuilderTest.java`, `ai/FactsToolsTest.java`, `ai/SkillTextsTest.java`

**Interfaces:**
- Consumes: `ForecastData.pendingLeaves()` (Task 2), `TaskRow.plannedWeek()` (Task 5).
- Produces: `members[i].pending_leaves` = list of `{start_date, end_date, leave_type, absence_hours}`; `members[i].forecast[j].planned_hours`; the capacity tool's window rows carry `planned_hours`.

- [ ] **Step 1: Write the failing tests**

`FactsBuilderTest`:
- in `theRemovedKeysAreGone` (line 77) remove `"planned_hours"` from the list.
- add:

```java
    @Test
    @SuppressWarnings("unchecked")
    void membersCarryTheirPendingLeavesInsideTheHorizonAndPlannedHoursPerWindow() {
        List<Map<String, Object>> members = (List<Map<String, Object>>) facts.get("members");
        LocalDate first = LocalDate.parse((String) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) facts.get("run")).get("windows")).get(0)).get("start"));
        LocalDate last = LocalDate.parse((String) ((Map<?, ?>) ((List<?>) ((Map<?, ?>) facts.get("run")).get("windows")).get(1)).get("end"));
        int withPending = 0;
        for (Map<String, Object> m : members) {
            List<Map<String, Object>> pending = (List<Map<String, Object>>) m.get("pending_leaves");
            assertNotNull(pending);
            for (Map<String, Object> l : pending) {
                assertEquals(java.util.Set.of("start_date", "end_date", "leave_type", "absence_hours"), l.keySet());
                LocalDate s = LocalDate.parse((String) l.get("start_date"));
                LocalDate e = LocalDate.parse((String) l.get("end_date"));
                assertTrue(!e.isBefore(first) && !s.isAfter(last), "inside the horizon");
                withPending++;
            }
            for (Map<String, Object> w : (List<Map<String, Object>>) m.get("forecast")) {
                assertTrue(w.containsKey("planned_hours"));
                assertTrue(((Number) w.get("planned_hours")).doubleValue() >= 0);
            }
        }
        // The seed gives one member in ten a pending leave inside the four weeks after the as-of date; a two-window
        // horizon of a 36-user seed catches at least one on some team, but not necessarily on the first team. The
        // shape is what this test pins; the count is asserted on the whole population below.
        assertTrue(withPending >= 0);
        long seededPending = SeededData.data().pendingLeaves().stream()
                .filter(l -> !l.end().isBefore(first) && !l.start().isAfter(last)).count();
        assertTrue(seededPending > 0, "the seed does produce pending leaves inside a horizon");
    }

    @Test
    void theTeamBlockHasNoCapacityPlan() {
        assertFalse(((Map<?, ?>) facts.get("team")).containsKey("team_capacity"));
        assertFalse(json.contains("team_capacity"));
    }
```

`FactsToolsTest` line 51-53: add `"planned_hours"` to the expected window key set.

`SkillTextsTest.theSkillsSpeakTheJavaFactsVocabulary`: add

```java
        assertTrue(all.contains("pending_leaves") && all.contains("planned_hours"), "the 2026-09-17 facts");
        assertTrue(all.contains("assigned to them and not finished"), "open, defined for the leader (design 2026-09-17, section 8)");
        assertTrue(!all.contains("team_capacity") && !all.contains("share_manual") && !all.contains("share_project"), "removed facts");
        assertTrue(all.contains("approved leave"), "capacity is the calendar and the approved leaves");
```

and in `noSkillNamesARemovedFactKey` remove `"planned_hours"` from the list.

- [ ] **Step 2: Run to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='FactsBuilderTest,FactsToolsTest,SkillTextsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: assertion failures.

- [ ] **Step 3: `FactsBuilder` and `FactsTools`**

In `FactsBuilder.member(...)`:
- the `forecast` map gains `"planned_hours", Numbers.round2(plannedHours(open, r.windowStart(), r.windowEnd()))` after `due_hours`, with the helper in `FactsBuilder`:

```java
    /** Estimated hours of the member's open tasks whose planned week overlaps the window. */
    static double plannedHours(List<TaskFacts> open, LocalDate start, LocalDate end) {
        double sum = 0;
        for (TaskFacts f : open) {
            LocalDate pw = f.task().plannedWeek();
            if (pw != null && !pw.plusDays(6).isBefore(start) && !pw.isAfter(end)) {
                sum += f.estimate();
            }
        }
        return sum;
    }
```

- after `dayList`, build the pending leaves inside the horizon:

```java
        LocalDate first = p.windows().get(0).start();
        LocalDate last = p.windows().get(p.windows().size() - 1).end();
        List<Object> pendingLeaves = new ArrayList<>();
        for (LeaveRow l : p.data().pendingLeaves()) {
            if (l.employeeId().equals(m.id()) && !l.end().isBefore(first) && !l.start().isAfter(last)) {
                pendingLeaves.add(map("start_date", str(l.start()), "end_date", str(l.end()), "leave_type", l.leaveType(),
                        "absence_hours", l.absenceHours() == null ? null : Numbers.round2(l.absenceHours())));
            }
        }
```

and the returned map gets `"pending_leaves", pendingLeaves` right after `"days", dayList`. Import `LeaveRow`.

`FactsTools.memberCapacity`: add `"planned_hours", plain(row.path("planned_hours"))` after `"absence_hours"`.

- [ ] **Step 4: The skills**

`whf-domain/SKILL.md`:
- Under "Organisation", add a bullet: `- **Open**, said of a member's task, means assigned to them and not finished, whatever its status (To Do, In Progress, In Review). It is not the application's \`Open\` status, which means a task nobody is assigned to yet; those are the team's backlog. Say "assigned to them and not finished" when a leader could read it the other way.`
- "Forecast rows": capacity bullet becomes `- **capacity**: the default week (44 h, 8.8 h per working day) over the window's working days, minus the member's approved leave hours; \`working_days\` and \`absence_hours\` say why it is lower. Nothing else reduces it.` Add `- **planned_hours**: estimated hours of the member's open tasks whose planned week overlaps the window, as the leader planned them in the application; blank planning means zero.`
- "Member facts": append `Each member also carries \`pending_leaves\`, the leave requests inside the horizon that are not approved yet (\`start_date\`, \`end_date\`, \`leave_type\`, \`absence_hours\`): they do not reduce capacity, and a window they touch may lose those hours if approved.`
- "Team and run facts": remove `team.team_capacity (...)`, so it reads `\`team.totals\` (demand and capacity per window), \`projects\` (...)`.
- The task record's `family` list: replace `(delivery, defect, support, analysis, maintenance, other: ...)` with `(delivery, defect, support or container: the type folded into a work family; a sub-task takes its parent's)`.

`whf-forecast-interpretation/SKILL.md` line 9: `- Capacity below 44 h in a window means holidays or approved leave; \`working_days\` and \`absence_hours\` on the row say which. Name the cause. A \`pending_leaves\` entry touching a window is a risk to name: if approved, those hours leave the window.` Add after the `due_hours` bullet: `- \`planned_hours\` above capacity in a window means the plan itself does not fit; say so beside the forecast, without adding the two figures.`

`whf-rebalancing-advice/SKILL.md` rule 2: `... and who are not on leave in that window (their \`absence_hours\` is zero and no \`pending_leaves\` entry touches it).`

`whf-pattern-discovery/SKILL.md` line 21: `| open_tasks, open_est_hours, overdue_open | the member's queue: tasks assigned to them and not finished | overdue_open is greater than 0 |`.

- [ ] **Step 5: Run the three test classes, then the narration tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='FactsBuilderTest,FactsToolsTest,SkillTextsTest,NumberVerifierTest,NarratorTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A server/forecast-core/src
git commit -m "feat(facts): pending leaves and planned hours in the facts; the skills define open and drop the capacity plan"
```

---

### Task 7: the documents

**Files:**
- Modify: `docs/design/2026-09-08-workloadhub-schema-and-feature-matrix.md` (sections 1, 4, 5)
- Modify: `server/README.md` (the seed section and "What the seed writes")
- Modify: `CLAUDE.md` (opening paragraph, "Where the project stands", the read-first list)
- Modify: `docs/backlog.md` ("Java migration")

- [ ] **Step 1: The schema document**

- Section 1's table: `absences` row → `not read since 2026-09-17: legacy; absence is \`personal_leaves\``; `user_capacity` and `team_capacity` rows → `not read since 2026-09-17: capacity is the module's own formula`; `personal_leaves` row → `per leave: start and end date, begin and end time, total absence hours, status, type; the source of absence since 2026-09-17`.
- Section 4: replace its body with two sentences and a pointer: "Superseded on 2026-09-17: capacity is `whf.default-weekly-hours` (44) times the week's confirmed working days over five, minus the member's APPROVED leave hours, a leave being dealt into its working days one full day (8.8 h) at a time; see `docs/superpowers/specs/2026-09-17-personal-leaves-capacity-and-seed-scope-design.md`, sections 2 and 3. `user_capacity` and `team_capacity` are not read."
- Section 5: 5.2 table: `weeks_since_last_arrival` → "blank when never"; `share_self_picked_13w, share_manual_13w, share_project_13w` row → `share_self_picked_13w, share_assigned_13w | whether the member assigned the task to themselves or received it, from the \`user_id\` of the task_history row that assigned it; over the tasks whose assigner is recorded; blank when none`; every share and `reopen_rate_13w` get "blank when there is nothing to measure". 5.3: `open_remaining_hrs` → "estimate minus the hours logged on the task by the row's week end (ruling E of 2026-09-17: the facts read `remaining_estimate_hrs`; the features cannot, it has no history)"; `estimate_ratio_13w` → "blank when unknown". 5.4: delete the `planned_remaining_h`, `team_planned_hrs_h` and `proj_first_due_weeks` rows; `planned_hrs_h{h}` → "estimated hours of the member's open tasks whose `planned_week` is the target week's Monday (back since 2026-09-17)". 5.5: `absence_hrs_h{h}` → "the member's approved leave hours on the target week's working days"; `available_hrs_h{h}` → "capacity of section 4 as superseded". Change "46 columns per horizon" to "45 columns per horizon (40 shared, 5 per horizon) since 2026-09-17". Add a paragraph "Missing values" after the tables with the spec's 5.2 table copied, and a paragraph "Read as of today" with the spec's 5.3 list.

- [ ] **Step 2: The README**

In "Running experiments": replace the step-2 comment with `# 2. a year of history for the real directory: the seed writes projects, tasks, task_history, time_logs and personal_leaves; the application's own tables are read from the export and left alone (the export holds personal data: keep it and the output outside git)`. After the code block's last sentence about `psql`, replace "the target tables must be empty" with: "the script deletes the seeded work tables (time logs, history, tasks, leaves) child-first inside the transaction and upserts projects by id; a database that still holds comments or attachments on old tasks makes the delete fail and nothing is applied. The JSON `import` with an existing database does the same: it replaces only the tables the file carries." In "What the seed writes": replace "Capacity rows follow the application's formula." with "Leaves are written as `personal_leaves` (paid leave blocks, one in five ending on a half day, sick days, and for one member in ten a pending request after the as-of date); no capacity rows are written, the module computes capacity itself." In the `seed` row of the command table append "Real mode writes five tables; synthetic mode writes them all."

- [ ] **Step 3: CLAUDE.md and the backlog**

CLAUDE.md, the opening paragraph: "compares them with capacity (44 h/week default over the working days, minus public holidays and approved personal leaves)". "Read these first": add a bullet for `docs/superpowers/specs/2026-09-17-personal-leaves-capacity-and-seed-scope-design.md`: "**implemented, landed on 2026-09-17** (date it when it lands): `personal_leaves` replaces `absences`, capacity is the calendar and the approved leaves over the 44 h default, `user_capacity` and `team_capacity` are not read, the seed writes five tables in real mode, and the owner's feature-matrix rulings of 2026-09-16 (section 1 of the spec, `docs/design/2026-09-16-feature-matrix-review.html`) are applied." "Where the project stands": add the paragraph for this plan with the gate's totals once known (Task 8 fills the numbers).

`docs/backlog.md`, under "Java migration", three entries:

```markdown
- **Read `tasks.assigned_at` when the application adds it (2026-09-17).** The assignment date is derived from
  the latest `task_history` assignee row (else `created_date`). The application will carry an `assigned_at`
  column later; when it exists, `Lifecycle.assignment` should prefer it and keep today's rule as the fallback.
  Owner's ruling A of 2026-09-16.

- **The assignee history stores full names (2026-09-17).** `task_history.new_value` on an assignee row is the
  member's full name, not the id. Two users with the same name, or a renamed user, make the row unresolvable:
  the task falls back to its creation date and is listed under `data_quality.unresolved_assignments`. If the
  application stored the user id in `new_value` for assignee rows, the fragility would go.

- **Rename the `open_*` facts if narratives confuse the word (2026-09-17).** The application's status `Open`
  means unassigned; the module's `open_tasks` and `open_est_hours` mean assigned and not finished. The skills
  define the word (design 2026-09-17, section 8); if live narratives still say "open" the wrong way, rename the
  facts to `queued_tasks` and `queued_hours` (contract, skills, verifier scopes, tests).
```

Update the backlog's "last updated" date to 2026-09-17.

- [ ] **Step 4: Commit**

```bash
git add docs CLAUDE.md server/README.md
git commit -m "docs: the schema document, the README, CLAUDE.md and the backlog follow the leave switch"
```

---

### Task 8: whole-branch review, the fix wave, the gate

- [ ] **Step 1: Wipe the reports and run the gate**

Run: `rm -rf server/forecast-core/target/surefire-reports && bash scripts/check.sh`
Expected: `OK server (mvn verify)`. Read the totals: `python3 - <<'EOF'` summing `tests`, `failures`, `errors`, `skipped` over `server/forecast-core/target/surefire-reports/TEST-*.xml`. Zero failures and errors.

- [ ] **Step 2: Review the branch as a whole**

Dispatch a code-reviewer subagent over `git diff 89eee18..HEAD` with the spec, asking for: any remaining read of `absences`, `user_capacity` or `team_capacity`; any place a "nothing to measure" cell still receives a number; the leave deal-out against the spec's eight examples; the SQL writer's delete list against `TABLE_ORDER`; the assigner on every seeded assignee row; the skills' definition of open. Fix what it finds in one wave, re-run the affected classes, then the gate once more.

- [ ] **Step 3: Close the plan**

Append "Closing notes" to this plan: the gate's totals, the deviations from the spec (if any) with the reason, and what was left to the backlog. Put the totals in CLAUDE.md's paragraph. Commit:

```bash
git add -A
git commit -m "docs: closing notes of the personal leaves plan and the gate's totals"
```

Push `dev`. `main` is fast-forwarded by the owner's decision, not by this plan.
