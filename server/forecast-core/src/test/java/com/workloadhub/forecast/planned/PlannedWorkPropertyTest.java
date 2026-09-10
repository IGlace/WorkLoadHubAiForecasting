package com.workloadhub.forecast.planned;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.MemberRow;
import com.workloadhub.forecast.data.rows.TaskRow;
import com.workloadhub.forecast.lifecycle.Lifecycle;
import com.workloadhub.forecast.lifecycle.TaskFacts;
import com.workloadhub.forecast.testing.TestData;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

class PlannedWorkPropertyTest {

    static final LocalDate AS_OF = LocalDate.of(2026, 9, 2);
    static final UUID PROJECT = TestData.id("proj");

    @Provide
    Arbitrary<List<Integer>> assignments() {
        // each entry: which of four members took a task, and 0..3 the project/type combination
        return Arbitraries.integers().between(0, 15).list().ofMinSize(0).ofMaxSize(40);
    }

    @Property(tries = 60)
    void weightsArePositiveAndSumToOneForAnyHistory(@ForAll("assignments") List<Integer> codes) {
        List<MemberRow> members = List.of(TestData.member("m0", TestData.TEAM), TestData.member("m1", TestData.TEAM),
                TestData.member("m2", TestData.TEAM), TestData.member("m3", TestData.TEAM));
        List<TaskRow> tasks = new ArrayList<>();
        int i = 0;
        for (int code : codes) {
            MemberRow m = members.get(code % 4);
            UUID project = (code / 4) % 2 == 0 ? PROJECT : TestData.id("other");
            String type = (code / 8) % 2 == 0 ? "Bug" : "Task";
            tasks.add(TestData.task("h" + i++, m.id(), AS_OF.minusDays(1 + (i % 100)).atTime(9, 0), 4).withProject(project).withType(type));
        }
        TaskRow candidate = TestData.task("c", null, AS_OF.minusDays(5).atTime(9, 0), 8).withProject(PROJECT).withType("Bug");
        tasks.add(candidate);
        ForecastData data = TestData.data(members, tasks, List.of(), List.of());
        Lifecycle lc = Lifecycle.derive(data);
        List<TaskFacts> history = PlannedWork.history(lc, members, AS_OF);
        Map<UUID, Double> w = PlannedWork.weights(lc.of(candidate.id()), members, history);
        assertEquals(4, w.size());
        assertEquals(1.0, w.values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9);
        assertTrue(w.values().stream().allMatch(v -> v > 0));
    }
}
