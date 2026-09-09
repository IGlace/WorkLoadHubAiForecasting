package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleBiFunction;

/** user_capacity and team_capacity rows, one per week, as the application computes them. */
public final class CapacityWriter {

    public static final double BASE_HOURS = 40.0;

    private CapacityWriter() {
    }

    public static List<LinkedHashMap<String, Object>> userCapacity(Person p, AbsencePlanner.Plan plan, SeedCalendar cal,
            SeedConfig cfg, SeedRandom rnd) {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (LocalDate monday : cfg.mondays()) {
            boolean employed = false;
            for (int i = 0; i < 7; i++) {
                employed |= p.employedOn(monday.plusDays(i));
            }
            if (!employed) {
                continue;
            }
            double absence = plan.absenceHours(monday);
            double available = BASE_HOURS * cal.workingDays(monday) / 5.0 - absence;
            String stamp = monday.atTime(6, 0).toString();
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("id", rnd.uuid().toString());
            r.put("user_id", p.id().toString());
            r.put("created_at", stamp);
            r.put("updated_at", stamp);
            r.put("week_start", monday.toString());
            r.put("absence_hrs", absence);
            r.put("available_hrs", Math.max(0.0, available));
            r.put("base_capacity_hrs", BASE_HOURS);
            rows.add(r);
        }
        return rows;
    }

    public static List<LinkedHashMap<String, Object>> teamCapacity(Team t, Map<UUID, List<LinkedHashMap<String, Object>>> userRowsByMember,
            ToDoubleBiFunction<UUID, LocalDate> allocated, SeedConfig cfg, SeedRandom rnd) {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (LocalDate monday : cfg.mondays()) {
            double total = 0;
            double alloc = 0;
            for (UUID member : t.memberIds()) {
                for (LinkedHashMap<String, Object> r : userRowsByMember.getOrDefault(member, List.of())) {
                    if (monday.toString().equals(r.get("week_start"))) {
                        total += (Double) r.get("available_hrs");
                    }
                }
                alloc += allocated.applyAsDouble(member, monday);
            }
            String stamp = monday.atTime(6, 0).toString();
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("allocated_hrs", alloc);
            r.put("total_capacity_hrs", total);
            r.put("week_start", monday.toString());
            r.put("created_at", stamp);
            r.put("updated_at", stamp);
            r.put("id", rnd.uuid().toString());
            r.put("team_id", t.id().toString());
            rows.add(r);
        }
        return rows;
    }
}
