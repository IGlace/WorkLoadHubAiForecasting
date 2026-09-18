package com.workloadhub.forecastweb.host;

import java.util.UUID;

/**
 * Who is making the request. Production takes this from its session; this host takes it from the
 * {@code X-Acting-User} header, resolved against {@code users} by {@link ActingUserResolver}. Nothing after
 * the resolution differs between the two.
 */
public record ActingUser(UUID id, String fullName, String role, String jobTitle) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
