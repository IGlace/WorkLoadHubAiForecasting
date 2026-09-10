package com.workloadhub.forecast.samplehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.testing.SeededData;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@SpringBootTest(classes = SampleHostApplication.class, properties = {"whf.web.enabled=true", "whf.run-threads=1",
        "whf.token-key=" + SampleHostIntegrationTest.KEY})
@AutoConfigureMockMvc
class SampleHostIntegrationTest {

    static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Autowired
    MockMvc mvc;

    @Autowired
    ForecastService service;

    @Autowired
    CopilotGateway gateway;

    static JsonNode json(MvcResult r) throws Exception {
        return ExportFiles.mapper().readTree(r.getResponse().getContentAsString());
    }

    @Test
    void theHostRunsNarratesAndReadsThroughTheRestSurface() throws Exception {
        assertEquals(Base64.getEncoder().encodeToString(new byte[32]), KEY);
        ForecastData data = SeededData.data();
        UUID team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
        UUID member = data.membersOfTeam(team).get(0).id();
        FakeGateway fake = (FakeGateway) gateway;

        MvcResult started = mvc.perform(post("/api/forecast/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\": \"" + team + "\", \"requestedBy\": \"" + member + "\", \"asOf\": \"" + SeededData.asOf() + "\", \"forcedModel\": \"seasonal_naive\"}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID run = UUID.fromString(json(started).path("id").asText());
        long deadline = System.currentTimeMillis() + 120_000;
        String phase = "";
        while (!phase.equals("DONE") && !phase.equals("FAILED") && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            phase = json(mvc.perform(get("/api/forecast/runs/" + run + "/progress")).andExpect(status().isOk()).andReturn()).path("phase").asText();
        }
        assertEquals("DONE", phase);
        JsonNode result = json(mvc.perform(get("/api/forecast/runs/" + run)).andExpect(status().isOk()).andReturn());
        assertTrue(result.path("memberWeeks").size() > 0);
        assertEquals("seasonal_naive", result.path("run").path("championModel").asText());

        mvc.perform(get("/api/forecast/copilot/status?userId=" + member)).andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(false));
        mvc.perform(put("/api/forecast/users/" + member + "/github-token").contentType(MediaType.APPLICATION_JSON).content("{\"token\": \"gho_sample\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/forecast/copilot/status?userId=" + member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasToken").value(true)).andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.login").value("sara"));

        fake.replies.clear();
        fake.replies.add(FakeGateway.goodNarrative(ExportFiles.mapper().readTree(result.path("factsJson").asText())));
        mvc.perform(post("/api/forecast/runs/" + run + "/narratives").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedBy\": \"" + member + "\", \"language\": \"en\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK")).andExpect(jsonPath("$.language").value("en"));
        mvc.perform(get("/api/forecast/runs/" + run + "/narratives/en")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK"));
        mvc.perform(get("/api/forecast/runs/" + run + "/narratives/fr")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NARRATIVE_NOT_FOUND"));
        mvc.perform(get("/api/forecast/runs/" + run + "/progress")).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("NARRATED"));
        mvc.perform(get("/api/forecast/runs/" + UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        mvc.perform(delete("/api/forecast/users/" + member + "/github-token")).andExpect(status().isNoContent());
        mvc.perform(get("/api/forecast/copilot/status?userId=" + member)).andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(false));
        assertEquals("gho_sample", fake.tokenSeen, "the stored token reached the gateway");
    }
}
