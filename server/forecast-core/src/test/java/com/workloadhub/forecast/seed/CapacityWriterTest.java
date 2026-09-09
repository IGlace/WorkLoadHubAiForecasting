package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapacityWriterTest {

    @Test
    void userCapacityRowsFollowTheFormulaForEveryEmployedWeek() {
        SeedConfig cfg = new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);
        SeedCalendar cal = AbsencePlannerTest.cal();
        Person p = AbsencePlannerTest.person(LocalDate.of(2026, 3, 2), null);
        AbsencePlanner.Plan plan = AbsencePlanner.plan(p, cal, cfg, new SeedRandom(5));
        List<LinkedHashMap<String, Object>> rows = CapacityWriter.userCapacity(p, plan, cal, cfg, new SeedRandom(6));
        long weeksEmployed = cfg.mondays().stream().filter(m -> !m.isBefore(LocalDate.of(2026, 3, 2))).count();
        assertEquals(weeksEmployed, rows.size());
        for (LinkedHashMap<String, Object> r : rows) {
            LocalDate monday = LocalDate.parse((String) r.get("week_start"));
            double base = (Double) r.get("base_capacity_hrs");
            double absence = (Double) r.get("absence_hrs");
            double available = (Double) r.get("available_hrs");
            assertEquals(40.0, base);
            assertEquals(plan.absenceHours(monday), absence, 1e-9);
            assertEquals(base * cal.workingDays(monday) / 5.0 - absence, available, 1e-9);
            assertTrue(available >= 0);
        }
    }

    @Test
    void teamCapacitySumsMembersAndTakesAllocatedFromTheCallback() {
        SeedConfig cfg = new SeedConfig(8, LocalDate.of(2026, 9, 6), 1, false, 0);
        SeedCalendar cal = AbsencePlannerTest.cal();
        Person a = AbsencePlannerTest.person(cfg.firstMonday(), null);
        Person b = new Person(UUID.fromString("30000000-0000-0000-0000-000000000004"), "Eng Four", "f@example.test",
                "Calibration Engineer", "PTE / CT2", "CT2", null, "MEMBER", WorkFamily.CALIBRATION, cfg.firstMonday(), null);
        var planA = AbsencePlanner.plan(a, cal, cfg, new SeedRandom(1));
        var planB = AbsencePlanner.plan(b, cal, cfg, new SeedRandom(2));
        var rowsA = CapacityWriter.userCapacity(a, planA, cal, cfg, new SeedRandom(3));
        var rowsB = CapacityWriter.userCapacity(b, planB, cal, cfg, new SeedRandom(4));
        Team team = new Team(UUID.randomUUID(), "CT2 · X", a.id(), null, List.of(a.id(), b.id()), false, "CT2");
        var rows = CapacityWriter.teamCapacity(team, Map.of(a.id(), rowsA, b.id(), rowsB), (id, monday) -> 12.0, cfg, new SeedRandom(5));
        assertEquals(8, rows.size());
        for (LinkedHashMap<String, Object> r : rows) {
            LocalDate monday = LocalDate.parse((String) r.get("week_start"));
            double expected = availableOf(rowsA, monday) + availableOf(rowsB, monday);
            assertEquals(expected, (Double) r.get("total_capacity_hrs"), 1e-9);
            assertEquals(24.0, (Double) r.get("allocated_hrs"), 1e-9);
            assertEquals(team.id().toString(), r.get("team_id"));
        }
    }

    static double availableOf(List<LinkedHashMap<String, Object>> rows, LocalDate monday) {
        return rows.stream().filter(r -> monday.toString().equals(r.get("week_start")))
                .mapToDouble(r -> (Double) r.get("available_hrs")).sum();
    }
}
