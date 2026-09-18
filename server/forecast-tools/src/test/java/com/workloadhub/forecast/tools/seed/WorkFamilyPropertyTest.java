package com.workloadhub.forecast.tools.seed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Property;

/**
 * The seed's whole purpose (design 2026-09-15, section 3.1): a member-week can land above capacity and a
 * member-week can land well below it, both inside a single team, so overload and rebalancing are observable
 * at all. `SeedGeneratorTest.loggedHoursTrackAssignedEstimatesAndNeverExceedPresence` already proves the
 * upper bound; this is the missing lower half, checked as a population-level rate rather than a bare
 * existence check: an event week (`Rhythm.EVENT_FACTOR` = 1.4) combined with a high personal factor (up to
 * 1.3) can already push a single member-week over capacity even under the pre-2026-09-15 constants, so "at
 * least one member-week above capacity" does not by itself discriminate a corrected seed from an
 * uncorrected one. A corrected seed is expected to put a meaningful fraction of member-weeks over capacity,
 * not one rare tail hit — the thresholds below come from the design's own arithmetic (section 3.1's table:
 * typical logged hours moves from ~28h under the old constants to a median near 37h under the new ones),
 * with margin on both sides. Fixture size: 120 users, 52 weeks, seed 7 — the same population task 1b
 * rebuilds the database from.
 */
class WorkFamilyPropertyTest {

    private static final SeedConfig CFG = new SeedConfig(52, LocalDate.of(2026, 9, 6), 7, true, 120);

    @Property(tries = 1)
    void memberWeeksSpanCapacityInBothDirectionsWithinATeam() {
        ExportEnvelope env = SeedGenerator.generate(null, CFG);
        Map<String, String> teamOf = new HashMap<>();
        for (var tm : env.rows("team_members")) {
            teamOf.put((String) tm.get("user_id"), (String) tm.get("team_id"));
        }
        Map<String, Map<String, Double>> perMemberWeek = new HashMap<>();
        for (var l : env.rows("time_logs")) {
            String user = (String) l.get("user_id");
            LocalDate day = LocalDate.parse((String) l.get("log_date"));
            String week = SeedConfig.mondayOf(day).toString();
            perMemberWeek.computeIfAbsent(user, k -> new HashMap<>()).merge(week, (Double) l.get("hours"), Double::sum);
        }
        int totalWeeks = 0;
        int overCapacity = 0;
        int underThreshold = 0;
        double sum = 0;
        Set<String> overloadedTeams = new HashSet<>();
        Set<String> underloadedTeams = new HashSet<>();
        for (var e : perMemberWeek.entrySet()) {
            String team = teamOf.get(e.getKey());
            for (double hours : e.getValue().values()) {
                totalWeeks++;
                sum += hours;
                if (hours > AbsencePlanner.BASE_HOURS) {
                    overCapacity++;
                    if (team != null) {
                        overloadedTeams.add(team);
                    }
                } else if (hours < 0.7 * AbsencePlanner.BASE_HOURS) {
                    underThreshold++;
                    if (team != null) {
                        underloadedTeams.add(team);
                    }
                }
            }
        }
        double mean = sum / totalWeeks;
        double overRate = (double) overCapacity / totalWeeks;
        assertTrue(mean > 27.5, "mean member-week " + mean + " h over " + totalWeeks + " weeks -- too close to the old ~23-28h supply");
        assertTrue(overRate > 0.03, "only " + overCapacity + "/" + totalWeeks + " (" + overRate + ") member-weeks exceeded capacity -- not a systematic shift");
        assertTrue(underThreshold > 0, "no member-week fell below 70% of capacity");
        overloadedTeams.retainAll(underloadedTeams);
        assertTrue(!overloadedTeams.isEmpty(), "no team held both an overloaded and an underloaded member-week");
    }
}
