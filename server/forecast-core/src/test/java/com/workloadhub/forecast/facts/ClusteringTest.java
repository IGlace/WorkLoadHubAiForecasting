package com.workloadhub.forecast.facts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClusteringTest {

    static MemberPattern pattern(int i, double hoursPerWeek, double shareManual, double cycle) {
        return new MemberPattern(new UUID(0x3000_0000_0000_0000L, i), 10, hoursPerWeek * 13, hoursPerWeek, 0.0, shareManual, 1 - shareManual, 0.0,
                "Monday", List.of(1.0, 0.0, 0.0, 0.0, 0.0), 1.0, cycle, Map.of(), 0.0, 0.0, 0.5, Map.of(), 2, 8.0, 0);
    }

    @Test
    void fewerThanSixMembersAreOneCluster() {
        List<MemberPattern> table = List.of(pattern(1, 10, 0.2, 3), pattern(2, 40, 0.9, 12), pattern(3, 11, 0.1, 4));
        assertTrue(Clustering.assign(table).values().stream().allMatch(v -> v == 0));
    }

    @Test
    void twoObviousGroupsSeparateAndLabelsAreStable() {
        List<MemberPattern> table = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            table.add(pattern(i, 10 + i * 0.1, 0.1 + i * 0.01, 3 + i * 0.1));
        }
        for (int i = 6; i <= 10; i++) {
            table.add(pattern(i, 40 + i * 0.1, 0.9 - i * 0.01, 12 + i * 0.1));
        }
        Map<UUID, Integer> first = Clustering.assign(table);
        assertEquals(first, Clustering.assign(table), "deterministic");
        assertEquals(0, first.get(table.get(0).memberId()), "labels renumbered by first appearance");
        for (int i = 0; i < 5; i++) {
            assertEquals(first.get(table.get(0).memberId()), first.get(table.get(i).memberId()));
            assertEquals(first.get(table.get(5).memberId()), first.get(table.get(5 + i).memberId()));
        }
        assertTrue(first.get(table.get(0).memberId()) != first.get(table.get(5).memberId()));
    }
}
