package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A counted user: role MEMBER or TEAM_LEADER, active. {@code managerId} is the company structure
 * (`users.manager_id`) and so names the team this member is forecast in; a member who leads a team is keyed by
 * their own id as well (design 2026-09-21, section 4).
 *
 * <p>{@code joined} is the member's first activity — the earlier of their earliest logged day and the earliest
 * `task_history` row they are the actor of — as a date, not a week: the callers that need a Monday take it
 * themselves. It is null when the member has no activity at all, which leaves their tenure blank rather than
 * inventing one.
 */
public record MemberRow(UUID id, String fullName, String email, String role, String jobTitle,
        UUID managerId, LocalDate joined, LocalDate left) {

    public MemberRow withFullName(String fullName) {
        return new MemberRow(id, fullName, email, role, jobTitle, managerId, joined, left);
    }

    public MemberRow withJoined(LocalDate joined) {
        return new MemberRow(id, fullName, email, role, jobTitle, managerId, joined, left);
    }
}
