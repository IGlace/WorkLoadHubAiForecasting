package com.workloadhub.forecastweb.api;

import com.workloadhub.forecast.api.AccuracyResult;
import com.workloadhub.forecast.api.CurrentDayForecast;
import com.workloadhub.forecast.api.MemberWindowForecast;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunSummary;
import com.workloadhub.forecastweb.api.Views.MemberView;
import com.workloadhub.forecastweb.api.Views.RunView;
import com.workloadhub.forecastweb.api.Views.StartedRun;
import com.workloadhub.forecastweb.host.ActingUser;
import com.workloadhub.forecastweb.host.Directory;
import com.workloadhub.forecastweb.host.HostForecastFacade;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The host routes of design 2026-09-11, section 3.3: runs, progress, the current forecast, the run list, accuracy. */
@RestController
@RequestMapping("/api")
public class ForecastController {

    public static final int CURRENT_DEFAULT_DAYS = 20;
    public static final int ACCURACY_DEFAULT_DAYS = 28;

    private final HostForecastFacade facade;
    private final Directory directory;
    private final Clock clock;

    public ForecastController(HostForecastFacade facade, Directory directory, Clock clock) {
        this.facade = facade;
        this.directory = directory;
        this.clock = clock;
    }

    /** Starts a run for the team and returns at once; the page polls progress. The run day is the host's today. */
    @PostMapping("/teams/{teamId}/forecast-runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartedRun start(ActingUser user, @PathVariable UUID teamId) {
        return new StartedRun(facade.startRun(user, teamId));
    }

    @GetMapping("/teams/{teamId}/forecast-runs")
    public List<RunSummary> runs(ActingUser user, @PathVariable UUID teamId, @RequestParam(defaultValue = "20") int limit) {
        return facade.listRuns(user, teamId, limit);
    }

    /** The latest value per member and day, whichever run produced it: what the team page reads. */
    @GetMapping("/teams/{teamId}/forecast")
    public List<CurrentDayForecast> current(ActingUser user, @PathVariable UUID teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        return facade.currentForecast(user, teamId, from == null ? today : from, to == null ? today.plusDays(CURRENT_DEFAULT_DAYS) : to);
    }

    /** The forecasts made before each past weekday compared with the hours logged on it; recomputed on every call. */
    @GetMapping("/teams/{teamId}/accuracy")
    public AccuracyResult accuracy(ActingUser user, @PathVariable UUID teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate today = LocalDate.now(clock);
        return facade.accuracy(user, teamId, from == null ? today.minusDays(ACCURACY_DEFAULT_DAYS) : from, to == null ? today.minusDays(1) : to);
    }

    /** The whole run, DONE runs only: windows, days, scores, the facts as an object and the members named. */
    @GetMapping("/forecast-runs/{id}")
    public RunView run(ActingUser user, @PathVariable UUID id) {
        RunResult r = facade.getRun(user, id);
        Set<UUID> ids = new LinkedHashSet<>();
        for (MemberWindowForecast w : r.memberWindows()) {
            ids.add(w.userId());
        }
        Map<UUID, Directory.User> users = directory.usersById();
        List<MemberView> members = ids.stream().map(users::get).filter(u -> u != null)
                .map(u -> new MemberView(u.id(), u.fullName(), u.role(), u.jobTitle())).toList();
        return new RunView(r.run(), r.scores(), r.memberWindows(), r.memberDays(), Json.parse(r.factsJson()), members);
    }

    @GetMapping("/forecast-runs/{id}/progress")
    public RunProgress progress(ActingUser user, @PathVariable UUID id) {
        return facade.progress(user, id);
    }
}
