package com.workloadhub.forecast.data;

import com.workloadhub.forecast.data.rows.HolidayRow;
import com.workloadhub.forecast.data.rows.LeaveRow;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.ProjectRow;
import com.workloadhub.forecast.data.rows.TaskRow;
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
    private final List<ProjectRow> projects;
    private final List<TaskRow> tasks;
    private final List<TransitionRow> transitions;
    private final List<TimeLogRow> timeLogs;
    private final List<LeaveRow> leaves;
    private final List<LeaveRow> pendingLeaves;
    private final List<HolidayRow> holidays;
    private final List<UserRef> users;
    private final Map<String, String> statusCategoryByName;

    private final Map<UUID, TaskRow> taskById;
    private final Map<UUID, ProjectRow> projectById;
    private final Map<UUID, UserRef> userById;
    private final Map<UUID, List<TransitionRow>> transitionsByTask;
    private final Map<UUID, List<TimeLogRow>> logsByTask;
    private final Map<UUID, Set<UUID>> projectIdsOfTeamCache = new ConcurrentHashMap<>();

    public ForecastData(List<MemberRow> members, List<ProjectRow> projects, List<TaskRow> tasks,
            List<TransitionRow> transitions, List<TimeLogRow> timeLogs, List<LeaveRow> leaves, List<LeaveRow> pendingLeaves,
            List<HolidayRow> holidays, List<UserRef> users, Map<String, String> statusCategoryByName) {
        this.members = sortedBy(members, MemberRow::id);
        this.projects = sortedBy(projects, ProjectRow::id);
        this.tasks = sortedBy(tasks, TaskRow::id);
        this.transitions = List.copyOf(transitions.stream()
                .sorted(Comparator.comparing(TransitionRow::changedAt).thenComparing(TransitionRow::taskId, Ids.UUID_ORDER))
                .toList());
        this.timeLogs = List.copyOf(timeLogs.stream()
                .sorted(Comparator.comparing(TimeLogRow::day).thenComparing(TimeLogRow::taskId, Ids.UUID_ORDER))
                .toList());
        this.leaves = sortedLeaves(leaves);
        this.pendingLeaves = sortedLeaves(pendingLeaves);
        this.holidays = List.copyOf(holidays.stream()
                .sorted(Comparator.comparing(HolidayRow::start)
                        .thenComparing(HolidayRow::end)
                        .thenComparing(HolidayRow::title, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
        this.users = sortedBy(users, UserRef::id);
        this.statusCategoryByName = Map.copyOf(statusCategoryByName);

        this.taskById = index(this.tasks, TaskRow::id);
        this.projectById = index(this.projects, ProjectRow::id);
        this.userById = index(this.users, UserRef::id);
        this.transitionsByTask = groupByTask(this.transitions, TransitionRow::taskId);
        this.logsByTask = groupByTask(this.timeLogs, TimeLogRow::taskId);
    }

    private static <T> List<T> sortedBy(List<T> items, Function<T, UUID> id) {
        return List.copyOf(items.stream().sorted(Comparator.comparing(id, Ids.UUID_ORDER)).toList());
    }

    private static List<LeaveRow> sortedLeaves(List<LeaveRow> rows) {
        return List.copyOf(rows.stream()
                .sorted(Comparator.comparing(LeaveRow::employeeId, Ids.UUID_ORDER).thenComparing(LeaveRow::start).thenComparing(LeaveRow::end))
                .toList());
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
        return new ForecastData(members, projects, tasks, transitions, timeLogs, leaves, pendingLeaves, holidays, users,
                statusCategoryByName);
    }

    public List<MemberRow> members() {
        return members;
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

    /** The APPROVED personal leaves, by member then start date. Only these reduce capacity. */
    public List<LeaveRow> leaves() {
        return leaves;
    }

    /** The PENDING personal leaves, for the facts only; they never reduce capacity. */
    public List<LeaveRow> pendingLeaves() {
        return pendingLeaves;
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

    public Map<UUID, TaskRow> taskById() {
        return taskById;
    }

    public Map<UUID, ProjectRow> projectById() {
        return projectById;
    }

    /** Every user by id, counted or not: a team's leader is looked up here, since they may not be counted. */
    public Map<UUID, UserRef> userById() {
        return userById;
    }

    /**
     * The team keyed by {@code teamId}: the leader of that id and every counted member who reports to them
     * directly. {@code members} is already filtered to counted, active users, so an uncounted report is absent
     * by construction and a leader who is not counted themselves keys a team they are not in.
     */
    public List<MemberRow> membersOfTeam(UUID teamId) {
        return members.stream().filter(m -> m.id().equals(teamId) || teamId.equals(m.managerId())).toList();
    }

    public Map<UUID, List<TransitionRow>> transitionsByTask() {
        return transitionsByTask;
    }

    public Map<UUID, List<TimeLogRow>> logsByTask() {
        return logsByTask;
    }

    /**
     * The projects this team actually works on: the distinct projects of the tasks assigned to its members,
     * over the whole loaded history. `projects.team_id` names a project team and is not consulted.
     */
    public Set<UUID> projectIdsOfTeam(UUID teamId) {
        return projectIdsOfTeamCache.computeIfAbsent(teamId, this::computeProjectIdsOfTeam);
    }

    private Set<UUID> computeProjectIdsOfTeam(UUID teamId) {
        Set<UUID> memberIds = new TreeSet<>(Ids.UUID_ORDER);
        membersOfTeam(teamId).forEach(m -> memberIds.add(m.id()));
        Set<UUID> out = new TreeSet<>(Ids.UUID_ORDER);
        for (TaskRow t : tasks) {
            if (t.projectId() != null && t.assigneeId() != null && memberIds.contains(t.assigneeId())) {
                out.add(t.projectId());
            }
        }
        return Set.copyOf(out);
    }
}
