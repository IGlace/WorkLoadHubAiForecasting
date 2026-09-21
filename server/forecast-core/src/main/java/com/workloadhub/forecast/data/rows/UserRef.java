package com.workloadhub.forecast.data.rows;

import java.util.UUID;

/**
 * Every user, counted or not, for lookups outside the counted membership (e.g. reporters, managers).
 * `managerId` is `users.manager_id`, the company structure, so this row alone answers who a team's leader
 * reports to even when that leader is not themselves counted.
 */
public record UserRef(UUID id, String fullName, String email, String username, String department, UUID managerId) {
}
