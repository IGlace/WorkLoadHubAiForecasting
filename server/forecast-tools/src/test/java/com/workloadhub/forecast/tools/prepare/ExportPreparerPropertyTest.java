package com.workloadhub.forecast.tools.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import com.workloadhub.forecast.tools.export.ExportFiles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * The invariants of a prepared export. Broken, each produces a failure that is silent or far from its cause: a
 * user left inactive is dropped by {@code ForecastRepository} without a word and simply never appears in a
 * forecast, and a leader left as a MEMBER leads a team nobody is allowed to run.
 *
 * <p>The assertions restate the rule rather than calling {@code EffectiveRole}, which is what {@code prepare}
 * itself uses: an oracle built from the implementation proves only that it equals itself.
 *
 * <p>Random directories rather than one fixture, because the shapes that break these are structural — a
 * manager outside the export, a chain three deep, a cycle-free tree with several roots — and a hand-written
 * fixture covers the shapes its author thought of.
 */
class ExportPreparerPropertyTest {

    // Arrays.asList, not List.of: a real export has users with no department at all, and List.of rejects null.
    private static final List<String> DEPARTMENTS =
            Arrays.asList("PTE / CT2 Calibration & Testing 2", "PTE / CT2", "PTE / SIM Simulation", "ADM / HR People", null);

    // Every shape the rule must separate: the two actor titles, two leader spellings, plain members, none.
    private static final List<String> TITLES = Arrays.asList("Skill Team Leader", "Engineering Center Manager",
            "Team Leader Calibration", "SW Lead Engineer", "Calibration Engineer", "Simulation Engineer", null);

    private static final List<String> ROLES = List.of("MEMBER", "MEMBER", "MEMBER", "TEAM_LEADER", "VIEWER", "ADMIN", "CENTER_MANAGER");

    /** The roles no job title implies, which the application assigns by hand and this step keeps. */
    private static final Set<String> DECLARED_WINS = Set.of("ADMIN", "CENTER_MANAGER", "VIEWER");

    private static final Set<String> COUNTED = Set.of("MEMBER", "TEAM_LEADER");

    /** A directory of 1 to 40 people with random departments, titles, roles and managers, from one seed. */
    private static ExportEnvelope directory(long seed) {
        Random random = new Random(seed);
        int count = 1 + random.nextInt(40);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(new UUID(0x3000000000000000L, i + 1L));
        }
        List<LinkedHashMap<String, Object>> users = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // a manager earlier in the list, outside the list entirely, or none: all three occur in a real export
            UUID manager = null;
            int pick = random.nextInt(3);
            if (pick == 0 && i > 0) {
                manager = ids.get(random.nextInt(i));
            } else if (pick == 1) {
                manager = new UUID(0x9000000000000000L, i + 1L);
            }
            users.add(ExportPreparerTest.user(ids.get(i), "Person " + (i + 1), TITLES.get(random.nextInt(TITLES.size())),
                    DEPARTMENTS.get(random.nextInt(DEPARTMENTS.size())), manager, ROLES.get(random.nextInt(ROLES.size())),
                    random.nextBoolean()));
        }
        return ExportPreparerTest.envelope(users, new ArrayList<>(), new ArrayList<>());
    }

    /** What the title says, restated here rather than borrowed from the class under test. */
    private static String byTitle(String declaredRole, String jobTitle) {
        if (DECLARED_WINS.contains(declaredRole)) {
            return declaredRole;
        }
        String t = jobTitle == null ? "" : jobTitle.toLowerCase(Locale.ROOT);
        if (t.contains("skill team leader")) {
            return "SKILL_TEAM_LEADER";
        }
        if (t.contains("center manager")) {
            return "CENTER_MANAGER";
        }
        return t.contains("team lead") || t.contains("lead engineer") ? "TEAM_LEADER" : "MEMBER";
    }

    @Property(tries = 300)
    void everyUserIsActiveAndCarriesTheRoleTheirTitleGivesThem(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        ExportEnvelope input = directory(seed);
        Map<Object, String> titled = new HashMap<>();
        for (LinkedHashMap<String, Object> u : input.rows("users")) {
            titled.put(u.get("id"), byTitle(String.valueOf(u.get("role")), (String) u.get("job_title")));
        }
        // Counted reports, of the activated directory: prepare activates everyone before it classifies.
        Map<Object, Integer> reports = new HashMap<>();
        for (LinkedHashMap<String, Object> u : input.rows("users")) {
            Object manager = u.get("manager_id");
            if (manager != null && !manager.equals(u.get("id")) && titled.containsKey(manager) && COUNTED.contains(titled.get(u.get("id")))) {
                reports.merge(manager, 1, Integer::sum);
            }
        }

        List<LinkedHashMap<String, Object>> after = ExportPreparer.prepare(input).envelope().rows("users");

        assertEquals(titled.size(), after.size(), "nobody is added or lost");
        for (LinkedHashMap<String, Object> u : after) {
            Object id = u.get("id");
            String role = String.valueOf(u.get("role"));
            assertEquals(Boolean.TRUE, u.get("active"), id + " must be active");
            assertNull(u.get("deactivated_at"), id + " must carry no leaving date");
            String expected = titled.get(id);
            if ("TEAM_LEADER".equals(expected) && reports.getOrDefault(id, 0) == 0) {
                assertEquals("MEMBER", role, id + " leads nobody, so they are a member");
            } else {
                assertEquals(expected, role, id + "'s role must be the one their title gives them");
            }
        }
    }

    @Property(tries = 100)
    void everyTeamLeaderLeadsSomebodyAndNoActorIsCounted(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        List<LinkedHashMap<String, Object>> after = ExportPreparer.prepare(directory(seed)).envelope().rows("users");
        Map<Object, String> roles = new HashMap<>();
        after.forEach(u -> roles.put(u.get("id"), String.valueOf(u.get("role"))));

        Map<Object, Integer> countedReports = new HashMap<>();
        for (LinkedHashMap<String, Object> u : after) {
            Object manager = u.get("manager_id");
            if (manager != null && !manager.equals(u.get("id")) && roles.containsKey(manager) && COUNTED.contains(roles.get(u.get("id")))) {
                countedReports.merge(manager, 1, Integer::sum);
            }
        }
        for (LinkedHashMap<String, Object> u : after) {
            String role = roles.get(u.get("id"));
            if ("TEAM_LEADER".equals(role)) {
                // Every team leader keys a team, so the count of them is the count of teams.
                assertTrue(countedReports.getOrDefault(u.get("id"), 0) > 0,
                        u.get("id") + " came out a team leader with nobody counted under them");
            }
            // An actor is never demoted into a counted role: they lead nobody the forecast can see either,
            // but they do no technical work, so making them a member would put them in a forecast.
            String title = (String) u.get("job_title");
            if (title != null && title.toLowerCase(Locale.ROOT).contains("skill team leader")
                    && !DECLARED_WINS.contains(String.valueOf(u.get("role")))) {
                assertEquals("SKILL_TEAM_LEADER", role, u.get("id") + " is an actor and must stay one");
            }
        }
    }

    @Property(tries = 100)
    void theCountersDescribeTheFileTheyCameWith(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        ExportPreparer.Result result = ExportPreparer.prepare(directory(seed));
        List<LinkedHashMap<String, Object>> after = result.envelope().rows("users");
        long counted = after.stream().filter(u -> COUNTED.contains(String.valueOf(u.get("role")))).count();
        long leaders = after.stream().filter(u -> "TEAM_LEADER".equals(String.valueOf(u.get("role")))).count();
        long skill = after.stream().filter(u -> "SKILL_TEAM_LEADER".equals(String.valueOf(u.get("role")))).count();
        assertEquals(counted, result.countedMembers());
        assertEquals(leaders, result.teamLeaders());
        assertEquals(skill, result.skillTeamLeaders());
    }

    @Property(tries = 100)
    void preparingAPreparedExportChangesNothing(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        // It writes roles back into the file it reads, so a second run over its own output must be a no-op.
        ExportEnvelope once = ExportPreparer.prepare(directory(seed)).envelope();
        assertEquals(ExportFiles.toJson(once), ExportFiles.toJson(ExportPreparer.prepare(once).envelope()));
    }

    @Property(tries = 100)
    void twoRunsOverOneExportProduceTheSameFile(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        ExportEnvelope first = ExportPreparer.prepare(directory(seed)).envelope();
        ExportEnvelope second = ExportPreparer.prepare(directory(seed)).envelope();
        assertEquals(ExportFiles.toJson(first), ExportFiles.toJson(second));
    }
}
