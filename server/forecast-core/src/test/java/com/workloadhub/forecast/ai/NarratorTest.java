package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NarrationProgress.Step;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.testing.SeededFacts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NarratorTest {

    static final JsonNode FACTS = SeededFacts.facts();
    static final String GOOD = FakeGateway.goodNarrative(FACTS);
    static final Prompts PROMPTS = Prompts.load();

    /** Records every step and live chunk; "reset" marks an answer reset. */
    static final class Recorder implements NarrationProgress {
        final List<String> steps = new ArrayList<>();
        final List<String> thinking = new ArrayList<>();
        final List<String> answer = new ArrayList<>();

        @Override
        public void step(Step step, String detail) {
            steps.add(step + (detail == null ? "" : ":" + detail));
        }

        @Override
        public void thinking(String text) {
            thinking.add(text);
        }

        @Override
        public void answer(String text) {
            answer.add(text);
        }

        @Override
        public void resetAnswer() {
            answer.add("reset");
        }
    }

    static Narrator narrator(FakeGateway g) {
        return new Narrator(g, PROMPTS, Duration.ofSeconds(30), "");
    }

    static NarrationOutcome narrate(FakeGateway g) {
        return narrator(g).narrate(FACTS, "en", null, "gho_secret", NarrationProgress.none());
    }

    static JsonNode usage(NarrationOutcome o) {
        return ExportFiles.mapper().readTree(o.usageJson());
    }

    @Test
    void happyPathReturnsOkAndCleansUp() {
        FakeGateway g = new FakeGateway(GOOD);
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.OK, o.status());
        assertNull(o.reason());
        assertNull(o.error());
        assertEquals("gpt-5", o.model());
        assertEquals(1, o.attempts());
        assertEquals("metrics", usage(o).path("source").asText());
        assertEquals(100, usage(o).path("input_tokens").asInt());
        assertEquals(List.of("get_run_overview", "get_member_history"), o.toolCalls());
        assertTrue(o.narrativeJson().contains("All members within capacity."));
        assertNull(o.rawText());
        assertTrue(o.verificationJson().contains("\"unverified\""));
        assertEquals("gho_secret", g.tokenSeen);
        assertTrue(g.opened && g.closed && g.session.closed);
        assertNull(g.spec.model(), "blank model means the account default");
        assertTrue(g.spec.systemMessage().contains("## Skill: whf-domain"));
        assertEquals(FactsTools.NAMES, g.spec.tools().stream().map(ToolSpec::name).toList());
        assertTrue(g.session.prompts.get(0).contains("Return only the JSON document."));
    }

    @Test
    void theModelOverrideWinsOverTheDefault() {
        FakeGateway g = new FakeGateway(GOOD);
        new Narrator(g, PROMPTS, Duration.ofSeconds(30), "gpt-5-mini").narrate(FACTS, "fr", "claude-sonnet-4", "gho_x", NarrationProgress.none());
        assertEquals("claude-sonnet-4", g.spec.model());
        assertTrue(g.session.prompts.get(0).contains("Language: fr"));
        FakeGateway h = new FakeGateway(GOOD);
        new Narrator(h, PROMPTS, Duration.ofSeconds(30), "gpt-5-mini").narrate(FACTS, "en", " ", "gho_x", NarrationProgress.none());
        assertEquals("gpt-5-mini", h.spec.model());
    }

    @Test
    void invalidJsonIsRetriedOnceThenAccepted() {
        FakeGateway g = new FakeGateway("Sure! Here it is: {", GOOD);
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.OK, o.status());
        assertEquals(2, o.attempts());
        assertTrue(g.session.prompts.get(1).contains("not valid JSON"));
        assertTrue(g.session.prompts.get(1).contains("only the JSON"));
    }

    @Test
    void persistentInvalidOutputFailsWithTheProblems() {
        FakeGateway g = new FakeGateway("nope", "still nope");
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.FAILED, o.status());
        assertEquals("invalid_output", o.reason());
        assertTrue(o.error().startsWith("invalid_output: "));
        assertEquals("still nope", o.rawText());
        assertNull(o.narrativeJson());
        assertEquals("{}", o.verificationJson());
        assertEquals(2, o.attempts());
        assertTrue(g.closed);
    }

    @Test
    void unverifiedNumbersDowngradeTheStatus() {
        String text = GOOD.replace("All members within capacity.", "Demand will hit 999.5 h.");
        NarrationOutcome o = narrate(new FakeGateway(text));
        assertEquals(NarrativeStatus.UNVERIFIED, o.status());
        assertTrue(o.verificationJson().contains("999.5"));
        assertNotNull(o.narrativeJson());
    }

    @Test
    void aRejectedTokenIsThrownBeforeAnySession() {
        FakeGateway g = new FakeGateway(GOOD);
        g.authenticated = false;
        ForecastException e = assertThrows(ForecastException.class, () -> narrate(g));
        assertEquals("TOKEN_REJECTED", e.code());
        assertTrue(e.getMessage().contains("not signed in"));
        assertNull(g.session);
        assertTrue(g.closed);
    }

    @Test
    void anUnreadableAuthStatusIsARejectedToken() {
        FakeGateway g = new FakeGateway(GOOD);
        g.authError = new IllegalStateException("auth server unreachable");
        ForecastException e = assertThrows(ForecastException.class, () -> narrate(g));
        assertEquals("TOKEN_REJECTED", e.code());
        assertTrue(e.getMessage().contains("auth server unreachable"));
        assertTrue(g.closed);
    }

    @Test
    void aRuntimeThatCannotStartIsCopilotUnavailable() {
        FakeGateway g = new FakeGateway(GOOD);
        g.openError = new IllegalStateException("runtime.node not found");
        ForecastException e = assertThrows(ForecastException.class, () -> narrate(g));
        assertEquals("COPILOT_UNAVAILABLE", e.code());
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void aSessionThatCannotBeCreatedIsCopilotUnavailable() {
        FakeGateway g = new FakeGateway(GOOD);
        g.sessionError = new IllegalStateException("bad model");
        ForecastException e = assertThrows(ForecastException.class, () -> narrate(g));
        assertEquals("COPILOT_UNAVAILABLE", e.code());
        assertTrue(e.getMessage().contains("bad model"));
        assertTrue(g.closed);
    }

    @Test
    void aTimeoutIsReported() {
        FakeGateway g = new FakeGateway(FakeGateway.TIMEOUT);
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.FAILED, o.status());
        assertEquals("timeout", o.reason());
        assertTrue(o.error().startsWith("timeout: no answer within"));
        assertTrue(g.closed && g.session.closed);
    }

    @Test
    void aModelErrorIsReportedWithTheSessionError() {
        FakeGateway g = new FakeGateway(new IllegalStateException("model call failed"));
        g.sessionErrorText = "rate limited";
        NarrationOutcome o = narrate(g);
        assertEquals("model_error", o.reason());
        assertTrue(o.error().contains("model call failed") && o.error().contains("rate limited"));
    }

    @Test
    void aNullReplyIsInvalidOutput() {
        FakeGateway g = new FakeGateway();
        g.replyNull = true;
        NarrationOutcome o = narrate(g);
        assertEquals("invalid_output", o.reason());
        assertEquals("", o.rawText());
        assertEquals(2, o.attempts());
    }

    @Test
    void aCloseFailureDoesNotMaskASuccessfulNarrative() {
        FakeGateway g = new FakeGateway(GOOD);
        g.closeThrows = true;
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.OK, o.status());
        assertTrue(g.session.closed && g.closed);
    }

    @Test
    void intentAndReasoningDeltasAreForwardedAsThinking() {
        FakeGateway g = new FakeGateway(GOOD);
        g.intents = List.of("Reading the capacity of each member");
        g.reasoningDeltas = List.of(new String[] {"r1", "Yara is "}, new String[] {"r1", "over capacity"});
        Recorder r = new Recorder();
        narrator(g).narrate(FACTS, "en", null, "gho_x", r);
        assertEquals("Reading the capacity of each member\nYara is over capacity", String.join("", r.thinking));
    }

    @Test
    void aFullReasoningAlreadyStreamedIsNotRepeated() {
        FakeGateway g = new FakeGateway(GOOD);
        g.reasoningDeltas = List.<String[]>of(new String[] {"r1", "Yara is over capacity"});
        g.reasoningFull = List.<String[]>of(new String[] {"r1", "Yara is over capacity"});
        Recorder r = new Recorder();
        narrator(g).narrate(FACTS, "en", null, "gho_x", r);
        assertEquals("Yara is over capacity", String.join("", r.thinking));
    }

    @Test
    void aFullReasoningNeverStreamedIsShown() {
        FakeGateway g = new FakeGateway(GOOD);
        g.reasoningFull = List.<String[]>of(new String[] {"r2", "Checking the weeks"});
        Recorder r = new Recorder();
        narrator(g).narrate(FACTS, "en", null, "gho_x", r);
        assertEquals("Checking the weeks\n", String.join("", r.thinking));
    }

    @Test
    void messageDeltasAreTheAnswerAndEveryAttemptStartsANewOne() {
        FakeGateway g = new FakeGateway("not JSON", GOOD);
        g.messageDeltas = List.of("{");
        Recorder r = new Recorder();
        narrator(g).narrate(FACTS, "en", null, "gho_x", r);
        assertEquals(List.of("reset", "{", "reset", "{"), r.answer);
    }

    @Test
    void aTurnWithoutAFinalMessageIsReadFromTheDeltas() {
        FakeGateway g = new FakeGateway(GOOD);
        g.finalMessage = false;
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.OK, o.status());
        assertEquals(1, o.attempts());
        assertTrue(o.narrativeJson().contains("All members within capacity."));
    }

    @Test
    void aDeltasOnlyRetryForgetsTheRejectedAttempt() {
        FakeGateway g = new FakeGateway("not JSON", GOOD);
        g.finalMessage = false;
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.OK, o.status());
        assertEquals(2, o.attempts());
    }

    @Test
    void toolCallsAreStepsNamingTheTool() {
        FakeGateway g = new FakeGateway(GOOD);
        g.unmatchedToolDoneId = "never-started";
        Recorder r = new Recorder();
        narrator(g).narrate(FACTS, "en", null, "gho_x", r);
        assertTrue(r.steps.contains("TOOL:get_run_overview"), r.steps.toString());
        assertTrue(r.steps.indexOf("TOOL_DONE:get_run_overview") > r.steps.indexOf("TOOL:get_run_overview"));
        assertTrue(r.steps.contains("TOOL_DONE"), "a completion whose start was never seen is still a step, naming no tool");
        assertEquals(List.of("STARTING", "SESSION", "ASKING:1"), r.steps.subList(0, 3));
        assertTrue(r.steps.contains("CHECKING"));
    }

    @Test
    void theSessionMetricsSayWhatTheNarrationCost() {
        FakeGateway g = new FakeGateway(GOOD);
        g.metrics = FakeGateway.metrics(2.5e9);
        JsonNode u = usage(narrate(g));
        assertEquals("metrics", u.path("source").asText());
        assertEquals(2.5, u.path("ai_credits").asDouble(), 1e-9);
        assertEquals(0.025, u.path("usd").asDouble(), 1e-9);
        assertEquals(100, u.path("input_tokens").asInt());
        assertEquals(1.0, u.path("premium_requests").asDouble(), 1e-9);
        assertEquals(2.5, u.path("api_seconds").asDouble(), 1e-9);
        assertEquals(List.of("usage", "close"), g.session.calls, "read while the session is still open");
    }

    @Test
    void aFailedNarrationStillReportsWhatItCost() {
        NarrationOutcome o = narrate(new FakeGateway("nope", "still nope"));
        assertEquals(NarrativeStatus.FAILED, o.status());
        assertEquals("metrics", usage(o).path("source").asText());
        assertEquals(1.5, usage(o).path("ai_credits").asDouble(), 1e-9);
    }

    @Test
    void unavailableMetricsFallBackToTheStreamedEvents() {
        FakeGateway g = new FakeGateway(GOOD);
        g.metricsError = new IllegalStateException("usage rpc unavailable");
        JsonNode u = usage(narrate(g));
        assertEquals("events", u.path("source").asText());
        assertEquals(100, u.path("input_tokens").asInt());
        assertEquals(50, u.path("output_tokens").asInt());
        assertEquals(20, u.path("cache_read_tokens").asInt());
        assertEquals(1, u.path("requests").asInt());
        assertTrue(u.path("ai_credits").isNull() && u.path("usd").isNull() && u.path("premium_requests").isNull());
    }
}
