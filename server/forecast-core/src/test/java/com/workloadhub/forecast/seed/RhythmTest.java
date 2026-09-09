package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RhythmTest {

    static final SeedConfig CFG = new SeedConfig(52, LocalDate.of(2026, 9, 6), 1, false, 0);

    static Rhythm rhythm(Person p, long seed) {
        SeedCalendar cal = AbsencePlannerTest.cal();
        Map<UUID, Person> people = new HashMap<>();
        people.put(p.id(), p);
        Map<UUID, AbsencePlanner.Plan> plans = new HashMap<>();
        plans.put(p.id(), AbsencePlanner.plan(p, cal, CFG, new SeedRandom(seed)));
        Team team = new Team(UUID.randomUUID(), "CT2 · X", p.id(), null, List.of(p.id()), false, "CT2");
        return new Rhythm(CFG, cal, people, plans, List.of(team), new SeedRandom(seed));
    }

    @Test
    void seasonFollowsTheDesign() {
        SeedCalendar cal = AbsencePlannerTest.cal();
        assertEquals(0.55, Rhythm.season(LocalDate.of(2026, 8, 3), cal), 1e-9);   // ISO week 32
        assertEquals(0.50, Rhythm.season(LocalDate.of(2025, 12, 29), cal), 1e-9); // ISO week 1 of 2026
        assertEquals(0.85, Rhythm.season(LocalDate.of(2026, 4, 27), cal), 1e-9);  // Labour Day in the week
        assertEquals(1.0, Rhythm.season(LocalDate.of(2026, 3, 16), cal), 1e-9);
    }

    @Test
    void targetIsBaseTimesFactorsAndLeadersAreHalved() {
        Person eng = AbsencePlannerTest.person(CFG.firstMonday(), null);
        Rhythm r = rhythm(eng, 9);
        LocalDate week = LocalDate.of(2026, 3, 16);
        double t = r.target(eng, week);
        double expected = r.base(eng) * Rhythm.season(week, r.calendar()) * r.ramp(eng, week) * r.event(r.teamOf(eng).id(), week) * r.availability(eng, week);
        assertEquals(expected, t, 1e-9);
        assertTrue(r.base(eng) >= 32 * 0.5 && r.base(eng) <= 32 * 1.3);
        Person lead = eng.withRole("TEAM_LEADER");
        assertEquals(r.base(eng) * 0.5, r.base(lead), 1e-9);
    }

    @Test
    void newcomersRampOverSixWeeks() {
        Person late = AbsencePlannerTest.person(LocalDate.of(2026, 3, 2), null);
        Rhythm r = rhythm(late, 2);
        assertEquals(0.0, r.ramp(late, LocalDate.of(2026, 2, 23)), 1e-9);
        assertEquals(1.0 / 6, r.ramp(late, LocalDate.of(2026, 3, 9)), 1e-9);
        assertEquals(1.0, r.ramp(late, LocalDate.of(2026, 5, 4)), 1e-9);
    }

    @Test
    void arrivalsMatchTheTargetOverManyWeeks() {
        Person eng = AbsencePlannerTest.person(CFG.firstMonday(), null);
        Rhythm r = rhythm(eng, 5);
        double targetSum = 0;
        double estimateSum = 0;
        for (LocalDate m : CFG.mondays()) {
            targetSum += r.target(eng, m);
            int n = r.arrivals(eng, m);
            for (int i = 0; i < n; i++) {
                estimateSum += r.estimate(eng);
            }
        }
        assertTrue(Math.abs(estimateSum - targetSum) < 0.25 * targetSum, "estimates " + estimateSum + " target " + targetSum);
        double oneEstimate = r.estimate(eng);
        assertTrue(oneEstimate >= 1.0 && oneEstimate * 2 == Math.floor(oneEstimate * 2), "half-hour steps");
    }
}
