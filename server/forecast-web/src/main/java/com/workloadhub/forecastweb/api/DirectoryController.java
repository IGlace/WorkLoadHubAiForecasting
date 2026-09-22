package com.workloadhub.forecastweb.api;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecastweb.api.Views.MemberView;
import com.workloadhub.forecastweb.api.Views.TeamDetailView;
import com.workloadhub.forecastweb.api.Views.TeamRelation;
import com.workloadhub.forecastweb.api.Views.TeamView;
import com.workloadhub.forecastweb.api.Views.UserView;
import com.workloadhub.forecastweb.host.ActingUser;
import com.workloadhub.forecastweb.host.Directory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The directory the front end picks users and teams from. Production has its own pages for this; the module
 * never reads names. A team is a leader and their direct reports, keyed by the leader (design 2026-09-21).
 */
@RestController
@RequestMapping("/api/directory")
public class DirectoryController {

    private final Directory directory;

    public DirectoryController(Directory directory) {
        this.directory = directory;
    }

    @GetMapping("/users")
    public List<UserView> users(ActingUser actingUser) {
        Directory.Snapshot d = directory.snapshot();
        List<Directory.Team> teams = d.teams();
        Map<UUID, Directory.Team> teamById = new LinkedHashMap<>();
        teams.forEach(t -> teamById.put(t.id(), t));
        Map<UUID, List<TeamRelation>> relations = new LinkedHashMap<>();
        for (Directory.Team t : teams) {
            // The leader manages their own team; the leader above them may act for it, one team at a time --
            // but only when that manager is a SKILL_TEAM_LEADER. A team leader reporting to another team
            // leader is not allowed to run the team beneath them (ruling 3), and this page must not say they
            // are: it is the one place where a displayed relation could contradict the enforced rule.
            relations.computeIfAbsent(t.id(), k -> new ArrayList<>()).add(new TeamRelation(t.id(), t.name(), "manages"));
            if (t.reportsToId() != null && "SKILL_TEAM_LEADER".equals(d.roleOf(t.reportsToId()))) {
                relations.computeIfAbsent(t.reportsToId(), k -> new ArrayList<>()).add(new TeamRelation(t.id(), t.name(), "manages-parent"));
            }
        }
        for (Directory.Membership m : d.memberships()) {
            Directory.Team t = teamById.get(m.teamId());
            if (t != null) {
                relations.computeIfAbsent(m.userId(), k -> new ArrayList<>()).add(new TeamRelation(t.id(), t.name(), "member"));
            }
        }
        relations.values().forEach(list -> list.sort(java.util.Comparator.comparing(TeamRelation::name).thenComparing(TeamRelation::relation)));
        List<UserView> out = new ArrayList<>();
        for (Directory.User u : d.users()) {
            out.add(new UserView(u.id(), u.fullName(), u.role(), u.jobTitle(), u.department(), u.active(), relations.getOrDefault(u.id(), List.of())));
        }
        return out;
    }

    @GetMapping("/teams")
    public List<TeamView> teams(ActingUser actingUser) {
        // The team's own name is its leader's, so `managerName` is `name`: no second read to recover it.
        List<TeamView> out = new ArrayList<>();
        for (Directory.Team t : directory.snapshot().teams()) {
            out.add(new TeamView(t.id(), t.name(), t.managerId(), t.name(), t.reportsToId(), t.reportsToName(), t.memberCount()));
        }
        return out;
    }

    @GetMapping("/teams/{id}")
    public TeamDetailView team(ActingUser actingUser, @PathVariable UUID id) {
        Directory.Snapshot d = directory.snapshot();
        Directory.Team t = d.team(id).orElseThrow(() -> ForecastException.of("TEAM_NOT_FOUND", "team " + id + " does not exist"));
        Map<UUID, Directory.User> users = d.usersById();
        List<MemberView> members = d.membersOf(id).stream().map(users::get).filter(u -> u != null)
                .map(u -> new MemberView(u.id(), u.fullName(), u.role(), u.jobTitle())).toList();
        return new TeamDetailView(t.id(), t.name(), t.managerId(), t.name(), t.reportsToId(), t.reportsToName(), members);
    }
}
