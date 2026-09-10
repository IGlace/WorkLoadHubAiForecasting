package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeaturesTest {

    @Test
    void fortySixColumnsPerHorizonInTheSpecOrderWithoutDuplicates() {
        for (int h : Features.HORIZONS) {
            List<String> cols = Features.featureColumns(h);
            assertEquals(46, cols.size(), cols.toString());
            assertEquals(cols.size(), new HashSet<>(cols).size());
            assertEquals("lag1", cols.get(0));
            assertTrue(cols.contains("due_hrs_h" + h));
            assertTrue(cols.contains("available_hrs_h" + h));
            assertFalse(cols.contains(Features.target(h)));
            assertFalse(cols.contains(Features.FRESH));
            for (int other : Features.HORIZONS) {
                if (other != h) {
                    assertFalse(cols.contains("due_hrs_h" + other));
                }
            }
        }
    }

    @Test
    void allColumnsHoldEveryHorizonOnceAndTheSeriesValues() {
        List<String> all = Features.allColumns();
        assertEquals(all.size(), new HashSet<>(all).size());
        for (int h : Features.HORIZONS) {
            assertTrue(all.containsAll(Features.featureColumns(h)));
            assertTrue(all.contains(Features.target(h)));
        }
        assertTrue(all.contains(Features.FRESH));
        assertTrue(all.contains(Features.EST));
        assertEquals(List.of("member_id", "team_id", "role", "job_title"), Features.CATEGORICAL);
        assertTrue(Features.isCategorical("role"));
        assertFalse(Features.isCategorical("lag1"));
    }
}
