package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A counted user: role MEMBER or TEAM_LEADER, active. `managerId` is the company structure
 * (`users.manager_id`) and so names the team this member is forecast in; a member who leads a team is keyed by
 * their own id as well (design 2026-09-21, section 4).
 *
 * <p>`joined` is the Monday of the member's first activity — their earliest logged hour or earliest assignment
 * — and is null when they have none at all, which leaves their tenure blank rather than inventing one.
 */
public record MemberRow(UUID id, String fullName, String email, String role, String jobTitle, String department,
        UUID managerId, LocalDate joined, LocalDate left) {

    public MemberRow withFullName(String fullName) {
        return new MemberRow(id, fullName, email, role, jobTitle, department, managerId, joined, left);
    }

    public MemberRow withJoined(LocalDate joined) {
        return new MemberRow(id, fullName, email, role, jobTitle, department, managerId, joined, left);
    }
}
