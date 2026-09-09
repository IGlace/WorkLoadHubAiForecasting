package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeedCalendarTest {

    static LinkedHashMap<String, Object> holiday(String title, String start, String end, String status, String type) {
        LinkedHashMap<String, Object> h = new LinkedHashMap<>();
        h.put("id", java.util.UUID.nameUUIDFromBytes((title + start).getBytes()).toString());
        h.put("title", title);
        h.put("type", type);
        h.put("status", status);
        h.put("active", true);
        h.put("start_date", start);
        h.put("end_date", end);
        h.put("country_code", "MA");
        h.put("created_at", "2026-09-03T13:59:58");
        h.put("updated_at", "2026-09-03T13:59:58");
        return h;
    }

    static SeedConfig cfg() {
        return new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);
    }

    @Test
    void confirmedHolidaysAreNotWorkingDaysPendingOnesAre() {
        SeedCalendar cal = SeedCalendar.fromHolidayRows(List.of(
                holiday("Labour Day", "2026-05-01", "2026-05-01", "CONFIRMED", "NATIONAL"),
                holiday("Eid", "2026-03-20", "2026-03-21", "PENDING", "RELIGIOUS")), cfg());
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 1)));
        assertTrue(cal.isWorkingDay(LocalDate.of(2026, 3, 20)));
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 5, 2)), "Saturday");
        assertEquals(4, cal.workingDays(LocalDate.of(2026, 4, 27)));
        assertEquals(5, cal.workingDays(LocalDate.of(2026, 3, 16)));
    }

    @Test
    void replicatesNationalHolidaysIntoYearsWithoutAny() {
        SeedCalendar cal = SeedCalendar.fromHolidayRows(List.of(
                holiday("Independence Day", "2026-11-18", "2026-11-18", "CONFIRMED", "NATIONAL"),
                holiday("Eid", "2026-03-20", "2026-03-21", "CONFIRMED", "RELIGIOUS")), cfg());
        assertFalse(cal.isWorkingDay(LocalDate.of(2025, 11, 18)), "national holiday replicated into 2025");
        assertTrue(cal.isWorkingDay(LocalDate.of(2025, 3, 20)), "religious holidays move; not replicated");
        assertEquals(3, cal.holidayRows().size());
        assertTrue(cal.holidayRows().stream().anyMatch(h -> "2025-11-18".equals(h.get("start_date"))));
    }

    @Test
    void twoRowsOfTheSameAnnualHolidayReplicateWithoutDuplicates() {
        // "Labour Day" 2025 and 2026 both replicate into the missing year 2024 at the same shifted date:
        // without dedupe that is two rows with an identical (title, start_date, end_date, country_code).
        SeedCalendar cal = SeedCalendar.fromHolidayRows(List.of(
                holiday("Labour Day", "2025-05-01", "2025-05-01", "CONFIRMED", "NATIONAL"),
                holiday("Labour Day", "2026-05-01", "2026-05-01", "CONFIRMED", "NATIONAL")),
                new SeedConfig(104, LocalDate.of(2026, 9, 6), 1, false, 0));
        List<LinkedHashMap<String, Object>> rows2024 = cal.holidayRows().stream()
                .filter(h -> "2024-05-01".equals(h.get("start_date"))).toList();
        assertEquals(1, rows2024.size(), "exactly one 2024 replica: " + rows2024);
        java.util.Set<List<Object>> keys = new java.util.HashSet<>();
        for (LinkedHashMap<String, Object> h : cal.holidayRows()) {
            List<Object> key = List.of(h.get("title"), h.get("start_date"), h.get("end_date"), h.get("country_code"));
            assertTrue(keys.add(key), "duplicate (title, start_date, end_date, country_code): " + key);
        }
    }

    @Test
    void replicationOfAYearBoundaryHolidayShiftsBothEndsTogether() {
        SeedCalendar cal = SeedCalendar.fromHolidayRows(List.of(
                holiday("New Year", "2026-12-31", "2027-01-02", "CONFIRMED", "NATIONAL")), cfg());
        assertFalse(cal.isWorkingDay(LocalDate.of(2025, 12, 31)), "replica for 2025 spanning into 2026");
        assertFalse(cal.isWorkingDay(LocalDate.of(2026, 1, 2)), "replica for 2025 spanning into 2026");
        for (LinkedHashMap<String, Object> h : cal.holidayRows()) {
            LocalDate start = LocalDate.parse((String) h.get("start_date"));
            LocalDate end = LocalDate.parse((String) h.get("end_date"));
            assertFalse(start.isAfter(end), "start_date after end_date: " + h);
        }
        // 2026 and 2027 are already covered by the original row's range; only 2025 is missing.
        assertEquals(2, cal.holidayRows().size());
    }
}
