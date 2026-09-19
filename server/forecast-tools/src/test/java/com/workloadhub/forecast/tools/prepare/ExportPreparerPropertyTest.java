package com.workloadhub.forecast.tools.prepare;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.tools.export.ExportEnvelope;
import com.workloadhub.forecast.tools.export.ExportFiles;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

/**
 * Three invariants of a prepared export. Broken, each produces a failure that is silent or far from its cause:
 * a user in no team is dropped by {@code ForecastRepository} without a word and simply never appears in a
 * forecast; an unresolved {@code parent_team_id} or a repeated {@code (team_id, user_id)} fails a foreign key
 * or a UNIQUE constraint at import, long after this code ran.
 *
 * <p>Random directories rather than one fixture, because the shapes that break these are structural — a manager
 * outside their reports' department, a department whose only member is its own head, nobody with a department
 * at all — and a hand-written fixture covers the shapes its author thought of.
 */
class ExportPreparerPropertyTest {

    private static final LocalDate JOINED = LocalDate.of(2021, 1, 4);

    private static final List<String> DEPARTMENTS =
            List.of("PTE / CT2 Calibration & Testing 2", "PTE / CT2", "PTE / SIM Simulation", "ADM / HR People", null);

    private static final List<String> TITLES =
            List.of("Skill Team Leader", "Calibration Engineer", "Simulation Engineer", null);

    /** A directory of 1 to 40 people with random departments, titles and managers, from one seed. */
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
                    DEPARTMENTS.get(random.nextInt(DEPARTMENTS.size())), manager, "MEMBER", random.nextBoolean()));
        }
        return ExportPreparerTest.envelope(users, new ArrayList<>(), new ArrayList<>());
    }

    @Property(tries = 300)
    void everyUserIsInATeamEveryParentResolvesAndNoMembershipRepeats(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        ExportEnvelope out = ExportPreparer.prepare(directory(seed), JOINED, ExportPreparer.SEED).envelope();

        Set<Object> teamIds = new HashSet<>();
        out.rows("teams").forEach(t -> teamIds.add(t.get("id")));

        Set<Object> placed = new HashSet<>();
        Set<String> pairs = new HashSet<>();
        for (LinkedHashMap<String, Object> m : out.rows("team_members")) {
            placed.add(m.get("user_id"));
            assertTrue(teamIds.contains(m.get("team_id")), "membership of a team that is not in the export");
            assertTrue(pairs.add(m.get("team_id") + "/" + m.get("user_id")), "(team_id, user_id) is UNIQUE");
        }
        for (LinkedHashMap<String, Object> u : out.rows("users")) {
            assertTrue(placed.contains(u.get("id")),
                    u.get("full_name") + " is in no team, so ForecastRepository would silently not count them");
        }
        for (LinkedHashMap<String, Object> t : out.rows("teams")) {
            if (t.get("parent_team_id") != null) {
                assertTrue(teamIds.contains(t.get("parent_team_id")), "parent_team_id does not resolve");
            }
        }
        assertTrue(out.rows("teams").stream().map(t -> t.get("name")).distinct().count() == out.rows("teams").size(),
                "teams.name is UNIQUE");
    }

    @Property(tries = 50)
    void twoRunsOverOneExportProduceTheSameFile(@ForAll @LongRange(min = 0, max = 100_000) long seed) {
        String first = ExportFiles.toJson(ExportPreparer.prepare(directory(seed), JOINED, ExportPreparer.SEED).envelope());
        String second = ExportFiles.toJson(ExportPreparer.prepare(directory(seed), JOINED, ExportPreparer.SEED).envelope());
        assertEquals(first, second);
    }
}
