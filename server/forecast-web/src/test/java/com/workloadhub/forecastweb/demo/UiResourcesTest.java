package com.workloadhub.forecastweb.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiResourcesTest {

    @TempDir
    Path dir;

    @Test
    void aFileIsServedAPageRouteFallsBackAndTheApiNeverDoes() {
        Set<String> files = Set.of("index.html", "assets/app.js");
        assertEquals("assets/app.js", UiResources.resolve("assets/app.js", files::contains));
        assertEquals("index.html", UiResources.resolve("", files::contains));
        assertEquals("index.html", UiResources.resolve("teams/123", files::contains));
        assertEquals("index.html", UiResources.resolve("/runs/abc", files::contains));
        assertNull(UiResources.resolve("api/system", files::contains));
        assertNull(UiResources.resolve("api", files::contains));
    }

    @Test
    void presentOnlyWithAnIndex() throws Exception {
        assertFalse(new UiResources(dir.resolve("missing")).present());
        assertFalse(new UiResources(dir).present());
        Files.writeString(dir.resolve("index.html"), "<html></html>");
        assertTrue(new UiResources(dir).present());
    }
}
