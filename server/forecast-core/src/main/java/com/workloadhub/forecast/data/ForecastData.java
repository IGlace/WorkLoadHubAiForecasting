package com.workloadhub.forecast.data;

import com.workloadhub.forecast.data.rows.AbsenceRow;
import com.workloadhub.forecast.data.rows.CapacityRow;
import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.data.rows.UserRef;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/** Everything a run reads, loaded once, immutable, with the lookups the rules need. */
public record ForecastData(
        List<MemberRow> members,
        List<TeamRow> teams,
        List<ProjectRow> projects,
        List<TaskRow> tasks,
        List<TransitionRow> transitions,
        List<TimeLogRow> timeLogs,
        List<CapacityRow> capacity,
        List<AbsenceRow> absences,
        List<HolidayRow> holidays,
        List<UserRef> users,
        Map<String, String> statusCategoryByName) {

    private static final Comparator<UUID> BY_ID = Comparator.comparing(UUID::toString);

    public ForecastData {
        members = sortedBy(members, MemberRow::id);
        teams = sortedBy(teams, TeamRow::id);
        projects = sortedBy(projects, ProjectRow::id);
        tasks = sortedBy(tasks, TaskRow::id);
        transitions = List.copyOf(transitions.stream()
                .sorted(Comparator.comparing(TransitionRow::changedAt).thenComparing(t -> t.taskId().toString())).toList());
        timeLogs = List.copyOf(timeLogs.stream()
                .sorted(Comparator.comparing(TimeLogRow::day).thenComparing(l -> l.taskId().toString())).toList());
        capacity = List.copyOf(capacity.stream()
                .sorted(Comparator.<CapacityRow, String>comparing(c -> c.userId().toString()).thenComparing(CapacityRow::weekStart))
                .toList());
        absences = List.copyOf(absences.stream()
                .sorted(Comparator.<AbsenceRow, String>comparing(a -> a.userId().toString()).thenComparing(AbsenceRow::day))
                .toList());
        holidays = List.copyOf(holidays.stream()
                .sorted(Comparator.comparing(HolidayRow::start)
                        .thenComparing(HolidayRow::end)
                        .thenComparing(HolidayRow::title, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
        users = sortedBy(users, UserRef::id);
        statusCategoryByName = Map.copyOf(statusCategoryByName);
    }

    private static <T> List<T> sortedBy(List<T> items, Function<T, UUID> id) {
        return List.copyOf(items.stream().sorted(Comparator.comparing(id, BY_ID)).toList());
    }

    public Map<UUID, MemberRow> memberById() {
        return index(members, MemberRow::id);
    }

    public Map<UUID, TaskRow> taskById() {
        return index(tasks, TaskRow::id);
    }

    public Map<UUID, ProjectRow> projectById() {
        return index(projects, ProjectRow::id);
    }

    public Map<UUID, TeamRow> teamById() {
        return index(teams, TeamRow::id);
    }

    public Map<UUID, UserRef> userById() {
        return index(users, UserRef::id);
    }

    private static <T> Map<UUID, T> index(List<T> items, Function<T, UUID> id) {
        Map<UUID, T> out = new LinkedHashMap<>();
        for (T item : items) {
            out.put(id.apply(item), item);
        }
        return out;
    }

    public List<MemberRow> membersOfTeam(UUID teamId) {
        return members.stream().filter(m -> m.teamIds().contains(teamId)).toList();
    }

    public Map<UUID, List<TransitionRow>> transitionsByTask() {
        Map<UUID, List<TransitionRow>> out = new TreeMap<>(BY_ID);
        for (TransitionRow t : transitions) {
            out.computeIfAbsent(t.taskId(), k -> new ArrayList<>()).add(t);
        }
        return out;
    }

    public Map<UUID, List<TimeLogRow>> logsByTask() {
        Map<UUID, List<TimeLogRow>> out = new TreeMap<>(BY_ID);
        for (TimeLogRow l : timeLogs) {
            out.computeIfAbsent(l.taskId(), k -> new ArrayList<>()).add(l);
        }
        return out;
    }

    /** Projects owned by the team or by its parent team (a department's projects feed its manager teams). */
    public Set<UUID> projectIdsOfTeamAndParent(UUID teamId) {
        TeamRow team = teamById().get(teamId);
        Set<UUID> owners = new TreeSet<>(BY_ID);
        owners.add(teamId);
        if (team != null && team.parentId() != null) {
            owners.add(team.parentId());
        }
        Set<UUID> out = new TreeSet<>(BY_ID);
        for (ProjectRow p : projects) {
            if (p.teamId() != null && owners.contains(p.teamId())) {
                out.add(p.id());
            }
        }
        return out;
    }
}
