package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.copilot.SystemMessageMode;
import com.github.copilot.generated.AssistantIntentEvent;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantReasoningDeltaEvent;
import com.github.copilot.generated.AssistantReasoningEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResultKind;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.ToolDefinition;
import com.workloadhub.forecast.testing.SeededFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdkCopilotGatewayTest {

    @Test
    void optionsCarryTheTokenTheHomeAndNoLoggedInUser(@TempDir Path dir) {
        CopilotClientOptions o = SdkCopilotGateway.options("gho_abc", dir.resolve("copilot"), "");
        assertEquals("gho_abc", o.getGitHubToken());
        assertEquals(Optional.of(false), o.getUseLoggedInUser());
        assertEquals(dir.resolve("copilot").toAbsolutePath().toString(), o.getCopilotHome());
        assertEquals("error", o.getLogLevel());
        assertNull(o.getCliPath(), "blank cli-path means the in-process runtime");
        assertEquals("workloadhub-forecast", o.getClientInfo().getApplicationName());
        CopilotClientOptions sub = SdkCopilotGateway.options("gho_abc", dir, " /opt/copilot/copilot ");
        assertEquals("/opt/copilot/copilot", sub.getCliPath());
    }

    @Test
    void theSessionConfigIsTheSpecMappedOntoTheSdk() {
        FactsTools tools = new FactsTools(SeededFacts.facts());
        List<NarrationEvent> seen = new ArrayList<>();
        SessionConfig cfg = SdkCopilotGateway.sessionConfig(new SessionSpec(null, "SYSTEM", tools.specs()), seen::add);
        assertNull(cfg.getModel());
        assertEquals(SystemMessageMode.REPLACE, cfg.getSystemMessage().getMode());
        assertEquals("SYSTEM", cfg.getSystemMessage().getContent());
        assertEquals(FactsTools.NAMES, cfg.getTools().stream().map(ToolDefinition::name).toList());
        assertTrue(cfg.getTools().stream().allMatch(t -> Boolean.TRUE.equals(t.skipPermission())));
        assertEquals(List.of("custom:*"), cfg.getAvailableTools());
        assertEquals(Optional.of(false), cfg.getEnableSkills());
        assertEquals(Optional.of(false), cfg.getEnableConfigDiscovery());
        assertEquals(Optional.of(false), cfg.getEnableSessionStore());
        assertEquals(Optional.of(true), cfg.getSkipCustomInstructions());
        assertEquals(Optional.of(false), cfg.getInfiniteSessions().getEnabled());
        assertNotNull(cfg.getOnPermissionRequest());
        assertNotNull(cfg.getOnEvent());
        assertEquals("gpt-5-mini", SdkCopilotGateway.sessionConfig(new SessionSpec("gpt-5-mini", "S", List.of()), seen::add).getModel());

        AssistantMessageDeltaEvent delta = new AssistantMessageDeltaEvent();
        delta.setData(new AssistantMessageDeltaEvent.AssistantMessageDeltaEventData("m1", "{\"run", null));
        cfg.getOnEvent().accept(delta);
        assertEquals(NarrationEvent.Kind.ANSWER_DELTA, seen.get(0).kind());
        assertEquals("{\"run", seen.get(0).text());
    }

    @Test
    void toolDefinitionsCallTheHandlersWithTheMemberId() throws Exception {
        List<ToolSpec> specs = List.of(
                new ToolSpec("get_run_overview", "overview", false, id -> Map.of("got", String.valueOf(id))),
                new ToolSpec("get_member_forecast", "forecast", true, id -> Map.of("got", id)));
        List<ToolDefinition> defs = SdkCopilotGateway.toolDefinitions(specs);
        assertEquals(2, defs.size());
        assertEquals("get_run_overview", defs.get(0).name());
        assertEquals("overview", defs.get(0).description());
        assertTrue(defs.get(0).skipPermission());
        assertNotNull(defs.get(1).parameters(), "the member tool declares its member_id parameter");
        assertTrue(defs.get(1).parameters().toString().contains("member_id"));
    }

    @Test
    void thePermissionHandlerApprovesOnlyTheModulesTools() throws Exception {
        var handler = SdkCopilotGateway.permissionHandler(Set.of("get_run_overview"));
        PermissionRequest ours = new PermissionRequest();
        ours.setKind("custom-tool");
        ours.setExtensionData(Map.of("toolName", "get_run_overview"));
        assertEquals(PermissionRequestResultKind.APPROVED.getValue(), handler.handle(ours, null).get().getKind());
        PermissionRequest other = new PermissionRequest();
        other.setKind("custom-tool");
        other.setExtensionData(Map.of("toolName", "write_file"));
        assertEquals(PermissionRequestResultKind.REJECTED.getValue(), handler.handle(other, null).get().getKind());
        PermissionRequest shell = new PermissionRequest();
        shell.setKind("commands");
        assertEquals(PermissionRequestResultKind.REJECTED.getValue(), handler.handle(shell, null).get().getKind());
    }

    @Test
    void eventsAreMappedToTheNarratorsKinds() {
        AssistantIntentEvent intent = new AssistantIntentEvent();
        intent.setData(new AssistantIntentEvent.AssistantIntentEventData("Reading the facts"));
        assertEquals(NarrationEvent.intent("Reading the facts"), SdkCopilotGateway.mapEvent(intent));
        AssistantReasoningDeltaEvent rd = new AssistantReasoningDeltaEvent();
        rd.setData(new AssistantReasoningDeltaEvent.AssistantReasoningDeltaEventData("r1", "Yara "));
        assertEquals(NarrationEvent.thinkingDelta("r1", "Yara "), SdkCopilotGateway.mapEvent(rd));
        AssistantReasoningEvent rf = new AssistantReasoningEvent();
        rf.setData(new AssistantReasoningEvent.AssistantReasoningEventData("r1", "Yara is over", null));
        assertEquals(NarrationEvent.thinkingFull("r1", "Yara is over"), SdkCopilotGateway.mapEvent(rf));
        SessionErrorEvent err = new SessionErrorEvent();
        err.setData(new SessionErrorEvent.SessionErrorEventData("model", "429", null, "rate limited", null, null, null, null, null, null));
        assertEquals(NarrationEvent.error("rate limited"), SdkCopilotGateway.mapEvent(err));
        assertNull(SdkCopilotGateway.mapEvent(new com.github.copilot.generated.SessionIdleEvent()), "ignored kinds map to null");
    }

    @Test
    void runtimeInfoReportsTheConfiguredCliWithoutStartingAnything(@TempDir Path dir) throws Exception {
        Path cli = dir.resolve("copilot");
        Files.writeString(cli, "#!/bin/sh\n");
        cli.toFile().setExecutable(true);
        RuntimeInfo info = new SdkCopilotGateway(dir.resolve("home"), cli.toString()).runtime();
        assertTrue(info.available());
        assertEquals(cli.toString(), info.path());
        assertEquals("1.0.13-preview.6", info.version());
        RuntimeInfo missing = new SdkCopilotGateway(dir.resolve("home"), dir.resolve("nope").toString()).runtime();
        assertFalse(missing.available());
        assertTrue(missing.message().contains("nope"));
        assertEquals("1.0.13-preview.6", SdkCopilotGateway.sdkVersion());
    }
}
