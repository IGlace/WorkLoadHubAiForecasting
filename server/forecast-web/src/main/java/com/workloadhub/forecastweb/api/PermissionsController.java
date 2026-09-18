package com.workloadhub.forecastweb.api;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecastweb.api.Views.PermissionView;
import com.workloadhub.forecastweb.host.ActingUser;
import com.workloadhub.forecastweb.host.Directory;
import com.workloadhub.forecastweb.host.ForecastAccess;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The permission explorer: what any user may do on any team, with the reasons. A test tool for the owner, open to every acting user. */
@RestController
@RequestMapping("/api/permissions")
public class PermissionsController {

    private final ForecastAccess access;
    private final Directory directory;

    public PermissionsController(ForecastAccess access, Directory directory) {
        this.access = access;
        this.directory = directory;
    }

    @GetMapping
    public PermissionView check(ActingUser actingUser, @RequestParam UUID userId, @RequestParam UUID teamId) {
        ActingUser subject = directory.user(userId).orElseThrow(() -> ForecastException.of("USER_NOT_FOUND", "no user " + userId));
        directory.team(teamId).orElseThrow(() -> ForecastException.of("TEAM_NOT_FOUND", "team " + teamId + " does not exist"));
        ForecastAccess.Decision run = access.run(userId, teamId);
        ForecastAccess.Decision view = access.view(userId, teamId);
        return new PermissionView(userId, subject.role(), teamId, run.allowed(), view.allowed(), run.reason(), view.reason());
    }
}
