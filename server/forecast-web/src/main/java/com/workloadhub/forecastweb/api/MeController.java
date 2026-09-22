package com.workloadhub.forecastweb.api;

import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecastweb.api.Views.MeView;
import com.workloadhub.forecastweb.api.Views.MemberView;
import com.workloadhub.forecastweb.api.Views.TeamPermission;
import com.workloadhub.forecastweb.api.Views.TokenBody;
import com.workloadhub.forecastweb.api.Views.TokenState;
import com.workloadhub.forecastweb.host.ActingUser;
import com.workloadhub.forecastweb.host.Directory;
import com.workloadhub.forecastweb.host.ForecastAccess;
import com.workloadhub.forecastweb.host.HostForecastFacade;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The acting user's own things: who they are and what they may do, their GitHub token, their Copilot seat. */
@RestController
@RequestMapping("/api/me")
public class MeController {

    private final HostForecastFacade facade;
    private final Directory directory;

    public MeController(HostForecastFacade facade, Directory directory) {
        this.facade = facade;
        this.directory = directory;
    }

    @GetMapping
    public MeView me(ActingUser user) {
        ForecastAccess access = facade.access();
        // One resolved directory for the whole loop: asking run and view per team used to re-query the acting
        // user's role twice per team and ask again whether each team exists -- about eight round trips a team.
        Directory.Snapshot d = directory.snapshot();
        List<TeamPermission> teams = new ArrayList<>();
        for (Directory.Team t : d.teams()) {
            ForecastAccess.Decision run = access.run(user.id(), t.id(), d);
            ForecastAccess.Decision view = access.view(user.id(), t.id(), d);
            teams.add(new TeamPermission(t.id(), t.name(), run.allowed(), view.allowed(), run.reason(), view.reason()));
        }
        return new MeView(new MemberView(user.id(), user.fullName(), user.role(), user.jobTitle()), facade.hasToken(user), teams);
    }

    /** Opens a Copilot session with the stored token and reports the seat: the settings page's call, not a pre-check. */
    @GetMapping("/copilot")
    public CopilotStatus copilot(ActingUser user) {
        return facade.copilotStatus(user);
    }

    @GetMapping("/github-token")
    public TokenState token(ActingUser user) {
        return new TokenState(facade.hasToken(user));
    }

    /** Stores the acting user's own token, encrypted on their users row; gho_, ghu_ and github_pat_ are accepted, classic ghp_ refused. */
    @PutMapping("/github-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putToken(ActingUser user, @RequestBody TokenBody body) {
        facade.saveToken(user, body == null ? null : body.token());
    }

    @DeleteMapping("/github-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteToken(ActingUser user) {
        facade.clearToken(user);
    }
}
