package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeaturesTest {

    static final int WINDOWS = 2;

    @Test
    void horizonsRunFromOneToWindowsPlusOne() {
        assertArrayEquals(new int[] {1, 2, 3}, Features.horizons(2));
        assertArrayEquals(new int[] {1, 2, 3, 4, 5, 6, 7}, Features.horizons(6));
        assertArrayEquals(new int[] {1, 2}, Features.horizons(1));
    }

    @Test
    void fortySixColumnsPerHorizonInTheSpecOrderWithoutDuplicates() {
        for (int h : Features.horizons(WINDOWS)) {
            List<String> cols = Features.featureColumns(h);
            assertEquals(46, cols.size(), cols.toString());
            assertEquals(cols.size(), new HashSet<>(cols).size());
            assertEquals("lag1", cols.get(0));
            assertTrue(cols.contains("due_hrs_h" + h));
            assertTrue(cols.contains("available_hrs_h" + h));
            assertFalse(cols.contains(Features.target(h)));
            for (int k = 1; k <= 4; k++) {
                assertTrue(cols.contains("arrival_hrs_lag" + k));
            }
            assertFalse(cols.contains("logged_hours_lag1"));
            for (int other : Features.horizons(WINDOWS)) {
                if (other != h) {
                    assertFalse(cols.contains("due_hrs_h" + other));
                }
            }
        }
    }

    @Test
    void sharedColumnsStayFortyTwoWithArrivalsInPlaceOfLoggedHours() {
        int h0 = Features.horizons(WINDOWS)[0];
        List<String> shared = Features.featureColumns(h0).stream()
                .filter(c -> !c.endsWith("_h" + h0)).toList();
        assertEquals(42, shared.size(), shared.toString());
        for (int k = 1; k <= 4; k++) {
            assertTrue(shared.contains("arrival_hrs_lag" + k));
        }
        assertFalse(shared.contains("logged_hours_lag1"));
        assertFalse(shared.contains("logged_hours_lag2"));
        assertFalse(shared.contains("logged_hours_lag3"));
        assertFalse(shared.contains("logged_hours_lag4"));
        assertFalse(shared.contains("fresh_hours"));
        assertFalse(shared.contains("est_hours"));
    }

    @Test
    void allColumnsHoldEveryHorizonOnce() {
        List<String> all = Features.allColumns(WINDOWS);
        assertEquals(all.size(), new HashSet<>(all).size());
        for (int h : Features.horizons(WINDOWS)) {
            assertTrue(all.containsAll(Features.featureColumns(h)));
            assertTrue(all.contains(Features.target(h)));
        }
        assertFalse(all.contains("fresh_hours"));
        assertFalse(all.contains("est_hours"));
        assertEquals(List.of("member_id", "team_id", "role", "job_title"), Features.CATEGORICAL);
        assertTrue(Features.isCategorical("role"));
        assertFalse(Features.isCategorical("lag1"));
    }

    @Test
    void allColumnsScalesWithTheWindowCount() {
        int perHorizon = Features.horizonColumns(1).size() + 1; // the per-horizon columns plus one target column
        assertEquals(Features.allColumns(2).size() + 4 * perHorizon, Features.allColumns(6).size(),
                "six windows reaches horizon 7, three more than two windows: four extra horizons of columns");
    }
}
