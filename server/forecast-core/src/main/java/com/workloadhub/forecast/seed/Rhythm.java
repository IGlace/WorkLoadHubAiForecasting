package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The weekly target hours of each member and the arrivals that realise it (design section 4.4). */
public final class Rhythm {

    private static final int RAMP_WEEKS = 6;
    private static final double EVENT_FACTOR = 1.4;
    private static final double ESTIMATE_SIGMA = 0.6;

    private final SeedConfig cfg;
    private final SeedCalendar cal;
    private final Map<UUID, Person> people;
    private final Map<UUID, AbsencePlanner.Plan> plans;
    private final List<Team> teams;
    private final SeedRandom rnd;
    private final Map<UUID, Double> baseFactor = new HashMap<>();
    private final Map<UUID, List<LocalDate>> eventWeeks = new HashMap<>();
    private final Map<UUID, Team> teamOf = new HashMap<>();

    public Rhythm(SeedConfig cfg, SeedCalendar cal, Map<UUID, Person> people, Map<UUID, AbsencePlanner.Plan> plans,
            List<Team> teams, SeedRandom rnd) {
        this.cfg = cfg;
        this.cal = cal;
        this.people = people;
        this.plans = plans;
        this.teams = teams;
        this.rnd = rnd;
        List<UUID> ids = new ArrayList<>(people.keySet());
        ids.sort(null);
        for (UUID id : ids) {
            baseFactor.put(id, Math.max(0.5, Math.min(1.3, 1.0 + 0.15 * rnd.gaussian())));
        }
        List<LocalDate> mondays = cfg.mondays();
        int events = Math.max(1, Math.round(3f * cfg.weeks() / 52f));
        for (Team t : teams) {
            List<LocalDate> weeks = new ArrayList<>();
            for (int e = 0; e < events; e++) {
                LocalDate start = mondays.get(rnd.between(0, mondays.size() - 1));
                weeks.add(start);
                weeks.add(start.plusWeeks(1));
            }
            eventWeeks.put(t.id(), weeks);
            for (UUID member : t.memberIds()) {
                // a manager team wins over a department team for people in both
                if (!teamOf.containsKey(member) || teamOf.get(member).department()) {
                    teamOf.put(member, t);
                }
            }
        }
    }

    public SeedCalendar calendar() {
        return cal;
    }

    public Team teamOf(Person p) {
        return teamOf.get(p.id());
    }

    /** family mean × personal factor, halved for team leaders. */
    public double base(Person p) {
        double b = p.family().weeklyHours * baseFactor.getOrDefault(p.id(), 1.0);
        return p.role().equals("TEAM_LEADER") ? b * 0.5 : b;
    }

    public static double season(LocalDate monday, SeedCalendar cal) {
        int week = monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        if (week >= 31 && week <= 34) {
            return 0.55;
        }
        if (week == 52 || week == 1) {
            return 0.50;
        }
        return cal.workingDays(monday) < 5 ? 0.85 : 1.0;
    }

    public double ramp(Person p, LocalDate monday) {
        if (!p.joined().isAfter(cfg.firstMonday())) {
            return 1.0;
        }
        long weeks = ChronoUnit.WEEKS.between(SeedConfig.mondayOf(p.joined()), monday);
        if (weeks < 0) {
            return 0.0;
        }
        return Math.min(1.0, weeks / (double) RAMP_WEEKS);
    }

    public double event(UUID teamId, LocalDate monday) {
        return eventWeeks.getOrDefault(teamId, List.of()).contains(monday) ? EVENT_FACTOR : 1.0;
    }

    public double availability(Person p, LocalDate monday) {
        AbsencePlanner.Plan plan = plans.get(p.id());
        int present = 0;
        for (int i = 0; i < 5; i++) {
            if (plan.hoursPresent(p, monday.plusDays(i)) > 0) {
                present++;
            }
        }
        return present / 5.0;
    }

    public double target(Person p, LocalDate monday) {
        Team t = teamOf(p);
        return base(p) * season(monday, cal) * ramp(p, monday) * (t == null ? 1.0 : event(t.id(), monday))
                * availability(p, monday);
    }

    /**
     * The Poisson rate divides by the estimate distribution's mean, not its median: `estimate` draws are
     * log-normal, whose mean sits exp(sigma^2/2) above the family median, so sizing arrivals off the raw
     * median would inflate the realised hours by that same factor over many weeks.
     */
    public int arrivals(Person p, LocalDate monday) {
        double meanEstimate = p.family().medianEstimate * Math.exp(ESTIMATE_SIGMA * ESTIMATE_SIGMA / 2.0);
        return rnd.poisson(target(p, monday) / meanEstimate);
    }

    /** Log-normal around the family median, half-hour steps, at least one hour. */
    public double estimate(Person p) {
        double e = rnd.lognormal(p.family().medianEstimate, ESTIMATE_SIGMA);
        return Math.max(1.0, Math.round(e * 2) / 2.0);
    }
}
