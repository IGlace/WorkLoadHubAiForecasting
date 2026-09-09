package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.util.UUID;

/** One user as the generator sees them. `left` is null while employed. */
public record Person(UUID id, String fullName, String email, String jobTitle, String department, String deptCode,
        UUID managerId, String role, WorkFamily family, LocalDate joined, LocalDate left) {

    public boolean counted() {
        return role.equals("MEMBER") || role.equals("TEAM_LEADER");
    }

    public boolean employedOn(LocalDate day) {
        return !day.isBefore(joined) && (left == null || day.isBefore(left));
    }

    public Person withRole(String newRole) {
        return new Person(id, fullName, email, jobTitle, department, deptCode, managerId, newRole, family, joined, left);
    }
}
