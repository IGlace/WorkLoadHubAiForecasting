package com.workloadhub.forecastweb.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class TokenKeyFileTest {

    @TempDir
    Path dir;

    @Test
    void createsAThirtyTwoByteKeyOnceAndReusesIt() throws Exception {
        Path file = dir.resolve("sub/token.key");
        String key = TokenKeyFile.readOrCreate(file);
        assertEquals(32, Base64.getDecoder().decode(key).length);
        assertTrue(Files.exists(file));
        assertEquals(key, TokenKeyFile.readOrCreate(file), "the same key on the next start");
    }

    @Test
    void fillsThePropertyOnlyWhenBlankAndAFileIsNamed() {
        Path file = dir.resolve("token.key");
        MockEnvironment blank = new MockEnvironment().withProperty("whf.token-key", "").withProperty("forecast-web.token-key-file", file.toString());
        new TokenKeyFile().postProcessEnvironment(blank, null);
        assertFalse(blank.getProperty("whf.token-key").isBlank());

        MockEnvironment given = new MockEnvironment().withProperty("whf.token-key", "given").withProperty("forecast-web.token-key-file", dir.resolve("other.key").toString());
        new TokenKeyFile().postProcessEnvironment(given, null);
        assertEquals("given", given.getProperty("whf.token-key"));
        assertFalse(Files.exists(dir.resolve("other.key")), "a given key writes nothing");

        MockEnvironment noFile = new MockEnvironment().withProperty("forecast-web.token-key-file", "");
        new TokenKeyFile().postProcessEnvironment(noFile, null);
        assertNull(noFile.getProperty("whf.token-key"));
    }
}
