package com.workloadhub.forecast.data;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The role the forecast acts on, derived from the user's job title rather than trusted from `users.role`.
 *
 * <p>WorkloadHub's own role column is not evidence of leadership: in the owner's directory it reads MEMBER
 * for 260 of 264 people, and the only two accounts marked TEAM_LEADER have no job title, no department and
 * nobody reporting to them. The job title does carry the structure, because it is synchronised from the
 * organisation system. So the title decides the two leader tiers, and the column is trusted only for the
 * three roles no title implies — ADMIN, CENTER_MANAGER and VIEWER — which the application assigns
 * deliberately (design 2026-09-21, second revision).
 *
 * <p>The rule has two stages. The title classifies, then a TEAM_LEADER nobody counted reports to is demoted
 * to MEMBER: a leader who leads nobody is an ordinary member of their own manager's team and runs nothing.
 * An actor is never demoted — a SKILL_TEAM_LEADER or CENTER_MANAGER with no reports has nothing to act on,
 * but making them a member would make them a forecast subject, and neither does technical work.
 *
 * <p>The two stages cannot contradict each other, because demotion only ever turns TEAM_LEADER into MEMBER
 * and both are counted: who is counted is fixed by the title alone, so no user's role depends on another's
 * demotion and the result does not depend on the order of the input.
 */
public final class EffectiveRole {

    /** The roles a run forecasts. A leader is counted like any member, because team leaders do technical work. */
    public static final Set<String> COUNTED = Set.of("MEMBER", "TEAM_LEADER");

    /** The roles `users.role` decides: no job title implies them, and the application assigns them by hand. */
    private static final Set<String> DECLARED_WINS = Set.of("ADMIN", "CENTER_MANAGER", "VIEWER");

    /** A user as the rule needs to see them. */
    public record Candidate(UUID id, UUID managerId, String declaredRole, String jobTitle, boolean active) {}

    private EffectiveRole() {
    }

    /**
     * The first stage: what the title says, before the demotion {@link #resolve} applies. Callers that have a
     * whole directory want {@code resolve}; this is for the rare one that classifies a user alone.
     */
    public static String fromTitle(String declaredRole, String jobTitle) {
        if (declaredRole != null && DECLARED_WINS.contains(declaredRole)) {
            return declaredRole;
        }
        String title = normalise(jobTitle);
        // "Skill Team Leader" contains "team lead", so it must be tested first.
        if (title.contains("skill team leader")) {
            return "SKILL_TEAM_LEADER";
        }
        if (title.contains("center manager")) {
            return "CENTER_MANAGER";
        }
        if (title.contains("team lead") || title.contains("lead engineer")) {
            return "TEAM_LEADER";
        }
        return "MEMBER";
    }

    /** The effective role of every user, by id: the title, then the demotion of leaders nobody reports to. */
    public static Map<UUID, String> resolve(Collection<Candidate> users) {
        Map<UUID, String> byTitle = new HashMap<>();
        for (Candidate c : users) {
            byTitle.put(c.id(), fromTitle(c.declaredRole(), c.jobTitle()));
        }
        Set<UUID> leads = new HashSet<>();
        for (Candidate c : users) {
            if (c.active() && c.managerId() != null && !c.managerId().equals(c.id()) && COUNTED.contains(byTitle.get(c.id()))) {
                leads.add(c.managerId());
            }
        }
        Map<UUID, String> out = new HashMap<>();
        for (Candidate c : users) {
            String role = byTitle.get(c.id());
            out.put(c.id(), "TEAM_LEADER".equals(role) && !leads.contains(c.id()) ? "MEMBER" : role);
        }
        return out;
    }

    /** Whether a run forecasts this user. */
    public static boolean counted(String effectiveRole, boolean active) {
        return active && COUNTED.contains(effectiveRole);
    }

    private static String normalise(String jobTitle) {
        return jobTitle == null ? "" : jobTitle.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
