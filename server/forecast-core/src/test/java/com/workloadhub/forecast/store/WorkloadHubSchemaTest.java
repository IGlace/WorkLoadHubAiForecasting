package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorkloadHubSchemaTest {

    static LinkedHashMap<String, Object> row(String id, String parentId) {
        LinkedHashMap<String, Object> r = new LinkedHashMap<>();
        r.put("id", id);
        r.put("manager_id", parentId);
        return r;
    }

    static int indexOfId(List<LinkedHashMap<String, Object>> rows, String id) {
        for (int i = 0; i < rows.size(); i++) {
            if (id.equals(rows.get(i).get("id"))) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void aChildBeforeItsParentGetsMovedAfterIt() {
        // "child" (index 0) references "parent" (index 1): out of order for insertion.
        List<LinkedHashMap<String, Object>> rows = List.of(row("child", "parent"), row("parent", null));
        List<LinkedHashMap<String, Object>> out = WorkloadHubSchema.parentsFirst("users", rows);
        assertEquals(2, out.size());
        assertTrue(indexOfId(out, "parent") < indexOfId(out, "child"), "parent must precede child: " + out);
    }

    @Test
    void unrelatedRowsKeepTheirOriginalRelativeOrder() {
        // "a" and "b" have no self-reference to one another (or to "c"); their relative order must survive.
        List<LinkedHashMap<String, Object>> rows = List.of(row("a", null), row("b", null), row("c", "a"));
        List<LinkedHashMap<String, Object>> out = WorkloadHubSchema.parentsFirst("users", rows);
        assertTrue(indexOfId(out, "a") < indexOfId(out, "b"), "a before b: " + out);
        assertTrue(indexOfId(out, "b") < indexOfId(out, "c"), "b before c since c only depends on a: " + out);
        assertTrue(indexOfId(out, "a") < indexOfId(out, "c"), "a before c: " + out);
    }

    @Test
    void aMissingParentDoesNotStopTheRowFromBeingPlaced() {
        // "orphan" references an id that is not in the batch at all (e.g. it is in an earlier statement).
        List<LinkedHashMap<String, Object>> rows = List.of(row("orphan", "not-in-this-list"), row("other", null));
        List<LinkedHashMap<String, Object>> out = WorkloadHubSchema.parentsFirst("users", rows);
        assertEquals(2, out.size());
        assertEquals(List.of("orphan", "other"), out.stream().map(r -> (String) r.get("id")).toList());
    }

    @Test
    void aCycleTerminatesInsteadOfLoopingForever() {
        // "a" references "b" and "b" references "a": no valid topological order exists, but the call
        // must still return promptly with every row placed exactly once.
        List<LinkedHashMap<String, Object>> rows = List.of(row("a", "b"), row("b", "a"));
        List<LinkedHashMap<String, Object>> out = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> WorkloadHubSchema.parentsFirst("users", rows));
        assertEquals(2, out.size());
        assertEquals(java.util.Set.of("a", "b"), out.stream().map(r -> (String) r.get("id")).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void aTableWithoutASelfReferenceIsReturnedUnchanged() {
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>(List.of(row("x", "y")));
        assertSame(rows, WorkloadHubSchema.parentsFirst("holidays", rows));
    }

    @Test
    void fewerThanTwoRowsAreReturnedUnchanged() {
        List<LinkedHashMap<String, Object>> rows = List.of(row("only", "missing"));
        assertSame(rows, WorkloadHubSchema.parentsFirst("users", rows));
    }

    @Test
    void duplicateIdsThrow() {
        // both rows carry id "dup": only the first can ever be placed, so the output is one row short.
        List<LinkedHashMap<String, Object>> rows = List.of(row("dup", null), row("dup", null));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> WorkloadHubSchema.parentsFirst("users", rows));
        assertTrue(e.getMessage().contains("users"), e.getMessage());
        assertTrue(e.getMessage().contains("1"), e.getMessage());
    }

    @Test
    void rowsWithoutAnIdKeyThrow() {
        // neither row carries an "id" key at all: both resolve to the same null id, same as a duplicate.
        LinkedHashMap<String, Object> a = new LinkedHashMap<>();
        a.put("manager_id", null);
        LinkedHashMap<String, Object> b = new LinkedHashMap<>();
        b.put("manager_id", null);
        List<LinkedHashMap<String, Object>> rows = List.of(a, b);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> WorkloadHubSchema.parentsFirst("users", rows));
        assertTrue(e.getMessage().contains("users"), e.getMessage());
    }
}
