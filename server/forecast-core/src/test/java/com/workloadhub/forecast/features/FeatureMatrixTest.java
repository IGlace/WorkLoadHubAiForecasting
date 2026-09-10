package com.workloadhub.forecast.features;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FeatureMatrixTest {

    static final UUID A = UUID.fromString("30000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("30000000-0000-0000-0000-00000000000b");
    static final LocalDate W = LocalDate.of(2026, 8, 3);

    static FeatureMatrix sample() {
        List<String> cols = List.of("lag1", "member_id", "target_h1");
        List<MemberWeek> keys = List.of(new MemberWeek(A, W), new MemberWeek(A, W.plusWeeks(1)), new MemberWeek(B, W));
        double[][] values = {{1.0, 0, 2.0}, {2.0, 0, Double.NaN}, {Double.NaN, 1, 4.0}};
        return FeatureMatrix.of(cols, keys, values, Map.of("member_id", List.of(A.toString(), B.toString())));
    }

    @Test
    void columnsRowsAndTargetsAreAddressable() {
        FeatureMatrix m = sample();
        assertEquals(3, m.rowCount());
        assertEquals(1, m.columnIndex("member_id"));
        assertArrayEquals(new double[] {2.0, Double.NaN, 4.0}, m.target(1));
        assertEquals(2.0, m.get(1, "lag1"));
        assertEquals(B.toString(), m.decode("member_id", 1));
        assertThrows(IllegalArgumentException.class, () -> m.columnIndex("nope"));
    }

    @Test
    void filterAndKnownRowsKeepKeysAlignedWithValues() {
        FeatureMatrix m = sample();
        FeatureMatrix onlyA = m.filter(k -> k.member().equals(A));
        assertEquals(2, onlyA.rowCount());
        assertEquals(new MemberWeek(A, W.plusWeeks(1)), onlyA.key(1));
        FeatureMatrix known = m.rowsWithKnown("target_h1");
        assertEquals(List.of(new MemberWeek(A, W), new MemberWeek(B, W)), known.keys());
        assertEquals(4.0, known.get(1, "target_h1"));
    }

    @Test
    void flattenIsRowMajorWithNaNForMissingAndNonEmptyColumnsDropAllNaN() {
        FeatureMatrix m = sample();
        float[] flat = m.flatten(List.of("lag1", "member_id"));
        assertEquals(6, flat.length);
        assertEquals(1.0f, flat[0]);
        assertTrue(Float.isNaN(flat[4]));
        FeatureMatrix all = FeatureMatrix.of(List.of("x", "y"), List.of(new MemberWeek(A, W)), new double[][] {{Double.NaN, 1}}, Map.of());
        assertEquals(List.of("y"), all.nonEmptyColumns(List.of("x", "y")));
    }

    @Test
    void valuesAreCopiedSoTheCallerCannotMutateTheMatrix() {
        double[][] values = {{1.0, 0, 2.0}};
        FeatureMatrix m = FeatureMatrix.of(List.of("lag1", "member_id", "target_h1"), List.of(new MemberWeek(A, W)), values, Map.of());
        values[0][0] = 99;
        assertEquals(1.0, m.get(0, "lag1"));
        assertTrue(new MemberWeek(A, W).compareTo(new MemberWeek(B, W)) < 0);
        assertTrue(new MemberWeek(A, W).compareTo(new MemberWeek(A, W.plusWeeks(1))) < 0);
    }
}
