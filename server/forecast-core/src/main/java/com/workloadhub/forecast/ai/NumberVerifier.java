package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.ai.NarrativeContract.Adjustment;
import com.workloadhub.forecast.ai.NarrativeContract.LikelyWork;
import com.workloadhub.forecast.ai.NarrativeContract.Member;
import com.workloadhub.forecast.ai.NarrativeContract.Move;
import com.workloadhub.forecast.ai.NarrativeContract.Narrative;
import com.workloadhub.forecast.ai.NarrativeContract.PatternFinding;
import com.workloadhub.forecast.ai.NarrativeContract.TeamRisk;
import com.workloadhub.forecast.data.ExportFiles;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Cross-checks every number Copilot wrote against the facts it was given, with the rules of the Python
 * {@code verify.py}: a number matches when its one-decimal rounding equals a fact rounded to one decimal or to the
 * nearest integer; a member's own text may cite the shared numbers and that member's numbers only; team-level text
 * may cite anything; integers up to {@link #SMALL_INTEGER_ALLOWANCE} without an hours unit are counts, not facts.
 */
final class NumberVerifier {

    static final int SMALL_INTEGER_ALLOWANCE = 20;
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern TIME = Pattern.compile("\\b\\d{1,2}:\\d{2}(?::\\d{2})?\\b");
    private static final Pattern PERCENT = Pattern.compile("-?\\d+(?:[.,]\\d+)?\\s*%");
    /** Thousands-separated tokens first, so "1,200" and "1 200,5" are not read as a decimal comma. */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\d.])-?\\d{1,3}(?:[ ,]\\d{3})+(?:[.,]\\d+)?(?![\\w.]*\\d)|(?<![\\d.])-?\\d+(?:[.,]\\d+)?(?![\\w.]*\\d)");
    private static final Pattern THOUSANDS = Pattern.compile("^(-?)(\\d{1,3}(?:[ ,]\\d{3})+)(?:([.,])(\\d+))?$");
    /** Longest spellings first, and a word boundary so "8 high-priority" stays a count. */
    private static final Pattern HOURS_UNIT = Pattern.compile("\\s*(?:hours|hour|heures|heure|hrs|hr|h)\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> MEMBER_SCOPED_KEYS = Set.of("members", "rebalancing_candidates");

    record NumberToken(double value, boolean hours) {
    }

    record Report(int checked, List<String> unverified, Map<String, List<Double>> fields) {

        boolean ok() {
            return unverified.isEmpty();
        }

        String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("checked", checked);
            m.put("unverified", unverified);
            m.put("fields", fields);
            return ExportFiles.mapper().writeValueAsString(m);
        }
    }

    private NumberVerifier() {
    }

    // ----- numbers ------------------------------------------------------------------------------

    static double round1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_EVEN).doubleValue() + 0.0;
    }

    static double round0(double v) {
        return BigDecimal.valueOf(v).setScale(0, RoundingMode.HALF_EVEN).doubleValue() + 0.0;
    }

    private static void walk(JsonNode node, Set<Double> out) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            double v = node.asDouble();
            out.add(round1(v));
            out.add(round0(v));
        } else if (node.isObject()) {
            node.properties().forEach(e -> walk(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(n -> walk(n, out));
        }
    }

    static Set<Double> factNumbers(JsonNode facts) {
        Set<Double> out = new HashSet<>();
        walk(facts, out);
        return out;
    }

    private static double parseNumber(String token) {
        Matcher m = THOUSANDS.matcher(token);
        if (!m.matches()) {
            return Double.parseDouble(token.replace(',', '.'));
        }
        String digits = m.group(2).replaceAll("[ ,]", "");
        String decimals = m.group(4);
        return Double.parseDouble(m.group(1) + digits + (decimals == null ? "" : "." + decimals));
    }

    /** Every number in the text, each paired with whether it is written as a number of hours. */
    static List<NumberToken> numbersWithUnits(String text) {
        String cleaned = PERCENT.matcher(TIME.matcher(DATE.matcher(text).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
        List<NumberToken> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(cleaned);
        while (m.find()) {
            Matcher unit = HOURS_UNIT.matcher(cleaned);
            unit.region(m.end(), cleaned.length());
            out.add(new NumberToken(parseNumber(m.group()), unit.lookingAt()));
        }
        return out;
    }

    static List<Double> numbersInText(String text) {
        return numbersWithUnits(text).stream().map(NumberToken::value).toList();
    }

    // ----- scopes -------------------------------------------------------------------------------

    /** The numeric facts of the run as a whole: every field may cite these. */
    static Set<Double> sharedNumbers(JsonNode facts) {
        Set<Double> out = new HashSet<>();
        facts.properties().forEach(e -> {
            if (!MEMBER_SCOPED_KEYS.contains(e.getKey())) {
                walk(e.getValue(), out);
            }
        });
        return out;
    }

    /** Each member's own numbers: their entry plus their rebalancing candidacy, keyed by member id. */
    static Map<String, Set<Double>> memberNumbers(JsonNode facts) {
        Map<String, Set<Double>> scopes = new HashMap<>();
        for (JsonNode m : facts.path("members")) {
            if (m.isObject() && m.path("id").isTextual()) {
                walk(m, scopes.computeIfAbsent(m.path("id").asText(), k -> new HashSet<>()));
            }
        }
        JsonNode candidates = facts.path("rebalancing_candidates");
        if (candidates.isObject()) {
            candidates.properties().forEach(group -> {
                for (JsonNode entry : group.getValue()) {
                    if (entry.isObject() && entry.path("member_id").isTextual()) {
                        walk(entry, scopes.computeIfAbsent(entry.path("member_id").asText(), k -> new HashSet<>()));
                    }
                }
            });
        }
        return scopes;
    }

    private record Field(String path, String text, Set<Double> allowed) {
    }

    private static Set<Double> union(Set<Double> a, Set<Double> b) {
        Set<Double> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }

    private static Set<Double> rounded(double... values) {
        Set<Double> out = new HashSet<>();
        for (double v : values) {
            out.add(round1(v));
            out.add(round0(v));
        }
        return out;
    }

    private static List<Field> textFields(Narrative n, JsonNode facts) {
        Set<Double> shared = sharedNumbers(facts);
        Map<String, Set<Double>> perMember = memberNumbers(facts);
        Set<Double> everything = new HashSet<>(shared);
        perMember.values().forEach(everything::addAll);
        java.util.function.Function<String, Set<Double>> memberScope = id -> union(shared, perMember.getOrDefault(id, Set.of()));

        List<Field> fields = new ArrayList<>();
        fields.add(new Field("run_summary", n.runSummary(), everything));
        fields.add(new Field("model_notes", n.modelNotes(), everything));
        for (int i = 0; i < n.members().size(); i++) {
            Member m = n.members().get(i);
            Set<Double> allowed = memberScope.apply(m.memberId());
            String p = "members[" + i + "]";
            fields.add(new Field(p + ".summary", m.summary(), allowed));
            for (int j = 0; j < m.warnings().size(); j++) {
                fields.add(new Field(p + ".warnings[" + j + "]", m.warnings().get(j), allowed));
            }
            for (int j = 0; j < m.patterns().size(); j++) {
                PatternFinding pf = m.patterns().get(j);
                fields.add(new Field(p + ".patterns[" + j + "].statement", pf.statement(), allowed));
                fields.add(new Field(p + ".patterns[" + j + "].evidence", pf.evidence(), allowed));
            }
            for (int j = 0; j < m.likelyWork().size(); j++) {
                LikelyWork lw = m.likelyWork().get(j);
                fields.add(new Field(p + ".likely_work[" + j + "].statement", lw.statement(), allowed));
                fields.add(new Field(p + ".likely_work[" + j + "].evidence", lw.evidence(), allowed));
            }
        }
        for (int i = 0; i < n.teamRisks().size(); i++) {
            TeamRisk r = n.teamRisks().get(i);
            fields.add(new Field("team_risks[" + i + "].detail", r.detail(), everything));
        }
        for (int i = 0; i < n.rebalancing().size(); i++) {
            Move mv = n.rebalancing().get(i);
            Set<Double> allowed = union(union(memberScope.apply(mv.fromMemberId()), memberScope.apply(mv.toMemberId())), rounded(mv.hours()));
            fields.add(new Field("rebalancing[" + i + "].reason", mv.reason(), allowed));
        }
        for (int i = 0; i < n.suggestedAdjustments().size(); i++) {
            Adjustment a = n.suggestedAdjustments().get(i);
            Set<Double> allowed = union(memberScope.apply(a.memberId()), rounded(a.deltaHours(), Math.abs(a.deltaHours())));
            fields.add(new Field("suggested_adjustments[" + i + "].reason", a.reason(), allowed));
        }
        return fields;
    }

    // ----- verification -------------------------------------------------------------------------

    static Report verify(Narrative n, JsonNode facts) {
        Set<Double> known = factNumbers(facts);
        int checked = 0;
        List<String> unverified = new ArrayList<>();
        Map<String, List<Double>> fields = new LinkedHashMap<>();
        for (Field f : textFields(n, facts)) {
            List<NumberToken> found = numbersWithUnits(f.text());
            if (found.isEmpty()) {
                continue;
            }
            fields.put(f.path(), found.stream().map(NumberToken::value).toList());
            for (NumberToken t : found) {
                checked++;
                double v = t.value();
                if (!t.hours() && v == Math.rint(v) && Math.abs(v) <= SMALL_INTEGER_ALLOWANCE) {
                    continue;
                }
                double r = round1(v);
                if (f.allowed().contains(r)) {
                    continue;
                }
                String elsewhere = known.contains(r) ? " (it is a fact of this run, but not of this field)" : "";
                unverified.add(f.path() + ": " + NarrativeContract.fmt(v) + " is not in the facts" + elsewhere);
            }
        }
        return new Report(checked, unverified, fields);
    }
}
