package com.workloadhub.forecast.model;

import com.workloadhub.forecast.calendar.HourPlacement;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.features.MemberWeek;
import com.workloadhub.forecast.lifecycle.Family;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** How long tasks take and how estimates deviate, per member with shrinkage toward the team, and where hours land. */
public final class EffortModel {

    public static final double SHRINK_K = 5.0;
    public static final double RATIO_MIN = 0.5;
    public static final double RATIO_MAX = 2.5;
    public static final double DEFAULT_CYCLE_DAYS = 5.0;

    private record Stat(int n, double value) {
    }

    private record MemberFamily(UUID member, Family family) {
    }

    private record TeamFamily(UUID team, Family family) {
    }

    private final Map<MemberFamily, Stat> ratioMemberFamily = new HashMap<>();
    private final Map<UUID, Stat> ratioMember = new HashMap<>();
    private final Map<TeamFamily, Double> ratioTeamFamily = new HashMap<>();
    private final Map<UUID, Double> ratioTeam = new HashMap<>();
    private double ratioGlobal = 1.0;
    private final Map<MemberFamily, Stat> cycleMemberFamily = new HashMap<>();
    private final Map<UUID, Stat> cycleMember = new HashMap<>();
    private final Map<TeamFamily, Double> cycleTeamFamily = new HashMap<>();
    private final Map<UUID, Double> cycleTeam = new HashMap<>();
    private double cycleGlobal = DEFAULT_CYCLE_DAYS;
    private final Map<UUID, Double> latenessMember = new HashMap<>();
    private final Map<UUID, Double> latenessTeam = new HashMap<>();
    private double latenessGlobal = 0.0;

    private EffortModel() {
    }

    public static EffortModel fit(Lifecycle lc, ForecastData data) {
        EffortModel m = new EffortModel();
        Map<UUID, MemberRow> members = data.memberById();
        Map<MemberFamily, List<Double>> rMf = new HashMap<>();
        Map<UUID, List<Double>> rM = new HashMap<>();
        Map<TeamFamily, List<Double>> rTf = new HashMap<>();
        Map<UUID, List<Double>> rT = new HashMap<>();
        List<Double> rG = new ArrayList<>();
        Map<MemberFamily, List<Double>> cMf = new HashMap<>();
        Map<UUID, List<Double>> cM = new HashMap<>();
        Map<TeamFamily, List<Double>> cTf = new HashMap<>();
        Map<UUID, List<Double>> cT = new HashMap<>();
        List<Double> cG = new ArrayList<>();
        Map<UUID, List<Double>> lM = new HashMap<>();
        Map<UUID, List<Double>> lT = new HashMap<>();
        List<Double> lG = new ArrayList<>();
        for (TaskFacts f : lc.all()) {
            MemberRow member = f.assignee() == null ? null : members.get(f.assignee());
            if (member == null || f.finished() == null || f.estimate() <= 0 || f.actualHours() <= 0 || f.cycleDays() == null) {
                continue;
            }
            UUID team = member.primaryTeamId();
            double ratio = f.actualHours() / f.estimate();
            double cycle = f.cycleDays();
            MemberFamily mf = new MemberFamily(member.id(), f.family());
            TeamFamily tf = new TeamFamily(team, f.family());
            rMf.computeIfAbsent(mf, k -> new ArrayList<>()).add(ratio);
            rM.computeIfAbsent(member.id(), k -> new ArrayList<>()).add(ratio);
            rTf.computeIfAbsent(tf, k -> new ArrayList<>()).add(ratio);
            rT.computeIfAbsent(team, k -> new ArrayList<>()).add(ratio);
            rG.add(ratio);
            cMf.computeIfAbsent(mf, k -> new ArrayList<>()).add(cycle);
            cM.computeIfAbsent(member.id(), k -> new ArrayList<>()).add(cycle);
            cTf.computeIfAbsent(tf, k -> new ArrayList<>()).add(cycle);
            cT.computeIfAbsent(team, k -> new ArrayList<>()).add(cycle);
            cG.add(cycle);
            Integer late = f.latenessDays();
            if (late != null) {
                lM.computeIfAbsent(member.id(), k -> new ArrayList<>()).add((double) late);
                lT.computeIfAbsent(team, k -> new ArrayList<>()).add((double) late);
                lG.add((double) late);
            }
        }
        if (!rG.isEmpty()) {
            m.ratioGlobal = mean(rG);
            m.cycleGlobal = median(cG);
        }
        if (!lG.isEmpty()) {
            m.latenessGlobal = median(lG);
        }
        rMf.forEach((k, v) -> m.ratioMemberFamily.put(k, new Stat(v.size(), mean(v))));
        rM.forEach((k, v) -> m.ratioMember.put(k, new Stat(v.size(), mean(v))));
        rTf.forEach((k, v) -> m.ratioTeamFamily.put(k, mean(v)));
        rT.forEach((k, v) -> m.ratioTeam.put(k, mean(v)));
        cMf.forEach((k, v) -> m.cycleMemberFamily.put(k, new Stat(v.size(), median(v))));
        cM.forEach((k, v) -> m.cycleMember.put(k, new Stat(v.size(), median(v))));
        cTf.forEach((k, v) -> m.cycleTeamFamily.put(k, median(v)));
        cT.forEach((k, v) -> m.cycleTeam.put(k, median(v)));
        lM.forEach((k, v) -> m.latenessMember.put(k, median(v)));
        lT.forEach((k, v) -> m.latenessTeam.put(k, median(v)));
        return m;
    }

    public static double shrink(int n, double mean, double prior, double k) {
        return (n * mean + k * prior) / (n + k);
    }

    static double mean(List<Double> v) {
        return v.stream().mapToDouble(Double::doubleValue).average().orElse(Double.NaN);
    }

    static double median(List<Double> v) {
        List<Double> s = v.stream().sorted().toList();
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    public double estimateRatio(UUID member, Family family, UUID team) {
        double teamPrior = ratioTeam.getOrDefault(team, ratioGlobal);
        double prior;
        Stat own;
        if (family != null) {
            prior = ratioTeamFamily.getOrDefault(new TeamFamily(team, family), teamPrior);
            own = ratioMemberFamily.getOrDefault(new MemberFamily(member, family), new Stat(0, prior));
        } else {
            prior = teamPrior;
            own = ratioMember.getOrDefault(member, new Stat(0, prior));
        }
        return Math.clamp(shrink(own.n(), own.value(), prior, SHRINK_K), RATIO_MIN, RATIO_MAX);
    }

    public double memberCycleDays(UUID member, UUID team) {
        double prior = cycleTeam.getOrDefault(team, cycleGlobal);
        Stat own = cycleMember.getOrDefault(member, new Stat(0, prior));
        return Math.max(1.0, shrink(own.n(), own.value(), prior, SHRINK_K));
    }

    public double familyCycleDays(UUID member, Family family, UUID team) {
        double prior = cycleTeamFamily.getOrDefault(new TeamFamily(team, family), memberCycleDays(member, team));
        Stat own = cycleMemberFamily.getOrDefault(new MemberFamily(member, family), new Stat(0, prior));
        return Math.max(1.0, shrink(own.n(), own.value(), prior, SHRINK_K));
    }

    public double memberLatenessDays(UUID member, UUID team) {
        return latenessMember.getOrDefault(member, latenessTeam.getOrDefault(team, latenessGlobal));
    }

    public static SortedMap<MemberWeek, Double> placeOpenTasks(List<TaskFacts> open, EffortModel model, LocalDate placementStart,
            Function<UUID, UUID> teamOf, Function<UUID, Set<LocalDate>> offDaysOf, WorkingCalendar cal) {
        SortedMap<MemberWeek, Double> out = new TreeMap<>();
        for (TaskFacts t : open) {
            UUID member = t.assignee();
            UUID team = teamOf.apply(member);
            double hours = t.remaining() != null ? t.remaining()
                    : Math.max(0.0, t.estimate() * model.estimateRatio(member, t.family(), team) - t.actualHours());
            if (hours <= 0) {
                continue;
            }
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
            HourPlacement.placeHours(hours, start, end, cal, offDaysOf.apply(member))
                    .forEach((week, h) -> out.merge(new MemberWeek(member, week), h, Double::sum));
        }
        return out;
    }

    public static SortedMap<MemberWeek, Double> placeNewArrivals(Map<MemberWeek, Double> predictedEst, EffortModel model,
            Function<UUID, UUID> teamOf, Function<UUID, Set<LocalDate>> offDaysOf, WorkingCalendar cal) {
        SortedMap<MemberWeek, Double> out = new TreeMap<>();
        for (Map.Entry<MemberWeek, Double> e : new TreeMap<>(predictedEst).entrySet()) {
            UUID member = e.getKey().member();
            UUID team = teamOf.apply(member);
            double hours = e.getValue() * model.estimateRatio(member, null, team);
            int span = (int) Math.round(model.memberCycleDays(member, team));
            LocalDate start = e.getKey().week();
            LocalDate end = start.plusDays(Math.max(span - 1, 0));
            HourPlacement.placeHours(hours, start, end, cal, offDaysOf.apply(member))
                    .forEach((week, h) -> out.merge(new MemberWeek(member, week), h, Double::sum));
        }
        return out;
    }
}
