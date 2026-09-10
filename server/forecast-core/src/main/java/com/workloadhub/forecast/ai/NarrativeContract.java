package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.data.ExportFiles;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/** The JSON contract Copilot must return, a hand-written validator over the tree, and the checks against the facts. */
final class NarrativeContract {

    static final Set<String> LEVELS = Set.of("low", "medium", "high");
    static final Set<String> KINDS = Set.of("assignment_style", "weekday_rhythm", "trend", "estimate_bias", "cycle_time", "lateness", "project_phase",
            "cluster", "other");
    static final int MAX_LIKELY_WORK = 4;
    static final double TOLERANCE = 0.05;

    /** Allowed keys per object, the source of truth the schema text is tested against. */
    static final Map<String, Set<String>> FIELDS = Map.of(
            "narrative", new TreeSet<>(Set.of("run_summary", "members", "team_risks", "rebalancing", "suggested_adjustments", "model_notes")),
            "member", new TreeSet<>(Set.of("member_id", "name", "risk_level", "summary", "patterns", "warnings", "likely_work")),
            "pattern", new TreeSet<>(Set.of("kind", "statement", "evidence")),
            "likely_work", new TreeSet<>(Set.of("statement", "evidence", "confidence")),
            "team_risk", new TreeSet<>(Set.of("title", "detail", "severity", "member_ids")),
            "move", new TreeSet<>(Set.of("from_member_id", "to_member_id", "window", "hours", "reason", "confidence", "task_keys")),
            "adjustment", new TreeSet<>(Set.of("member_id", "window", "delta_hours", "reason")));

    private static final Pattern FENCE = Pattern.compile("^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$", Pattern.DOTALL);

    record Narrative(String runSummary, List<Member> members, List<TeamRisk> teamRisks, List<Move> rebalancing, List<Adjustment> suggestedAdjustments,
            String modelNotes, JsonNode tree) {

        String toJson() {
            return ExportFiles.mapper().writeValueAsString(tree);
        }
    }

    record Member(String memberId, String name, String riskLevel, String summary, List<PatternFinding> patterns, List<String> warnings,
            List<LikelyWork> likelyWork) {
    }

    record PatternFinding(String kind, String statement, String evidence) {
    }

    record LikelyWork(String statement, String evidence, String confidence) {
    }

    record TeamRisk(String title, String detail, String severity, List<String> memberIds) {
    }

    record Move(String fromMemberId, String toMemberId, LocalDate window, double hours, String reason, String confidence, List<String> taskKeys) {
    }

    record Adjustment(String memberId, LocalDate window, double deltaHours, String reason) {
    }

    static final class ContractException extends RuntimeException {
        private final List<String> problems;

        ContractException(List<String> problems) {
            super(String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        List<String> problems() {
            return problems;
        }
    }

    private NarrativeContract() {
    }

    // ----- parsing --------------------------------------------------------------------------

    static Narrative parse(String text) {
        String body = text == null ? "" : text.strip();
        Matcher m = FENCE.matcher(body);
        if (m.matches()) {
            body = m.group(1);
        }
        int start = body.indexOf('{');
        int end = body.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            throw new ContractException(List.of("the answer is not valid JSON: no JSON object found"));
        }
        JsonNode tree;
        try {
            tree = ExportFiles.mapper().readTree(body.substring(start, end + 1));
        } catch (RuntimeException e) {
            throw new ContractException(List.of("the answer is not valid JSON: " + firstLine(e.getMessage())));
        }
        List<String> problems = new ArrayList<>();
        Narrative n = read(tree, problems);
        if (!problems.isEmpty()) {
            throw new ContractException(problems);
        }
        return n;
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }

    private static Narrative read(JsonNode tree, List<String> problems) {
        Walker w = new Walker(problems);
        w.object(tree, "", FIELDS.get("narrative"));
        String runSummary = w.text(tree, "", "run_summary", 1, 2000, true);
        List<Member> members = new ArrayList<>();
        int i = 0;
        for (JsonNode mn : w.array(tree, "", "members", true)) {
            String p = "members[" + i++ + "]";
            w.object(mn, p, FIELDS.get("member"));
            List<PatternFinding> patterns = new ArrayList<>();
            int j = 0;
            for (JsonNode pn : w.array(mn, p, "patterns", false)) {
                String pp = p + ".patterns[" + j++ + "]";
                w.object(pn, pp, FIELDS.get("pattern"));
                patterns.add(new PatternFinding(w.oneOf(pn, pp, "kind", KINDS), w.text(pn, pp, "statement", 1, 400, true), w.text(pn, pp, "evidence", 1, 400, true)));
            }
            List<String> warnings = new ArrayList<>();
            int k = 0;
            for (JsonNode wn : w.array(mn, p, "warnings", false)) {
                String wp = p + ".warnings[" + k++ + "]";
                if (!wn.isTextual()) {
                    problems.add(wp + ": must be a string");
                } else {
                    warnings.add(wn.asText());
                }
            }
            List<LikelyWork> likely = new ArrayList<>();
            int l = 0;
            List<JsonNode> likelyNodes = w.array(mn, p, "likely_work", false);
            if (likelyNodes.size() > MAX_LIKELY_WORK) {
                problems.add(p + ".likely_work: at most " + MAX_LIKELY_WORK + " items");
            }
            for (JsonNode ln : likelyNodes) {
                String lp = p + ".likely_work[" + l++ + "]";
                w.object(ln, lp, FIELDS.get("likely_work"));
                likely.add(new LikelyWork(w.text(ln, lp, "statement", 1, 300, true), w.text(ln, lp, "evidence", 1, 300, true), w.oneOf(ln, lp, "confidence", LEVELS)));
            }
            members.add(new Member(w.text(mn, p, "member_id", 1, 200, true), w.text(mn, p, "name", 0, 400, true), w.oneOf(mn, p, "risk_level", LEVELS),
                    w.text(mn, p, "summary", 1, 1200, true), patterns, warnings, likely));
        }
        List<TeamRisk> risks = new ArrayList<>();
        i = 0;
        for (JsonNode rn : w.array(tree, "", "team_risks", false)) {
            String p = "team_risks[" + i++ + "]";
            w.object(rn, p, FIELDS.get("team_risk"));
            risks.add(new TeamRisk(w.text(rn, p, "title", 1, 120, true), w.text(rn, p, "detail", 1, 800, true), w.oneOf(rn, p, "severity", LEVELS),
                    w.strings(rn, p, "member_ids")));
        }
        List<Move> moves = new ArrayList<>();
        i = 0;
        for (JsonNode vn : w.array(tree, "", "rebalancing", false)) {
            String p = "rebalancing[" + i++ + "]";
            w.object(vn, p, FIELDS.get("move"));
            double hours = w.number(vn, p, "hours");
            if (!Double.isNaN(hours) && hours <= 0) {
                problems.add(p + ".hours: must be greater than 0");
            }
            moves.add(new Move(w.text(vn, p, "from_member_id", 1, 200, true), w.text(vn, p, "to_member_id", 1, 200, true), w.date(vn, p, "window"), hours,
                    w.text(vn, p, "reason", 1, 600, true), w.oneOf(vn, p, "confidence", LEVELS), w.strings(vn, p, "task_keys")));
        }
        List<Adjustment> adjustments = new ArrayList<>();
        i = 0;
        for (JsonNode an : w.array(tree, "", "suggested_adjustments", false)) {
            String p = "suggested_adjustments[" + i++ + "]";
            w.object(an, p, FIELDS.get("adjustment"));
            adjustments.add(new Adjustment(w.text(an, p, "member_id", 1, 200, true), w.date(an, p, "window"), w.number(an, p, "delta_hours"),
                    w.text(an, p, "reason", 1, 600, true)));
        }
        String notes = tree.has("model_notes") ? w.text(tree, "", "model_notes", 0, 1000, true) : "";
        return new Narrative(runSummary, members, risks, moves, adjustments, notes, tree);
    }

    /** Reads one field at a time, recording every problem with its path instead of stopping at the first. */
    private static final class Walker {
        private final List<String> problems;

        Walker(List<String> problems) {
            this.problems = problems;
        }

        private static String at(String path, String key) {
            return path.isEmpty() ? key : path + "." + key;
        }

        void object(JsonNode node, String path, Set<String> allowed) {
            if (node == null || !node.isObject()) {
                problems.add((path.isEmpty() ? "document" : path) + ": must be an object");
                return;
            }
            node.properties().forEach(e -> {
                if (!allowed.contains(e.getKey())) {
                    problems.add(at(path, e.getKey()) + ": unknown field");
                }
            });
        }

        String text(JsonNode node, String path, String key, int min, int max, boolean required) {
            JsonNode v = node == null || !node.isObject() ? null : node.get(key);
            if (v == null || v.isNull()) {
                if (required) {
                    problems.add(at(path, key) + ": field required");
                }
                return "";
            }
            if (!v.isTextual()) {
                problems.add(at(path, key) + ": must be a string");
                return "";
            }
            String s = v.asText();
            if (s.length() < min) {
                problems.add(at(path, key) + ": must have at least " + min + " character" + (min == 1 ? "" : "s"));
            }
            if (s.length() > max) {
                problems.add(at(path, key) + ": must have at most " + max + " characters");
            }
            return s;
        }

        String oneOf(JsonNode node, String path, String key, Set<String> values) {
            String s = text(node, path, key, 1, 100, true);
            if (!s.isEmpty() && !values.contains(s)) {
                problems.add(at(path, key) + ": must be one of " + String.join(", ", new TreeSet<>(values)));
            }
            return s;
        }

        double number(JsonNode node, String path, String key) {
            JsonNode v = node == null || !node.isObject() ? null : node.get(key);
            if (v == null || v.isNull()) {
                problems.add(at(path, key) + ": field required");
                return Double.NaN;
            }
            if (!v.isNumber()) {
                problems.add(at(path, key) + ": must be a number");
                return Double.NaN;
            }
            return v.asDouble();
        }

        LocalDate date(JsonNode node, String path, String key) {
            String s = text(node, path, key, 1, 40, true);
            if (s.isEmpty()) {
                return null;
            }
            try {
                return LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
            } catch (DateTimeParseException e) {
                problems.add(at(path, key) + ": must be an ISO date (YYYY-MM-DD)");
                return null;
            }
        }

        List<JsonNode> array(JsonNode node, String path, String key, boolean required) {
            JsonNode v = node == null || !node.isObject() ? null : node.get(key);
            if (v == null || v.isNull()) {
                if (required) {
                    problems.add(at(path, key) + ": field required");
                }
                return List.of();
            }
            if (!v.isArray()) {
                problems.add(at(path, key) + ": must be an array");
                return List.of();
            }
            List<JsonNode> out = new ArrayList<>();
            v.forEach(out::add);
            return out;
        }

        List<String> strings(JsonNode node, String path, String key) {
            List<String> out = new ArrayList<>();
            int i = 0;
            for (JsonNode s : array(node, path, key, false)) {
                if (!s.isTextual()) {
                    problems.add(at(path, key) + "[" + i + "]: must be a string");
                } else {
                    out.add(s.asText());
                }
                i++;
            }
            return out;
        }
    }

    // ----- cross-checks with the facts ----------------------------------------------------------

    static List<String> validateAgainstFacts(Narrative n, JsonNode facts) {
        List<String> problems = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>();
        Map<String, JsonNode> memberNodes = new java.util.HashMap<>();
        for (JsonNode m : facts.path("members")) {
            known.add(m.path("id").asText());
            memberNodes.put(m.path("id").asText(), m);
        }
        Set<LocalDate> windows = new HashSet<>();
        for (JsonNode w : facts.path("run").path("windows")) {
            windows.add(LocalDate.parse(w.path("start").asText().substring(0, 10)));
        }
        List<String> seen = new ArrayList<>();
        for (Member m : n.members()) {
            if (!known.contains(m.memberId())) {
                problems.add("member_id " + m.memberId() + " is not a member of this team");
            }
            seen.add(m.memberId());
        }
        for (String id : known) {
            if (!seen.contains(id)) {
                problems.add("member " + id + " is missing from members");
            }
        }
        for (String id : new LinkedHashSet<>(seen)) {
            if (seen.stream().filter(id::equals).count() > 1) {
                problems.add("member " + id + " appears more than once");
            }
        }
        for (TeamRisk r : n.teamRisks()) {
            for (String id : r.memberIds()) {
                if (!known.contains(id)) {
                    problems.add("team risk '" + r.title() + "' names unknown member " + id);
                }
            }
        }
        for (Move mv : n.rebalancing()) {
            boolean valid = true;
            if (mv.fromMemberId().equals(mv.toMemberId())) {
                problems.add("a rebalancing move names the same member as source and target");
                valid = false;
            }
            for (String id : List.of(mv.fromMemberId(), mv.toMemberId())) {
                if (!known.contains(id)) {
                    problems.add("rebalancing move names unknown member " + id);
                    valid = false;
                }
            }
            if (mv.window() == null || !windows.contains(mv.window())) {
                problems.add("rebalancing window " + mv.window() + " is not a forecast window");
                valid = false;
            }
            if (!valid) {
                continue;
            }
            Set<String> openKeys = new HashSet<>();
            for (JsonNode t : memberNodes.get(mv.fromMemberId()).path("open_tasks")) {
                openKeys.add(t.path("key").asText());
            }
            for (String key : mv.taskKeys()) {
                if (!openKeys.contains(key)) {
                    problems.add("rebalancing move names task " + key + ", which is not an open task of member " + mv.fromMemberId());
                }
            }
            JsonNode source = forecastRow(memberNodes.get(mv.fromMemberId()), mv.window());
            JsonNode target = forecastRow(memberNodes.get(mv.toMemberId()), mv.window());
            if (source == null || target == null) {
                continue;
            }
            double overload = source.path("overload").asDouble(0);
            if (mv.hours() > overload + TOLERANCE) {
                problems.add("rebalancing move of " + fmt(mv.hours()) + " h from member " + mv.fromMemberId() + " in the window starting " + mv.window()
                        + " exceeds their overload of " + fmt(overload) + " h; the source has no such overload to move");
            }
            double demand = target.path("demand").asDouble(0);
            double capacity = target.path("capacity").asDouble(0);
            if (demand + mv.hours() > capacity + TOLERANCE) {
                problems.add("rebalancing move of " + fmt(mv.hours()) + " h to member " + mv.toMemberId() + " in the window starting " + mv.window()
                        + " would bring their demand to " + fmt(demand + mv.hours()) + " h, above their capacity of " + fmt(capacity) + " h");
            }
        }
        for (Adjustment a : n.suggestedAdjustments()) {
            if (!known.contains(a.memberId())) {
                problems.add("adjustment names unknown member " + a.memberId());
            }
            if (a.window() == null || !windows.contains(a.window())) {
                problems.add("adjustment window " + a.window() + " is not a forecast window");
            }
            if (a.deltaHours() == 0 || !Double.isFinite(a.deltaHours())) {
                problems.add("suggested adjustment for member " + a.memberId() + " in the window starting " + a.window() + " has a delta_hours of " + a.deltaHours()
                        + ", which is not a usable value");
            }
        }
        return problems;
    }

    private static JsonNode forecastRow(JsonNode member, LocalDate window) {
        if (member == null || window == null) {
            return null;
        }
        for (JsonNode row : member.path("forecast")) {
            String w = row.path("start").asText();
            if (w.length() >= 10 && w.substring(0, 10).equals(window.toString())) {
                return row;
            }
        }
        return null;
    }

    /** Python's {@code %g}-like rendering: no trailing zeros, at most two decimals. */
    static String fmt(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        return s.contains(".") ? s.replaceAll("0+$", "").replaceAll("\\.$", "") : s;
    }
}
