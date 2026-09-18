package com.workloadhub.forecastweb.api;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecastweb.api.Views.NarrateBody;
import com.workloadhub.forecastweb.api.Views.NarrationStarted;
import com.workloadhub.forecastweb.api.Views.NarrativeView;
import com.workloadhub.forecastweb.host.ActingUser;
import com.workloadhub.forecastweb.host.HostForecastFacade;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narration (design 2026-09-11, section 3.4): the host submits it to its own executor and answers 202; the page
 * polls progress (NARRATING, then NARRATED or NARRATION_FAILED) and reads the stored narrative here.
 */
@RestController
@RequestMapping("/api/forecast-runs/{id}/narratives")
public class NarrativeController {

    private final HostForecastFacade facade;

    public NarrativeController(HostForecastFacade facade) {
        this.facade = facade;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public NarrationStarted narrate(ActingUser user, @PathVariable UUID id, @RequestBody(required = false) NarrateBody body) {
        String language = body == null || body.language() == null || body.language().isBlank() ? "en" : body.language().trim().toLowerCase(Locale.ROOT);
        if (!language.equals("en") && !language.equals("fr")) {
            throw ForecastException.invalidRequest("language must be en or fr");
        }
        facade.narrate(user, id, language, body == null ? null : body.model());
        return new NarrationStarted(id, language, "NARRATING");
    }

    @GetMapping("/{lang}")
    public NarrativeView narrative(ActingUser user, @PathVariable UUID id, @PathVariable String lang) {
        NarrativeResult n = facade.narrative(user, id, lang)
                .orElseThrow(() -> ForecastException.of("NARRATIVE_NOT_FOUND", "no " + lang + " narrative for run " + id));
        return new NarrativeView(n.id(), n.runId(), n.language(), n.status(), n.model(), Json.parse(n.narrativeJson()), n.rawText(),
                Json.parse(n.verificationJson()), Json.parse(n.usageJson()), n.error(), n.attempts(), n.toolCalls(), n.createdAt());
    }
}
