package com.workloadhub.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NarrationProgress;
import com.workloadhub.forecast.api.ProgressLabel;
import com.workloadhub.forecast.api.RunProgress;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    void everyPhaseHasABilingualLabel() {
        RunProgressTracker t = new RunProgressTracker();
        UUID id = UUID.randomUUID();
        t.start(id);
        assertEquals(new ProgressLabel("queued", "en attente"), t.get(id).orElseThrow().label());
        for (String[] phase : new String[][] {{"LOADING", "reading the team's data", "lecture des données de l'équipe"},
                {"FEATURES", "preparing the history", "préparation de l'historique"}, {"BACKTEST", "scoring the models", "évaluation des modèles"},
                {"FORECAST", "predicting the next two weeks", "prévision des deux prochaines semaines"}, {"FACTS", "assembling the facts", "assemblage des faits"},
                {"PERSIST", "saving the run", "enregistrement"}}) {
            t.update(id, phase[0], 50, "detail");
            assertEquals(new ProgressLabel(phase[1], phase[2]), t.get(id).orElseThrow().label(), phase[0]);
            assertEquals("detail", t.get(id).orElseThrow().message());
        }
        t.done(id);
        assertEquals(new ProgressLabel("forecast ready", "prévision prête"), t.get(id).orElseThrow().label());
        t.failed(id, "boom");
        assertEquals(new ProgressLabel("forecast failed", "échec de la prévision"), t.get(id).orElseThrow().label());
        assertEquals("boom", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("working", "en cours"), RunProgressTracker.phaseLabel("SOMETHING_ELSE"));
    }

    @Test
    void narrationStepsRotateTheirLabelsByTheClockAndKeepNoText() {
        TickingClock clock = new TickingClock(Instant.parse("2026-09-06T12:00:00Z"));
        RunProgressTracker t = new RunProgressTracker(clock);
        UUID id = UUID.randomUUID();
        t.done(id);
        NarrationProgress p = t.narrationProgress(id);
        p.step(NarrationProgress.Step.STARTING, null);
        assertEquals("NARRATING", t.get(id).orElseThrow().phase());
        assertEquals(5, t.get(id).orElseThrow().percent());
        assertEquals("starting Copilot", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("starting Copilot", "démarrage de Copilot"), t.get(id).orElseThrow().label());
        p.step(NarrationProgress.Step.SESSION, null);
        assertEquals(10, t.get(id).orElseThrow().percent());
        assertEquals(new ProgressLabel("starting Copilot", "démarrage de Copilot"), t.get(id).orElseThrow().label());
        p.step(NarrationProgress.Step.ASKING, "2");
        assertEquals(20, t.get(id).orElseThrow().percent());
        assertEquals("asking Copilot (attempt 2)", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("consulting Copilot", "consultation de Copilot"), t.get(id).orElseThrow().label(), "0 s: the first phrase");
        clock.advance(Duration.ofSeconds(4));
        assertEquals(new ProgressLabel("thinking", "réflexion"), t.get(id).orElseThrow().label(), "4 s: the second phrase");
        clock.advance(Duration.ofSeconds(4));
        assertEquals(new ProgressLabel("writing the report", "rédaction du rapport"), t.get(id).orElseThrow().label(), "8 s: the third phrase");
        clock.advance(Duration.ofSeconds(4));
        assertEquals(new ProgressLabel("consulting Copilot", "consultation de Copilot"), t.get(id).orElseThrow().label(), "12 s: back to the first");
        p.thinking("Reading ");
        p.answer("{\"run_summary\"");
        p.resetAnswer();
        assertEquals(new ProgressLabel("consulting Copilot", "consultation de Copilot"), t.get(id).orElseThrow().label(), "text does not change the label");
        for (int i = 0; i < 20; i++) {
            p.step(NarrationProgress.Step.TOOL, "get_member_forecast");
        }
        assertEquals(80, t.get(id).orElseThrow().percent(), "tool calls add five points up to eighty");
        assertEquals("tool get_member_forecast", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("collecting data", "collecte des données"), t.get(id).orElseThrow().label());
        clock.advance(Duration.ofSeconds(5));
        assertEquals(new ProgressLabel("reading the forecast", "lecture de la prévision"), t.get(id).orElseThrow().label());
        p.step(NarrationProgress.Step.TOOL_DONE, "get_member_forecast");
        assertEquals("tool get_member_forecast done", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("consulting Copilot", "consultation de Copilot"), t.get(id).orElseThrow().label(), "a finished tool returns to the asking phrases");
        p.step(NarrationProgress.Step.CHECKING, null);
        assertEquals(90, t.get(id).orElseThrow().percent());
        assertEquals(new ProgressLabel("checking the numbers", "vérification des chiffres"), t.get(id).orElseThrow().label());
        t.narrated(id);
        assertEquals("NARRATED", t.get(id).orElseThrow().phase());
        assertEquals(100, t.get(id).orElseThrow().percent());
        assertEquals(new ProgressLabel("report ready", "rapport prêt"), t.get(id).orElseThrow().label());
        t.narrationFailed(id, "timeout: no answer within 300 s");
        assertEquals("NARRATION_FAILED", t.get(id).orElseThrow().phase());
        assertEquals("timeout: no answer within 300 s", t.get(id).orElseThrow().message());
        assertEquals(new ProgressLabel("narration failed", "échec de la narration"), t.get(id).orElseThrow().label());
        assertEquals(Set.of("runId", "phase", "percent", "message", "label"),
                Set.of(java.util.Arrays.stream(RunProgress.class.getRecordComponents()).map(c -> c.getName()).toArray(String[]::new)),
                "no text tails in the public record");
    }
}
