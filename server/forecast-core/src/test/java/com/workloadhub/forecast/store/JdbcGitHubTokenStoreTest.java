package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcGitHubTokenStoreTest {

    static final UUID ENG = UUID.fromString("30000000-0000-0000-0000-000000000002");

    static DataSource postgresWithOneUser() {
        DataSource ds = DatabaseTestSupport.postgresWithSchema();
        JdbcClient.create(ds).sql("INSERT INTO users (id, username, email, full_name, role, active, created_at, updated_at)"
                + " VALUES (?, 'eng', 'eng@example.test', 'Eng Two', 'MEMBER', TRUE, ?, ?)")
                .param(ENG).param(LocalDateTime.of(2026, 9, 1, 8, 0)).param(LocalDateTime.of(2026, 9, 1, 8, 0)).update();
        ForecastMigrations.run(ds);
        return ds;
    }

    static JdbcGitHubTokenStore store(DataSource ds) {
        return new JdbcGitHubTokenStore(JdbcClient.create(ds),
                AesGcmCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
    }

    @Test
    void savesEncryptedAndLoadsForTheUser() {
        DataSource ds = postgresWithOneUser();
        JdbcGitHubTokenStore s = store(ds);
        assertFalse(s.has(ENG));
        s.save(ENG, "gho_secret123");
        assertTrue(s.has(ENG));
        assertEquals("gho_secret123", s.load(ENG).orElseThrow());
        String stored = JdbcClient.create(ds).sql("SELECT github_token FROM users WHERE id = ?")
                .param(ENG).query(String.class).single();
        assertTrue(stored.startsWith("v1:"));
        assertNotEquals("gho_secret123", stored);
        s.clear(ENG);
        assertFalse(s.has(ENG));
    }

    @Test
    void refusesClassicTokensAndUnknownUsers() {
        JdbcGitHubTokenStore s = store(postgresWithOneUser());
        ForecastException classic = assertThrows(ForecastException.class, () -> s.save(ENG, "ghp_old"));
        assertEquals("INVALID_REQUEST", classic.code());
        ForecastException unknown = assertThrows(ForecastException.class, () -> s.save(UUID.randomUUID(), "gho_x"));
        assertEquals("USER_NOT_FOUND", unknown.code());
    }

    @Test
    void refusesEmptyTokens() {
        DataSource ds = postgresWithOneUser();
        JdbcGitHubTokenStore s = store(ds);
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> s.save(ENG, "")).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> s.save(ENG, "   ")).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> s.save(ENG, null)).code());
        assertFalse(s.has(ENG));
    }

    @Test
    void withoutKeyEveryCallFails() {
        DataSource ds = postgresWithOneUser();
        JdbcGitHubTokenStore s = new JdbcGitHubTokenStore(JdbcClient.create(ds), null);
        assertEquals("TOKEN_KEY_MISSING", assertThrows(ForecastException.class, () -> s.save(ENG, "gho_x")).code());
        assertEquals("TOKEN_KEY_MISSING", assertThrows(ForecastException.class, () -> s.load(ENG)).code());
    }
}
