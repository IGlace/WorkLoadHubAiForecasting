package com.workloadhub.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NarrationProgress;
import com.workloadhub.forecast.api.ProgressLabel;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

/** The rotation is arithmetic on the clock: whatever the elapsed time, the label is the phrase of that slot. */
class RunProgressTrackerProperties {

    @Property
    void theAskingLabelIsThePhraseOfTheFourSecondSlotTheClockIsIn(@ForAll @IntRange(min = 0, max = 99_999) int elapsedSeconds) {
        assertEquals(4, RunProgressTracker.ROTATION.getSeconds(), "a phrase is shown for four seconds");
        TickingClock clock = new TickingClock(Instant.parse("2026-09-06T12:00:00Z"));
        RunProgressTracker tracker = new RunProgressTracker(clock);
        UUID runId = UUID.randomUUID();
        tracker.done(runId);
        NarrationProgress progress = tracker.narrationProgress(runId);
        progress.step(NarrationProgress.Step.ASKING, null);

        clock.advance(Duration.ofSeconds(elapsedSeconds));

        List<ProgressLabel> phrases = RunProgressTracker.ASKING_LABELS;
        ProgressLabel label = tracker.get(runId).orElseThrow().label();
        assertEquals(phrases.get((elapsedSeconds / 4) % phrases.size()), label, "after " + elapsedSeconds + " s");
        assertTrue(phrases.contains(label), "the label never leaves the table");
    }
}
