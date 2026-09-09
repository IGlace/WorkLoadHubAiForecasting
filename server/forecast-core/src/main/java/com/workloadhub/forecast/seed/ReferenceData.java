package com.workloadhub.forecast.seed;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

/** Reference rows for synthetic mode, shaped like the export's, with stable ids. */
public final class ReferenceData {

    private static final String STAMP = "2025-09-01T08:00:00";

    private record Status(String name, String category, int order, String description) {
    }

    private record Dept(String code, String label, WorkFamily family) {
    }

    private static final List<Status> STATUSES = List.of(
            new Status("Open", "TO_DO", 1, "New task, not started"), new Status("To Do", "TO_DO", 2, "Ready to start"),
            new Status("In Progress", "IN_PROGRESS", 3, "Being worked on"), new Status("In Review", "IN_PROGRESS", 4, "Under review"),
            new Status("Testing", "IN_PROGRESS", 5, "Being tested"), new Status("Blocked", "IN_PROGRESS", 6, "Waiting on something"),
            new Status("On Hold", "TO_DO", 7, "Paused"), new Status("Done", "DONE", 8, "Finished"), new Status("Closed", "DONE", 9, "Closed"));

    private static final List<String> TYPES = List.of("Story", "Bug", "Task", "Epic", "Improvement", "New Feature",
            "Change Request", "Incident", "Risk", "Spike", "Test", "Sub-task");

    private static final List<String> ROLES = List.of("ADMIN", "CENTER_MANAGER", "SKILL_TEAM_LEADER", "TEAM_LEADER", "MEMBER", "VIEWER");

    /** Moroccan national holidays (the export's country code is MA). */
    private static final List<String[]> NATIONAL = List.of(
            new String[] {"New Year's Day", "01-01"}, new String[] {"Independence Manifesto Day", "01-11"},
            new String[] {"Labour Day", "05-01"}, new String[] {"Throne Day", "07-30"},
            new String[] {"Oued Ed-Dahab Day", "08-14"}, new String[] {"Revolution Day", "08-20"},
            new String[] {"Youth Day", "08-21"}, new String[] {"Green March Day", "11-06"},
            new String[] {"Independence Day", "11-18"});

    private static final List<Dept> DEPTS = List.of(
            new Dept("CT1", "PTE / CT1 Calibration & Testing 1", WorkFamily.CALIBRATION),
            new Dept("CT2", "PTE / CT2 Calibration & Testing 2", WorkFamily.CALIBRATION),
            new Dept("SD1", "PTE / SD1 Safety & Diagnostics 1", WorkFamily.SYSTEMS),
            new Dept("EE", "PTE / EE Electric & Electronics", WorkFamily.ELECTRONICS),
            new Dept("DAI", "PTE / DAI Data & Artificial Intelligence", WorkFamily.DATA),
            new Dept("SMBD", "PTE / SMBD Software Model Based Design", WorkFamily.ELECTRONICS),
            new Dept("VAH", "PTE / VAH Vehicle Attributes & Homologation", WorkFamily.VALIDATION),
            new Dept("MDS", "PTE / MDS Modeling, Design & Simulation", WorkFamily.DESIGN),
            new Dept("HR", "ZEN / HR & WKP DEP HR & Workplace Services Department", WorkFamily.SUPPORT));

    private static final List<String> TITLES_BY_FAMILY_CAL = List.of("Calibration Engineer", "Calibration Lead Engineer", "Calibration Quality & Dataset Manager");
    private static final List<String> FIRST = List.of("Amina", "Youssef", "Sara", "Omar", "Leila", "Karim", "Nadia", "Hamza", "Imane", "Rachid",
            "Salma", "Anas", "Hind", "Mehdi", "Kenza", "Ayoub", "Zineb", "Tarik", "Meryem", "Bilal");
    private static final List<String> LAST = List.of("Benali", "El Amrani", "Idrissi", "Bouzid", "Chraibi", "Haddad", "Kabbaj", "Lahlou",
            "Mansouri", "Naciri", "Ouazzani", "Rami", "Saadi", "Tazi", "Ziani", "Berrada", "Fassi", "Guessous", "Hajji", "Jabri");

    private ReferenceData() {
    }

    static UUID stableId(String key) {
        return UUID.nameUUIDFromBytes(("whf-seed:" + key).getBytes(StandardCharsets.UTF_8));
    }

    public static List<LinkedHashMap<String, Object>> statusRows() {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (Status s : STATUSES) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("id", stableId("status:" + s.name()).toString());
            r.put("name", s.name());
            r.put("active", true);
            r.put("category", s.category());
            r.put("created_at", STAMP);
            r.put("sort_order", (long) s.order());
            r.put("updated_at", STAMP);
            r.put("description", s.description());
            out.add(r);
        }
        return out;
    }

    public static List<LinkedHashMap<String, Object>> typeRows() {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (String t : TYPES) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("id", stableId("type:" + t).toString());
            r.put("icon", null);
            r.put("name", t);
            r.put("active", true);
            r.put("created_at", STAMP);
            r.put("updated_at", STAMP);
            r.put("description", t);
            r.put("subtask_type_id", null);
            out.add(r);
        }
        return out;
    }

    public static List<LinkedHashMap<String, Object>> roleRows() {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (String code : ROLES) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("id", stableId("role:" + code).toString());
            r.put("code", code);
            r.put("label", code.charAt(0) + code.substring(1).toLowerCase().replace('_', ' '));
            r.put("active", true);
            r.put("created_at", STAMP);
            r.put("updated_at", STAMP);
            out.add(r);
        }
        return out;
    }

    public static List<LinkedHashMap<String, Object>> holidayRows(int fromYear, int toYear) {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (int year = fromYear; year <= toYear; year++) {
            for (String[] h : NATIONAL) {
                String date = year + "-" + h[1];
                LinkedHashMap<String, Object> r = new LinkedHashMap<>();
                r.put("id", stableId("holiday:" + h[0] + ":" + date).toString());
                r.put("type", "NATIONAL");
                r.put("title", h[0]);
                r.put("active", true);
                r.put("status", "CONFIRMED");
                r.put("end_date", date);
                r.put("created_at", STAMP);
                r.put("start_date", date);
                r.put("updated_at", STAMP);
                r.put("country_code", "MA");
                out.add(r);
            }
        }
        return out;
    }

    public static List<LinkedHashMap<String, Object>> jobTitleRows(Collection<String> titles) {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        for (String t : new TreeSet<>(titles)) {
            LinkedHashMap<String, Object> r = new LinkedHashMap<>();
            r.put("id", stableId("title:" + t).toString());
            r.put("value", t);
            r.put("created_at", STAMP);
            r.put("updated_at", STAMP);
            out.add(r);
        }
        return out;
    }

    static String titleFor(WorkFamily family, SeedRandom rnd) {
        return switch (family) {
            case CALIBRATION -> rnd.pick(TITLES_BY_FAMILY_CAL);
            case SYSTEMS -> rnd.pick(List.of("System Development Engineer", "Lead Engineer System engineering"));
            case ELECTRONICS -> rnd.pick(List.of("Development Eng. Electric/ Electronics", "Software & Functions Engineer", "Development Engineer SW"));
            case DATA -> rnd.pick(List.of("Data Analyst & SW Developer", "AI Engineer"));
            case VALIDATION -> rnd.pick(List.of("Verification & Validation Engineer", "Attributes & Homologation Engineer", "Vehicule Fleet Validation Engineer"));
            case DESIGN -> rnd.pick(List.of("Design Engineer", "Simulation Engineer", "Design Engineer DMU"));
            case SUPPORT -> rnd.pick(List.of("HR Specialist", "IT System Administrator", "Purchasing & Admin Officer"));
            default -> "Project Manager PTE";
        };
    }

    /** n users over the departments: one head per department, one manager per ten people, the rest members. */
    public static List<LinkedHashMap<String, Object>> syntheticUsers(int n, SeedRandom rnd) {
        List<LinkedHashMap<String, Object>> out = new ArrayList<>();
        int perDept = Math.max(3, n / DEPTS.size());
        int made = 0;
        int deptIndex = 0;
        while (made < n) {
            Dept d = DEPTS.get(deptIndex % DEPTS.size());
            deptIndex++;
            int size = Math.min(perDept, n - made);
            UUID head = stableId("user:" + made);
            out.add(user(head, name(made, rnd), "Skill Team Leader", d.label(), null, made == 0 ? "CENTER_MANAGER" : "MEMBER"));
            made++;
            UUID manager = null;
            for (int i = 1; i < size; i++) {
                UUID id = stableId("user:" + made);
                boolean isManager = (i - 1) % 10 == 0;
                if (isManager) {
                    manager = id;
                    out.add(user(id, name(made, rnd), "Team Leader " + d.code(), d.label(), head, "MEMBER"));
                } else {
                    out.add(user(id, name(made, rnd), titleFor(d.family(), rnd), d.label(), manager, "MEMBER"));
                }
                made++;
            }
        }
        if (out.size() > 1) {
            out.get(1).put("role", "ADMIN");
        }
        return out;
    }

    static String name(int i, SeedRandom rnd) {
        return FIRST.get(rnd.between(0, FIRST.size() - 1)) + " " + LAST.get(rnd.between(0, LAST.size() - 1)) + " " + (i + 1);
    }

    static LinkedHashMap<String, Object> user(UUID id, String fullName, String title, String department, UUID manager, String role) {
        String username = fullName.toLowerCase().replace(' ', '.');
        LinkedHashMap<String, Object> u = new LinkedHashMap<>();
        u.put("id", id.toString());
        u.put("role", role);
        u.put("email", username + "@example.test");
        u.put("active", true);
        u.put("version", 0L);
        u.put("password", null);
        u.put("username", username);
        u.put("full_name", fullName);
        u.put("job_title", title);
        u.put("object_id", null);
        u.put("created_at", STAMP);
        u.put("department", department);
        u.put("manager_id", manager == null ? null : manager.toString());
        u.put("updated_at", STAMP);
        u.put("account_name", username);
        u.put("deactivated_at", null);
        u.put("manager_object_id", null);
        return u;
    }
}
