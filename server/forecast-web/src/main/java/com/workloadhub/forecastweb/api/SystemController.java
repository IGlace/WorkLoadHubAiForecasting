package com.workloadhub.forecastweb.api;

import com.workloadhub.forecast.ForecastProperties;
import com.workloadhub.forecastweb.ForecastWebProperties;
import com.workloadhub.forecastweb.api.Views.ClockBody;
import com.workloadhub.forecastweb.api.Views.SystemView;
import com.workloadhub.forecastweb.demo.DemoClock;
import com.workloadhub.forecastweb.host.ActingUser;
import com.workloadhub.forecastweb.host.ActingUserException;
import com.workloadhub.forecastweb.host.Directory;
import com.workloadhub.forecastweb.host.HostForbidden;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the host is running on: the day, the module's settings. The only route that needs no acting user. */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final DemoClock clock;
    private final ForecastProperties module;
    private final ForecastWebProperties web;
    private final Directory directory;

    public SystemController(DemoClock clock, ForecastProperties module, ForecastWebProperties web, Directory directory) {
        this.clock = clock;
        this.module = module;
        this.web = web;
        this.directory = directory;
    }

    /**
     * Showcase only: the front end cannot read the directory before it has an acting user, so this names an
     * ADMIN (else the directory's first user) to start from.
     */
    private UUID bootstrapUserId() {
        return directory.users().stream().filter(u -> "ADMIN".equals(u.role())).findFirst().or(() -> directory.users().stream().findFirst())
                .map(Directory.User::id).orElse(null);
    }

    @GetMapping
    public SystemView system() {
        return new SystemView(clock.today(), clock.pinned() != null, web.getClock().isAdjustable(), module.getForecast().getWindows(),
                module.getDefaultWeeklyHours(), module.getRunThreads(), ActingUserException.HEADER, bootstrapUserId());
    }

    /** Demo only: an ADMIN moves the day. {@code today} null releases the pin to the system date. */
    @PostMapping("/clock")
    public SystemView clock(ActingUser user, @RequestBody ClockBody body) {
        if (!web.getClock().isAdjustable()) {
            throw new HostForbidden("the clock is not adjustable (forecast-web.clock.adjustable=false)");
        }
        if (!user.isAdmin()) {
            throw new HostForbidden("only an ADMIN may move the demo clock");
        }
        clock.pin(body == null ? null : body.today());
        return system();
    }
}
