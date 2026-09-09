package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A counted user (role MEMBER or TEAM_LEADER, active, in at least one team). */
public record MemberRow(UUID id, String fullName, String email, String role, String jobTitle, List<UUID> teamIds,
        UUID primaryTeamId, LocalDate joined, LocalDate left) {

    public boolean employedOn(LocalDate d) {
        return !d.isBefore(joined) && (left == null || d.isBefore(left));
    }

    public MemberRow withFullName(String fullName) {
        return new MemberRow(id, fullName, email, role, jobTitle, teamIds, primaryTeamId, joined, left);
    }
}
