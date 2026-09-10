package com.workloadhub.forecast.web;

import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The service, one to one, under whf.web.base-path. Authorisation is the host's: requestedBy is trusted. */
@RestController
@RequestMapping("${whf.web.base-path:/api/forecast}")
public class ForecastController {

    public record StartedRun(UUID id) {
    }

    public record NarrateBody(UUID requestedBy, String language, String model) {
    }

    public record TokenBody(String token) {
    }

    private final ForecastService service;
    private final GitHubTokenStore tokens;

    public ForecastController(ForecastService service, GitHubTokenStore tokens) {
        this.service = service;
        this.tokens = tokens;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartedRun start(@RequestBody RunRequest request) {
        return new StartedRun(service.startRun(request));
    }

    @GetMapping("/runs/{id}")
    public RunResult run(@PathVariable UUID id) {
        return service.getRun(id);
    }

    @GetMapping("/teams/{teamId}/runs")
    public List<RunSummary> runs(@PathVariable UUID teamId, @RequestParam(defaultValue = "20") int limit) {
        return service.listRuns(teamId, limit);
    }

    @GetMapping("/runs/{id}/progress")
    public RunProgress progress(@PathVariable UUID id) {
        return service.progress(id);
    }

    @PostMapping("/runs/{id}/narratives")
    public NarrativeResult narrate(@PathVariable UUID id, @RequestBody NarrateBody body) {
        return service.narrate(new NarrativeRequest(id, body.requestedBy(), body.language(), body.model()));
    }

    @GetMapping("/runs/{id}/narratives/{lang}")
    public NarrativeResult narrative(@PathVariable UUID id, @PathVariable String lang) {
        return service.narrative(id, lang).orElseThrow(() -> ForecastException.of("NARRATIVE_NOT_FOUND", "no " + lang + " narrative for run " + id));
    }

    @GetMapping("/copilot/status")
    public CopilotStatus copilotStatus(@RequestParam UUID userId) {
        return service.copilotStatus(userId);
    }

    @PutMapping("/users/{id}/github-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putToken(@PathVariable UUID id, @RequestBody TokenBody body) {
        tokens.save(id, body == null ? null : body.token());
    }

    @DeleteMapping("/users/{id}/github-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteToken(@PathVariable UUID id) {
        tokens.clear(id);
    }
}
