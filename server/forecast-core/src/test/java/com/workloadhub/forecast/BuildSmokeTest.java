package com.workloadhub.forecast;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class BuildSmokeTest {

    @Test
    void junitRuns() {
        assertTrue(Runtime.version().feature() >= 21, "Java 21 or newer");
    }

    @Property
    boolean jqwikRunsOnThisPlatform(@ForAll int x) {
        return x == Integer.MIN_VALUE || Math.abs(x) >= 0;
    }
}
