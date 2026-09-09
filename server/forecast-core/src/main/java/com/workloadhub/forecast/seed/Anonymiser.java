package com.workloadhub.forecast.seed;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Replaces identities in user rows; ids, departments, titles and managers stay so the structure survives. */
public final class Anonymiser {

    private Anonymiser() {
    }

    public static List<LinkedHashMap<String, Object>> anonymise(List<LinkedHashMap<String, Object>> users, SeedRandom rnd) {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        int i = 0;
        for (LinkedHashMap<String, Object> u : users) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(u);
            String name = ReferenceData.name(i, rnd);
            String username = "user" + (i + 1);
            row.put("full_name", name);
            row.put("username", username);
            row.put("email", username + "@example.test");
            row.put("account_name", username);
            row.put("password", null);
            row.put("object_id", null);
            row.put("manager_object_id", null);
            out.add(row);
            i++;
        }
        return out;
    }

    /** Keeps n users, department by department, and drops manager links that point outside the kept set. */
    public static List<LinkedHashMap<String, Object>> shrink(List<LinkedHashMap<String, Object>> users, int n) {
        if (n <= 0 || n >= users.size()) {
            return users;
        }
        List<LinkedHashMap<String, Object>> sorted = new ArrayList<>(users);
        sorted.sort(Comparator.comparing((LinkedHashMap<String, Object> u) -> String.valueOf(u.get("department")))
                .thenComparing(u -> u.get("manager_id") == null ? 0 : 1)
                .thenComparing(u -> String.valueOf(u.get("id"))));
        List<LinkedHashMap<String, Object>> kept = new ArrayList<>(sorted.subList(0, n));
        Set<Object> ids = new HashSet<>();
        for (LinkedHashMap<String, Object> u : kept) {
            ids.add(u.get("id"));
        }
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (LinkedHashMap<String, Object> u : kept) {
            LinkedHashMap<String, Object> row = new LinkedHashMap<>(u);
            if (row.get("manager_id") != null && !ids.contains(row.get("manager_id"))) {
                row.put("manager_id", null);
            }
            out.add(row);
        }
        return out;
    }
}
