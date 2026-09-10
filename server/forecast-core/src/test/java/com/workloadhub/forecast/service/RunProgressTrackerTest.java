package com.workloadhub.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.api.RunProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RunProgressTrackerTest {

    @Test
    void aTwoHundredAndFiftySeventhRunEvictsTheOldest() {
        RunProgressTracker tracker = new RunProgressTracker();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 257; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            tracker.start(id);
        }
        assertTrue(tracker.get(ids.get(0)).isEmpty(), "the 1st (oldest) run was evicted by the 257th");
        assertTrue(tracker.get(ids.get(1)).isPresent(), "the 2nd run survives: only one eviction for one overflow");
        RunProgress last = tracker.get(ids.get(256)).orElseThrow();
        assertEquals("QUEUED", last.phase());
        assertEquals(0, last.percent());
    }

    @Test
    void updatingAnExistingRunDoesNotCountAsANewInsertOrEvictAnother() {
        RunProgressTracker tracker = new RunProgressTracker();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            tracker.start(id);
        }
        tracker.update(ids.get(0), "LOADING", 2, "reading");
        assertFalse(tracker.get(ids.get(0)).isEmpty(), "re-touching the oldest run keeps it present, unchanged in count");
        assertEquals("LOADING", tracker.get(ids.get(0)).orElseThrow().phase());
    }
}
