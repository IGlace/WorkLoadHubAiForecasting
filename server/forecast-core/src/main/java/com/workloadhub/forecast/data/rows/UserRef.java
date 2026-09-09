package com.workloadhub.forecast.data.rows;

import java.util.UUID;

/** Every user, counted or not, for lookups outside the counted membership (e.g. reporters, managers). */
public record UserRef(UUID id, String fullName, String email, String username) {
}
