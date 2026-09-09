package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ExportImporter;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcGitHubTokenStoreTest {

    static final UUID ENG = UUID.fromString("30000000-0000-0000-0000-000000000002");

    static DataSource sqliteWithFixture() throws Exception {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ExportImporter(ds).importAll(ExportFiles.read(Path.of("src/test/resources/fixtures/mini-export.json")), true);
        ForecastMigrations.run(ds);
        return ds;
    }

    static JdbcGitHubTokenStore store(DataSource ds) {
        return new JdbcGitHubTokenStore(JdbcClient.create(ds), Dialect.of(ds),
                AesGcmCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
    }

    @Test
    void savesEncryptedAndLoadsForTheUser() throws Exception {
        DataSource ds = sqliteWithFixture();
        JdbcGitHubTokenStore s = store(ds);
        assertFalse(s.has(ENG));
        s.save(ENG, "gho_secret123");
        assertTrue(s.has(ENG));
        assertEquals("gho_secret123", s.load(ENG).orElseThrow());
        String stored = JdbcClient.create(ds).sql("SELECT github_token FROM users WHERE id = ?").param(ENG.toString()).query(String.class).single();
        assertTrue(stored.startsWith("v1:"));
        assertNotEquals("gho_secret123", stored);
        s.clear(ENG);
        assertFalse(s.has(ENG));
    }

    @Test
    void refusesClassicTokensAndUnknownUsers() throws Exception {
        JdbcGitHubTokenStore s = store(sqliteWithFixture());
        ForecastException classic = assertThrows(ForecastException.class, () -> s.save(ENG, "ghp_old"));
        assertEquals("INVALID_REQUEST", classic.code());
        ForecastException unknown = assertThrows(ForecastException.class, () -> s.save(UUID.randomUUID(), "gho_x"));
        assertEquals("USER_NOT_FOUND", unknown.code());
    }

    @Test
    void refusesEmptyTokens() throws Exception {
        DataSource ds = sqliteWithFixture();
        JdbcGitHubTokenStore s = store(ds);
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> s.save(ENG, "")).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> s.save(ENG, "   ")).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class, () -> s.save(ENG, null)).code());
        assertFalse(s.has(ENG));
    }

    @Test
    void withoutKeyEveryCallFails() throws Exception {
        DataSource ds = sqliteWithFixture();
        JdbcGitHubTokenStore s = new JdbcGitHubTokenStore(JdbcClient.create(ds), Dialect.of(ds), null);
        assertEquals("TOKEN_KEY_MISSING", assertThrows(ForecastException.class, () -> s.save(ENG, "gho_x")).code());
        assertEquals("TOKEN_KEY_MISSING", assertThrows(ForecastException.class, () -> s.load(ENG)).code());
    }
}
