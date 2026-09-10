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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Everything a run reads, loaded once, immutable, with the lookups the rules need.
 *
 * <p>Not a record: the by-id and by-task indexes below are built once, in the constructor, and handed out by
 * reference from then on, which a record's fixed component list cannot hold without exposing them as public
 * components (and so as part of equals/hashCode/toString). Field-for-field this is the record's data: same
 * accessor names, same immutability, same normalisation (every list sorted, defensively copied).
 */
public final class ForecastData {

    private final List<MemberRow> members;
    private final List<TeamRow> teams;
    private final List<ProjectRow> projects;
    private final List<TaskRow> tasks;
    private final List<TransitionRow> transitions;
    private final List<TimeLogRow> timeLogs;
    private final List<CapacityRow> capacity;
    private final List<AbsenceRow> absences;
    private final List<HolidayRow> holidays;
    private final List<UserRef> users;
    private final Map<String, String> statusCategoryByName;

    private final Map<UUID, MemberRow> memberById;
    private final Map<UUID, TaskRow> taskById;
    private final Map<UUID, ProjectRow> projectById;
    private final Map<UUID, TeamRow> teamById;
    private final Map<UUID, UserRef> userById;
    private final Map<UUID, List<TransitionRow>> transitionsByTask;
    private final Map<UUID, List<TimeLogRow>> logsByTask;
    private final Map<UUID, Set<UUID>> projectIdsOfTeamAndParentCache = new ConcurrentHashMap<>();

    public ForecastData(List<MemberRow> members, List<TeamRow> teams, List<ProjectRow> projects, List<TaskRow> tasks,
            List<TransitionRow> transitions, List<TimeLogRow> timeLogs, List<CapacityRow> capacity, List<AbsenceRow> absences,
            List<HolidayRow> holidays, List<UserRef> users, Map<String, String> statusCategoryByName) {
        this.members = sortedBy(members, MemberRow::id);
        this.teams = sortedBy(teams, TeamRow::id);
        this.projects = sortedBy(projects, ProjectRow::id);
        this.tasks = sortedBy(tasks, TaskRow::id);
        this.transitions = List.copyOf(transitions.stream()
                .sorted(Comparator.comparing(TransitionRow::changedAt).thenComparing(TransitionRow::taskId, Ids.UUID_ORDER))
                .toList());
        this.timeLogs = List.copyOf(timeLogs.stream()
                .sorted(Comparator.comparing(TimeLogRow::day).thenComparing(TimeLogRow::taskId, Ids.UUID_ORDER))
                .toList());
        this.capacity = List.copyOf(capacity.stream()
                .sorted(Comparator.comparing(CapacityRow::userId, Ids.UUID_ORDER).thenComparing(CapacityRow::weekStart))
                .toList());
        this.absences = List.copyOf(absences.stream()
                .sorted(Comparator.comparing(AbsenceRow::userId, Ids.UUID_ORDER).thenComparing(AbsenceRow::day))
                .toList());
        this.holidays = List.copyOf(holidays.stream()
                .sorted(Comparator.comparing(HolidayRow::start)
                        .thenComparing(HolidayRow::end)
                        .thenComparing(HolidayRow::title, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
        this.users = sortedBy(users, UserRef::id);
        this.statusCategoryByName = Map.copyOf(statusCategoryByName);

        this.memberById = index(this.members, MemberRow::id);
        this.taskById = index(this.tasks, TaskRow::id);
        this.projectById = index(this.projects, ProjectRow::id);
        this.teamById = index(this.teams, TeamRow::id);
        this.userById = index(this.users, UserRef::id);
        this.transitionsByTask = groupByTask(this.transitions, TransitionRow::taskId);
        this.logsByTask = groupByTask(this.timeLogs, TimeLogRow::taskId);
    }

    private static <T> List<T> sortedBy(List<T> items, Function<T, UUID> id) {
        return List.copyOf(items.stream().sorted(Comparator.comparing(id, Ids.UUID_ORDER)).toList());
    }

    private static <T> Map<UUID, T> index(List<T> items, Function<T, UUID> id) {
        Map<UUID, T> out = new LinkedHashMap<>();
        for (T item : items) {
            out.put(id.apply(item), item);
        }
        return Map.copyOf(out);
    }

    /** A sorted, unmodifiable multimap: {@code TreeMap} for the deterministic iteration order some callers rely on. */
    private static <T> Map<UUID, List<T>> groupByTask(List<T> items, Function<T, UUID> taskId) {
        Map<UUID, List<T>> mutable = new TreeMap<>(Ids.UUID_ORDER);
        for (T item : items) {
            mutable.computeIfAbsent(taskId.apply(item), k -> new ArrayList<>()).add(item);
        }
        Map<UUID, List<T>> frozen = new TreeMap<>(Ids.UUID_ORDER);
        mutable.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(frozen);
    }

    public ForecastData withProjects(List<ProjectRow> projects) {
        return new ForecastData(members, teams, projects, tasks, transitions, timeLogs, capacity, absences, holidays, users,
                statusCategoryByName);
    }

    public List<MemberRow> members() {
        return members;
    }

    public List<TeamRow> teams() {
        return teams;
    }

    public List<ProjectRow> projects() {
        return projects;
    }

    public List<TaskRow> tasks() {
        return tasks;
    }

    public List<TransitionRow> transitions() {
        return transitions;
    }

    public List<TimeLogRow> timeLogs() {
        return timeLogs;
    }

    public List<CapacityRow> capacity() {
        return capacity;
    }

    public List<AbsenceRow> absences() {
        return absences;
    }

    public List<HolidayRow> holidays() {
        return holidays;
    }

    public List<UserRef> users() {
        return users;
    }

    public Map<String, String> statusCategoryByName() {
        return statusCategoryByName;
    }

    public Map<UUID, MemberRow> memberById() {
        return memberById;
    }

    public Map<UUID, TaskRow> taskById() {
        return taskById;
    }

    public Map<UUID, ProjectRow> projectById() {
        return projectById;
    }

    public Map<UUID, TeamRow> teamById() {
        return teamById;
    }

    public Map<UUID, UserRef> userById() {
        return userById;
    }

    public List<MemberRow> membersOfTeam(UUID teamId) {
        return members.stream().filter(m -> m.teamIds().contains(teamId)).toList();
    }

    public Map<UUID, List<TransitionRow>> transitionsByTask() {
        return transitionsByTask;
    }

    public Map<UUID, List<TimeLogRow>> logsByTask() {
        return logsByTask;
    }

    /** Projects owned by the team or by its parent team (a department's projects feed its manager teams). */
    public Set<UUID> projectIdsOfTeamAndParent(UUID teamId) {
        return projectIdsOfTeamAndParentCache.computeIfAbsent(teamId, this::computeProjectIdsOfTeamAndParent);
    }

    private Set<UUID> computeProjectIdsOfTeamAndParent(UUID teamId) {
        TeamRow team = teamById.get(teamId);
        Set<UUID> owners = new TreeSet<>(Ids.UUID_ORDER);
        owners.add(teamId);
        if (team != null && team.parentId() != null) {
            owners.add(team.parentId());
        }
        Set<UUID> out = new TreeSet<>(Ids.UUID_ORDER);
        for (ProjectRow p : projects) {
            if (p.teamId() != null && owners.contains(p.teamId())) {
                out.add(p.id());
            }
        }
        return Set.copyOf(out);
    }
}
