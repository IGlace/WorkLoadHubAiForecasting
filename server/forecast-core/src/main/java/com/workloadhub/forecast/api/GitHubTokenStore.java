package com.workloadhub.forecast.api;

import java.util.Optional;
import java.util.UUID;

/** Where each user's GitHub token lives: encrypted on the users table, read only for that user. */
public interface GitHubTokenStore {

    /** Stores a gho_, ghu_ or github_pat_ token; refuses classic ghp_ tokens and unknown users. */
    void save(UUID userId, String token);

    Optional<String> load(UUID userId);

    boolean has(UUID userId);

    void clear(UUID userId);
}
