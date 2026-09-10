package com.workloadhub.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void narrationStepsPercentAndTailsAreTracked() {
        RunProgressTracker t = new RunProgressTracker();
        UUID id = UUID.randomUUID();
        t.done(id);
        com.workloadhub.forecast.ai.NarrationProgress p = t.narrationProgress(id);
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.STARTING, null);
        assertEquals("NARRATING", t.get(id).orElseThrow().phase());
        assertEquals(5, t.get(id).orElseThrow().percent());
        assertEquals("starting Copilot", t.get(id).orElseThrow().message());
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.SESSION, null);
        assertEquals(10, t.get(id).orElseThrow().percent());
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.ASKING, "2");
        assertEquals(20, t.get(id).orElseThrow().percent());
        assertEquals("asking Copilot (attempt 2)", t.get(id).orElseThrow().message());
        for (int i = 0; i < 20; i++) {
            p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.TOOL, "get_member_forecast");
        }
        assertEquals(80, t.get(id).orElseThrow().percent(), "tool calls add five points up to eighty");
        assertEquals("tool get_member_forecast", t.get(id).orElseThrow().message());
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.TOOL_DONE, null);
        assertEquals("tool done", t.get(id).orElseThrow().message());
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.TOOL_DONE, "get_member_forecast");
        assertEquals("tool get_member_forecast done", t.get(id).orElseThrow().message());
        p.thinking("Reading ");
        p.thinking(null);
        p.thinking("");
        p.thinking("the facts");
        p.answer("{\"run_summary\"");
        p.answer(null);
        assertEquals("Reading the facts", t.get(id).orElseThrow().thinking());
        assertEquals("{\"run_summary\"", t.get(id).orElseThrow().answer());
        p.resetAnswer();
        assertEquals("", t.get(id).orElseThrow().answer());
        assertEquals("Reading the facts", t.get(id).orElseThrow().thinking(), "a reset keeps the thinking");
        p.answer("x".repeat(RunProgressTracker.TAIL_CHARS + 10));
        assertEquals(RunProgressTracker.TAIL_CHARS, t.get(id).orElseThrow().answer().length());
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.CHECKING, null);
        assertEquals(90, t.get(id).orElseThrow().percent());
        t.narrated(id);
        assertEquals("NARRATED", t.get(id).orElseThrow().phase());
        assertEquals(100, t.get(id).orElseThrow().percent());
        assertEquals("Reading the facts", t.get(id).orElseThrow().thinking(), "the tails stay readable after the end");
        t.narrationFailed(id, "timeout: no answer within 300 s");
        assertEquals("NARRATION_FAILED", t.get(id).orElseThrow().phase());
        assertEquals("timeout: no answer within 300 s", t.get(id).orElseThrow().message());
    }

    @Test
    void runProgressHasNoTailsOutsideNarration() {
        RunProgressTracker t = new RunProgressTracker();
        UUID id = UUID.randomUUID();
        t.start(id);
        t.update(id, "LOADING", 2, "reading");
        assertNull(t.get(id).orElseThrow().thinking());
        assertNull(t.get(id).orElseThrow().answer());
    }
}
