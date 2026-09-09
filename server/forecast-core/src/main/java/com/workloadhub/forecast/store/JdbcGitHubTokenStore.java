package com.workloadhub.forecast.store;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.GitHubTokenStore;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** users.github_token, encrypted; the module reads it in exactly one place, for the requesting user. */
public final class JdbcGitHubTokenStore implements GitHubTokenStore {

    private final JdbcClient jdbc;
    private final Dialect dialect;
    private final AesGcmCipher cipher; // null when whf.token-key is not configured

    public JdbcGitHubTokenStore(JdbcClient jdbc, Dialect dialect, AesGcmCipher cipher) {
        this.jdbc = jdbc;
        this.dialect = dialect;
        this.cipher = cipher;
    }

    private AesGcmCipher cipherOrFail() {
        if (cipher == null) {
            throw ForecastException.of("TOKEN_KEY_MISSING", "whf.token-key is not configured; tokens cannot be stored or read");
        }
        return cipher;
    }

    private String idPlaceholder() {
        return dialect.placeholder("uuid");
    }

    @Override
    public void save(UUID userId, String token) {
        AesGcmCipher c = cipherOrFail();
        if (token == null || token.isBlank()) {
            throw ForecastException.invalidRequest("token is empty");
        }
        String t = token.trim();
        if (!(t.startsWith("gho_") || t.startsWith("ghu_") || t.startsWith("github_pat_"))) {
            throw ForecastException.invalidRequest("token must be a gho_, ghu_ or github_pat_ token; classic ghp_ tokens are not accepted");
        }
        int updated = jdbc.sql("UPDATE users SET github_token = ?, github_token_updated_at = " + dialect.placeholder("timestamp")
                        + " WHERE id = " + idPlaceholder())
                .param(c.encrypt(t))
                .param(LocalDateTime.now().withNano(0).toString())
                .param(userId.toString())
                .update();
        if (updated == 0) {
            throw ForecastException.of("USER_NOT_FOUND", "No user " + userId);
        }
    }

    @Override
    public Optional<String> load(UUID userId) {
        AesGcmCipher c = cipherOrFail();
        return jdbc.sql("SELECT github_token FROM users WHERE id = " + idPlaceholder())
                .param(userId.toString())
                .query(String.class)
                .optional()
                .filter(v -> v != null && !v.isBlank())
                .map(c::decrypt);
    }

    @Override
    public boolean has(UUID userId) {
        return jdbc.sql("SELECT github_token FROM users WHERE id = " + idPlaceholder())
                .param(userId.toString())
                .query(String.class)
                .optional()
                .filter(v -> v != null && !v.isBlank())
                .isPresent();
    }

    @Override
    public void clear(UUID userId) {
        jdbc.sql("UPDATE users SET github_token = NULL, github_token_updated_at = NULL WHERE id = " + idPlaceholder())
                .param(userId.toString())
                .update();
    }
}
