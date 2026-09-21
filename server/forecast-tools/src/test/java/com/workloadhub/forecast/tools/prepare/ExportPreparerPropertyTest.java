package com.workloadhub.forecast.tools.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import com.workloadhub.forecast.tools.export.ExportFiles;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * forecast, and a manager left as a MEMBER leads a team nobody is allowed to run.
 *
 * <p>Random directories rather than one fixture, because the shapes that break these are structural — a
 * manager outside the export, a chain three deep, a cycle-free tree with several roots — and a hand-written
 * fixture covers the shapes its author thought of.
 */
class ExportPreparerPropertyTest {

    // Arrays.asList, not List.of: a real export has users with no department at all, and List.of rejects null.
    private static final List<String> DEPARTMENTS =
            Arrays.asList("PTE / CT2 Calibration & Testing 2", "PTE / CT2", "PTE / SIM Simulation", "ADM / HR People", null);

    private static final List<String> TITLES =
            Arrays.asList("Skill Team Leader", "Calibration Engineer", "Simulation Engineer", null);

    private static final List<String> ROLES = List.of("MEMBER", "MEMBER", "MEMBER", "TEAM_LEADER", "VIEWER", "ADMIN", "CENTER_MANAGER");

    private static final Set<String> FIXED = Set.of("ADMIN", "CENTER_MANAGER");

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

    @Property(tries = 300)
    void everyUserIsActiveAndEveryManagerCarriesTheLeaderRoleTheirPlaceImplies(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        ExportEnvelope input = directory(seed);
        List<LinkedHashMap<String, Object>> before = input.rows("users");
        Set<Object> ids = new HashSet<>();
        before.forEach(u -> ids.add(u.get("id")));
        Set<Object> managers = new HashSet<>();
        before.forEach(u -> {
            if (u.get("manager_id") != null && ids.contains(u.get("manager_id"))) {
                managers.add(u.get("manager_id"));
            }
        });
        Set<Object> managersOfManagers = new HashSet<>();
        before.forEach(u -> {
            if (u.get("manager_id") != null && managers.contains(u.get("id"))) {
                managersOfManagers.add(u.get("manager_id"));
            }
        });
        Map<Object, String> roleBefore = new HashMap<>();
        before.forEach(u -> roleBefore.put(u.get("id"), String.valueOf(u.get("role"))));

        List<LinkedHashMap<String, Object>> after = ExportPreparer.prepare(input).envelope().rows("users");

        assertEquals(before.size(), after.size(), "nobody is added or lost");
        for (LinkedHashMap<String, Object> u : after) {
            Object id = u.get("id");
            String role = String.valueOf(u.get("role"));
            assertEquals(Boolean.TRUE, u.get("active"), id + " must be active");
            assertNull(u.get("deactivated_at"), id + " must carry no leaving date");
            if (FIXED.contains(roleBefore.get(id))) {
                assertEquals(roleBefore.get(id), role, id + " keeps a role the project planner looks for");
            } else if (managersOfManagers.contains(id)) {
                assertEquals("SKILL_TEAM_LEADER", role, id + " manages a manager");
            } else if (managers.contains(id)) {
                assertEquals("TEAM_LEADER", role, id + " manages people and no manager");
            } else {
                assertEquals(roleBefore.get(id), role, id + " manages nobody, so their role is not this step's business");
            }
        }
    }

    @Property(tries = 100)
    void everyCountedUsersManagerLeadsATeamThatCanBeRun(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        List<LinkedHashMap<String, Object>> after = ExportPreparer.prepare(directory(seed)).envelope().rows("users");
        Map<Object, String> roles = new HashMap<>();
        after.forEach(u -> roles.put(u.get("id"), String.valueOf(u.get("role"))));
        for (LinkedHashMap<String, Object> u : after) {
            Object manager = u.get("manager_id");
            if (manager == null || !roles.containsKey(manager) || !Set.of("MEMBER", "TEAM_LEADER").contains(String.valueOf(u.get("role")))) {
                continue;
            }
            // This user is counted, so their manager keys a team. Whoever runs it is a leader or fixed role.
            assertTrue(Set.of("TEAM_LEADER", "SKILL_TEAM_LEADER", "ADMIN", "CENTER_MANAGER").contains(roles.get(manager)),
                    "the manager of a counted user is " + roles.get(manager) + ", which can lead no team");
        }
    }

    @Property(tries = 100)
    void twoRunsOverOneExportProduceTheSameFile(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        ExportEnvelope first = ExportPreparer.prepare(directory(seed)).envelope();
        ExportEnvelope second = ExportPreparer.prepare(directory(seed)).envelope();
        assertEquals(ExportFiles.toJson(first), ExportFiles.toJson(second));
    }
}
