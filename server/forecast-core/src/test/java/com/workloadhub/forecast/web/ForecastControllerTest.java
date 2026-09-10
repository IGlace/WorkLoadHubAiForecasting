package com.workloadhub.forecast.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ForecastController.class, properties = "whf.web.base-path=/forecast-api")
class ForecastControllerTest {

    /** The scan root: this package only, so the controller and the advice are found and nothing else. */
    @SpringBootApplication
    static class Boot {
    }

    static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID TEAM = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID USER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ForecastService service;

    @MockitoBean
    GitHubTokenStore tokens;

    static RunSummary summary() {
        return new RunSummary(RUN, TEAM, USER, LocalDate.of(2026, 9, 6), RunStatus.DONE, null, "xgboost", 0.83, null, LocalDateTime.of(2026, 9, 6, 10, 0), null);
    }

    static NarrativeResult narrative(NarrativeStatus status) {
        return new NarrativeResult(UUID.randomUUID(), RUN, "en", status, "gpt-5", status == NarrativeStatus.FAILED ? null : "{\"run_summary\":\"ok\"}",
                null, "{}", "{\"source\":\"none\"}", null, 1, 3, LocalDateTime.of(2026, 9, 6, 11, 0));
    }

    @Test
    void startsARunAndAnswers202WithItsId() throws Exception {
        when(service.startRun(any(RunRequest.class))).thenReturn(RUN);
        mvc.perform(post("/forecast-api/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\": \"" + TEAM + "\", \"requestedBy\": \"" + USER + "\", \"asOf\": \"2026-09-06\", \"forcedModel\": \"xgboost\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(RUN.toString()));
    }

    @Test
    void readsRunsProgressAndLists() throws Exception {
        when(service.getRun(RUN)).thenReturn(new RunResult(summary(), List.of(), Map.of(), Map.of(), List.of(), "{}"));
        when(service.progress(RUN)).thenReturn(new RunProgress(RUN, "NARRATING", 40, "tool get_member_forecast", "thinking", "{"));
        when(service.listRuns(TEAM, 5)).thenReturn(List.of(summary()));
        mvc.perform(get("/forecast-api/runs/" + RUN)).andExpect(status().isOk()).andExpect(jsonPath("$.run.championModel").value("xgboost"));
        mvc.perform(get("/forecast-api/runs/" + RUN + "/progress")).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("NARRATING")).andExpect(jsonPath("$.answer").value("{"));
        mvc.perform(get("/forecast-api/teams/" + TEAM + "/runs?limit=5")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(RUN.toString()));
    }

    @Test
    void narratesAndReadsNarratives() throws Exception {
        when(service.narrate(new NarrativeRequest(RUN, USER, "en", null))).thenReturn(narrative(NarrativeStatus.OK));
        when(service.narrative(RUN, "en")).thenReturn(Optional.of(narrative(NarrativeStatus.UNVERIFIED)));
        when(service.narrative(RUN, "fr")).thenReturn(Optional.empty());
        mvc.perform(post("/forecast-api/runs/" + RUN + "/narratives").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedBy\": \"" + USER + "\", \"language\": \"en\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK")).andExpect(jsonPath("$.model").value("gpt-5"));
        mvc.perform(get("/forecast-api/runs/" + RUN + "/narratives/en")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNVERIFIED"));
        mvc.perform(get("/forecast-api/runs/" + RUN + "/narratives/fr")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NARRATIVE_NOT_FOUND"));
    }

    @Test
    void tokensAndCopilotStatus() throws Exception {
        when(service.copilotStatus(USER)).thenReturn(new CopilotStatus(USER, true, true, "/x/runtime.node", "1.0.13-preview.6", true, "sara", null, "signed in as sara"));
        mvc.perform(put("/forecast-api/users/" + USER + "/github-token").contentType(MediaType.APPLICATION_JSON).content("{\"token\": \"gho_abc\"}"))
                .andExpect(status().isNoContent());
        verify(tokens).save(USER, "gho_abc");
        mvc.perform(delete("/forecast-api/users/" + USER + "/github-token")).andExpect(status().isNoContent());
        verify(tokens).clear(USER);
        mvc.perform(get("/forecast-api/copilot/status?userId=" + USER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("sara")).andExpect(jsonPath("$.hasToken").value(true));
    }

    @Test
    void errorsAreMappedByCode() throws Exception {
        when(service.getRun(RUN)).thenThrow(ForecastException.of("RUN_NOT_FOUND", "run " + RUN + " not found"));
        when(service.progress(RUN)).thenThrow(ForecastException.of("RUN_NOT_DONE", "run is QUEUED"));
        when(service.narrate(any(NarrativeRequest.class))).thenThrow(ForecastException.of("TOKEN_MISSING", "no token"));
        when(service.copilotStatus(USER)).thenThrow(ForecastException.invalidRequest("bad"));
        mvc.perform(get("/forecast-api/runs/" + RUN)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND")).andExpect(jsonPath("$.message").value("run " + RUN + " not found"));
        mvc.perform(get("/forecast-api/runs/" + RUN + "/progress")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_NOT_DONE"));
        mvc.perform(post("/forecast-api/runs/" + RUN + "/narratives").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedBy\": \"" + USER + "\", \"language\": \"en\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));
        mvc.perform(get("/forecast-api/copilot/status?userId=" + USER)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/forecast-api/runs/not-a-uuid")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/forecast-api/runs").contentType(MediaType.APPLICATION_JSON).content("{\"teamId\": \"" + TEAM + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/forecast-api/copilot/status")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("userId")));
    }
}
