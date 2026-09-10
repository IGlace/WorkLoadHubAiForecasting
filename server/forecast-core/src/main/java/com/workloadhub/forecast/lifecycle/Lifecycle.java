package com.workloadhub.forecast.lifecycle;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.Ids;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.data.rows.TimeLogRow;
import com.workloadhub.forecast.data.rows.TransitionRow;
import com.workloadhub.forecast.data.rows.UserRef;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The lifecycle rules of the spec, section 5, applied to every task once.
 *
 * <p>Not a record: {@link #all()} is read once per candidate/history scan in the planned-work allocation, so its
 * sorted view is computed once here (the map is already ordered by {@link Ids#UUID_ORDER}, so this is a plain
 * copy, not a re-sort) and cached in a real field, which a record's fixed component list cannot hold.
 */
public final class Lifecycle {

    public static final int BACKLOG_LAG_DAYS = 2;
    private static final Set<String> UNASSIGNED_VALUES = Set.of("", "none", "null");
    private static final Comparator<UUID> BY_ID = Ids.UUID_ORDER;

    private final SortedMap<UUID, TaskFacts> facts;
    private final List<String> unresolvedAssignments;
    private final List<String> unloggedTasks;
    private final List<TaskFacts> all;

    private Lifecycle(Map<UUID, TaskFacts> facts, List<String> unresolvedAssignments, List<String> unloggedTasks) {
        SortedMap<UUID, TaskFacts> sorted = new TreeMap<>(BY_ID);
        sorted.putAll(facts);
        this.facts = Collections.unmodifiableSortedMap(sorted);
        this.unresolvedAssignments = List.copyOf(unresolvedAssignments);
        this.unloggedTasks = List.copyOf(unloggedTasks);
        this.all = List.copyOf(this.facts.values());
    }

    public Map<UUID, TaskFacts> facts() {
        return facts;
    }

    public List<String> unresolvedAssignments() {
        return unresolvedAssignments;
    }

    public List<String> unloggedTasks() {
        return unloggedTasks;
    }

    public static Lifecycle derive(ForecastData data) {
        Resolver resolver = new Resolver(data);
        Map<UUID, List<TransitionRow>> transitions = data.transitionsByTask();
        Map<UUID, List<TimeLogRow>> logs = data.logsByTask();
        Map<UUID, TaskRow> byId = data.taskById();
        Map<UUID, TaskFacts> out = new TreeMap<>(BY_ID);
        List<String> unresolved = new ArrayList<>();
        List<String> unlogged = new ArrayList<>();
        for (TaskRow t : data.tasks()) {
            List<TransitionRow> history = transitions.getOrDefault(t.id(), List.of());
            Assignment a = assignment(t, history, resolver);
            LocalDateTime started = t.startedDate() != null ? t.startedDate() : firstEntry(history, data, "IN_PROGRESS");
            LocalDateTime finished = null;
            if ("DONE".equals(t.statusCategory())) {
                finished = t.finishedDate() != null ? t.finishedDate() : firstEntry(history, data, "DONE");
            }
            double actual = 0.0;
            boolean hasAssigneeLog = false;
            for (TimeLogRow l : logs.getOrDefault(t.id(), List.of())) {
                if (t.assigneeId() != null && t.assigneeId().equals(l.userId())) {
                    actual += l.hours();
                    hasAssigneeLog = true;
                }
            }
            boolean isUnlogged = false;
            if (!hasAssigneeLog && finished != null && t.assigneeId() != null && t.estimate() != null) {
                actual = Math.max(0.0, t.estimate() - (t.remaining() == null ? 0.0 : t.remaining()));
                isUnlogged = true;
                unlogged.add(t.key());
            }
            if (a.fallback()) {
                unresolved.add(t.key());
            }
            out.put(t.id(), new TaskFacts(t, a.at(), started, finished, family(t, byId), mode(t), actual, isUnlogged, a.fallback()));
        }
        return new Lifecycle(out, unresolved, unlogged);
    }

    private record Assignment(LocalDateTime at, boolean fallback) {
    }

    private static Assignment assignment(TaskRow t, List<TransitionRow> history, Resolver resolver) {
        if (t.assigneeId() == null) {
            return new Assignment(null, false);
        }
        boolean sawAssigneeRow = false;
        for (int i = history.size() - 1; i >= 0; i--) {
            TransitionRow row = history.get(i);
            if (!"assignee".equals(row.field())) {
                continue;
            }
            sawAssigneeRow = true;
            UUID resolved = resolver.resolve(row.newValue(), t.assigneeId());
            if (t.assigneeId().equals(resolved)) {
                return new Assignment(row.changedAt(), false);
            }
        }
        return new Assignment(t.createdDate(), sawAssigneeRow);
    }

    private static LocalDateTime firstEntry(List<TransitionRow> history, ForecastData data, String category) {
        for (TransitionRow row : history) {
            if ("status".equals(row.field()) && category.equals(data.statusCategoryByName().get(row.newValue()))) {
                return row.changedAt();
            }
        }
        return null;
    }

    private static Family family(TaskRow t, Map<UUID, TaskRow> byId) {
        Family own = Family.ofType(t.typeName());
        if (own != null) {
            return own;
        }
        TaskRow parent = t.parentId() == null ? null : byId.get(t.parentId());
        Family parentFamily = parent == null ? null : Family.ofType(parent.typeName());
        return parentFamily == null ? Family.DELIVERY : parentFamily;
    }

    private static Mode mode(TaskRow t) {
        if (t.reporterId() != null && t.reporterId().equals(t.assigneeId())) {
            return Mode.SELF_PICKED;
        }
        return t.parentId() != null ? Mode.PROJECT : Mode.MANUAL;
    }

    public TaskFacts of(UUID taskId) {
        return facts.get(taskId);
    }

    public List<TaskFacts> all() {
        return all;
    }

    public List<TaskFacts> assignedTo(UUID member) {
        return facts.values().stream()
                .filter(f -> member.equals(f.assignee()) && f.isAssigned())
                .sorted(Comparator.comparing(TaskFacts::assigned).thenComparing(TaskFacts::id, Ids.UUID_ORDER))
                .toList();
    }

    /** UUID, else email, else full name; counted members first, then every user; ambiguity resolves to null. */
    static final class Resolver {
        private final Set<UUID> userIds = new HashSet<>();
        private final Map<String, List<UUID>> membersByEmail = new HashMap<>();
        private final Map<String, List<UUID>> membersByName = new HashMap<>();
        private final Map<String, List<UUID>> usersByEmail = new HashMap<>();
        private final Map<String, List<UUID>> usersByName = new HashMap<>();

        Resolver(ForecastData data) {
            for (MemberRow m : data.members()) {
                add(membersByEmail, m.email(), m.id());
                add(membersByName, m.fullName(), m.id());
            }
            for (UserRef u : data.users()) {
                userIds.add(u.id());
                add(usersByEmail, u.email(), u.id());
                add(usersByName, u.fullName(), u.id());
            }
        }

        private static void add(Map<String, List<UUID>> index, String key, UUID id) {
            if (key != null && !key.isBlank()) {
                index.computeIfAbsent(key.trim().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(id);
            }
        }

        UUID resolve(String value, UUID expected) {
            if (value == null || UNASSIGNED_VALUES.contains(value.trim().toLowerCase(Locale.ROOT))) {
                return null;
            }
            String key = value.trim().toLowerCase(Locale.ROOT);
            try {
                UUID id = UUID.fromString(key);
                return userIds.contains(id) ? id : null;
            } catch (IllegalArgumentException notAUuid) {
                // fall through to names
            }
            for (Map<String, List<UUID>> index : List.of(membersByEmail, membersByName, usersByEmail, usersByName)) {
                List<UUID> hits = index.get(key);
                if (hits != null && !hits.isEmpty()) {
                    return hits.size() == 1 ? hits.get(0) : null;
                }
            }
            return null;
        }
    }
}
