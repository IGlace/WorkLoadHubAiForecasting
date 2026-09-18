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

/** The directory the front end picks users and teams from. Production has its own pages for this; the module never reads names. */
@RestController
@RequestMapping("/api/directory")
public class DirectoryController {

    private final Directory directory;

    public DirectoryController(Directory directory) {
        this.directory = directory;
    }

    @GetMapping("/users")
    public List<UserView> users(ActingUser actingUser) {
        List<Directory.Team> teams = directory.teams();
        Map<UUID, Directory.Team> teamById = new LinkedHashMap<>();
        teams.forEach(t -> teamById.put(t.id(), t));
        Map<UUID, List<TeamRelation>> relations = new LinkedHashMap<>();
        for (Directory.Team t : teams) {
            if (t.managerId() != null) {
                relations.computeIfAbsent(t.managerId(), k -> new ArrayList<>()).add(new TeamRelation(t.id(), t.name(), "manages"));
            }
            Directory.Team parent = t.parentTeamId() == null ? null : teamById.get(t.parentTeamId());
            if (parent != null && parent.managerId() != null) {
                relations.computeIfAbsent(parent.managerId(), k -> new ArrayList<>()).add(new TeamRelation(t.id(), t.name(), "manages-parent"));
            }
        }
        for (Directory.Membership m : directory.memberships()) {
            Directory.Team t = teamById.get(m.teamId());
            if (t != null) {
                relations.computeIfAbsent(m.userId(), k -> new ArrayList<>()).add(new TeamRelation(t.id(), t.name(), "member"));
            }
        }
        List<UserView> out = new ArrayList<>();
        for (Directory.User u : directory.users()) {
            out.add(new UserView(u.id(), u.fullName(), u.role(), u.jobTitle(), u.department(), u.active(), relations.getOrDefault(u.id(), List.of())));
        }
        return out;
    }

    @GetMapping("/teams")
    public List<TeamView> teams(ActingUser actingUser) {
        Map<UUID, Directory.User> users = directory.usersById();
        List<Directory.Team> teams = directory.teams();
        Map<UUID, Directory.Team> teamById = new LinkedHashMap<>();
        teams.forEach(t -> teamById.put(t.id(), t));
        List<TeamView> out = new ArrayList<>();
        for (Directory.Team t : teams) {
            out.add(new TeamView(t.id(), t.name(), t.managerId(), name(users, t.managerId()), t.parentTeamId(),
                    t.parentTeamId() == null || !teamById.containsKey(t.parentTeamId()) ? null : teamById.get(t.parentTeamId()).name(), t.memberCount()));
        }
        return out;
    }

    @GetMapping("/teams/{id}")
    public TeamDetailView team(ActingUser actingUser, @PathVariable UUID id) {
        Directory.Team t = directory.team(id).orElseThrow(() -> ForecastException.of("TEAM_NOT_FOUND", "team " + id + " does not exist"));
        Map<UUID, Directory.User> users = directory.usersById();
        List<MemberView> members = directory.membersOf(id).stream().map(users::get).filter(u -> u != null)
                .map(u -> new MemberView(u.id(), u.fullName(), u.role(), u.jobTitle())).toList();
        String parentName = t.parentTeamId() == null ? null : directory.team(t.parentTeamId()).map(Directory.Team::name).orElse(null);
        return new TeamDetailView(t.id(), t.name(), t.managerId(), name(users, t.managerId()), t.parentTeamId(), parentName, members);
    }

    static String name(Map<UUID, Directory.User> users, UUID id) {
        Directory.User u = id == null ? null : users.get(id);
        return u == null ? null : u.fullName();
    }
}
