package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.ai.NarrativeContract.Adjustment;
import com.workloadhub.forecast.ai.NarrativeContract.LikelyWork;
import com.workloadhub.forecast.ai.NarrativeContract.Member;
import com.workloadhub.forecast.ai.NarrativeContract.Move;
import com.workloadhub.forecast.ai.NarrativeContract.Narrative;
import com.workloadhub.forecast.ai.NarrativeContract.PatternFinding;
import com.workloadhub.forecast.ai.NarrativeContract.TeamRisk;
import com.workloadhub.forecast.Json;
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
    /** A task or team key such as EE2-59 or WEB-3: masked out before numbers are read, so its own digits never
     * pass as a fact. The trailing {@code (?![.,]\d)} refuses to match when the key is immediately followed by
     * a decimal continuation -- e.g. "MASE-0" inside "MASE-0.821" -- so it cannot swallow the integer part of a
     * real figure and leave the fractional part (".821") behind, which the {@link #NUMBER} pattern's own digit
     * lookbehind then refuses to open. */
    private static final Pattern TASK_KEY = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+\\b(?![.,]\\d)");
    /** Thousands-separated tokens first, so "1,200" and "1 200,5" are not read as a decimal comma. The sign is
     * only accepted when not preceded by a letter, digit or dot: a hyphenated code or label (EE2-59,
     * week-30) must not read as a false minus sign, but a genuine "bias -1.228" still does. */
    private static final Pattern NUMBER = Pattern.compile(
            "(?:(?<![\\w.])-)?(?<![\\d.])\\d{1,3}(?:[ ,]\\d{3})+(?:[.,]\\d+)?(?![\\w.]*\\d)"
                    + "|(?:(?<![\\w.])-)?(?<![\\d.])\\d+(?:[.,]\\d+)?(?![\\w.]*\\d)");
    private static final Pattern THOUSANDS = Pattern.compile("^(-?)(\\d{1,3}(?:[ ,]\\d{3})+)(?:([.,])(\\d+))?$");
    /** Longest spellings first, and a word boundary so "8 high-priority" stays a count. */
    private static final Pattern HOURS_UNIT = Pattern.compile("\\s*(?:hours|hour|heures|heure|hrs|hr|h)\\b", Pattern.CASE_INSENSITIVE);
    private static final Set<String> MEMBER_SCOPED_KEYS = Set.of("members", "rebalancing_candidates");

    record NumberToken(double value, boolean hours, int decimals) {
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
            return Json.mapper().writeValueAsString(m);
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

    static double roundHalfUpAt1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).doubleValue() + 0.0;
    }

    /** The roundings a raw fact value is checked against: round1 (HALF_EVEN), its HALF_UP tie reading when
     * that differs, round0, and the raw value itself. Shared by {@link #walk} (JSON-node facts) and
     * {@link #raw} (varargs), so the four-line block is written once. */
    private static void addRoundings(double v, Set<Double> out) {
        out.add(round1(v));
        double up = roundHalfUpAt1(v);
        if (up != round1(v)) {
            out.add(up);
        }
        out.add(round0(v));
        out.add(v);
    }

    private static void walk(JsonNode node, Set<Double> out) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            addRoundings(node.asDouble(), out);
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

    /**
     * The count of digits after the decimal point, reusing {@link #THOUSANDS}'s own grouping so a
     * thousands-separated token like "1,200.5" is not misread by blindly normalising commas to dots first
     * (which would turn it into "1.200.5" and find the wrong dot).
     */
    private static int decimalsOf(String token) {
        Matcher t = THOUSANDS.matcher(token);
        if (t.matches()) {
            String decimals = t.group(4);
            return decimals == null ? 0 : decimals.length();
        }
        String normalized = token.replace(',', '.');
        int dot = normalized.indexOf('.');
        return dot < 0 ? 0 : normalized.length() - dot - 1;
    }

    /** Every number in the text, each paired with whether it is written as a number of hours. */
    static List<NumberToken> numbersWithUnits(String text) {
        String cleaned = PERCENT.matcher(TIME.matcher(TASK_KEY.matcher(DATE.matcher(text).replaceAll(" ")).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
        List<NumberToken> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(cleaned);
        while (m.find()) {
            Matcher unit = HOURS_UNIT.matcher(cleaned);
            unit.region(m.end(), cleaned.length());
            String token = m.group();
            out.add(new NumberToken(parseNumber(token), unit.lookingAt(), decimalsOf(token)));
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

    private static Set<Double> raw(double... values) {
        Set<Double> out = new HashSet<>();
        for (double v : values) {
            addRoundings(v, out);
        }
        return out;
    }

    /** Each member's name as the FACTS record it -- the authoritative name -- keyed by member id. Never the
     * narrative's own {@code name} field, which is free text the language model itself wrote and is not
     * validated against anything: masking on that string would let a crafted name (e.g. one with a fabricated
     * number glued to its end) delete that number from its own scoped fields before verification ever runs. */
    private static Map<String, String> factNames(JsonNode facts) {
        Map<String, String> names = new HashMap<>();
        for (JsonNode m : facts.path("members")) {
            if (m.isObject() && m.path("id").isTextual() && m.path("name").isTextual()) {
                names.put(m.path("id").asText(), m.path("name").asText());
            }
        }
        return names;
    }

    /** Masks the member's own name out of their own scoped text before numbers are read. Uses a regex, not a
     * plain literal replace, so a name ending in digits cannot consume part of a longer number that merely
     * starts with it -- e.g. "Karim Fassi 23" must not swallow the leading "23" of "Karim Fassi 235.5 h". The
     * negative lookahead refuses to match when the name is immediately followed by a digit or a dot. */
    private static String maskOwnName(String text, String name) {
        return name == null || name.isBlank() ? text
                : Pattern.compile(Pattern.quote(name) + "(?![\\d.])").matcher(text).replaceAll(" ");
    }

    private static List<Field> textFields(Narrative n, JsonNode facts) {
        Set<Double> shared = sharedNumbers(facts);
        Map<String, Set<Double>> perMember = memberNumbers(facts);
        Map<String, String> memberFactNames = factNames(facts);
        Set<Double> everything = new HashSet<>(shared);
        perMember.values().forEach(everything::addAll);
        java.util.function.Function<String, Set<Double>> memberScope = id -> union(shared, perMember.getOrDefault(id, Set.of()));

        List<Field> fields = new ArrayList<>();
        fields.add(new Field("run_summary", n.runSummary(), everything));
        fields.add(new Field("model_notes", n.modelNotes(), everything));
        for (int i = 0; i < n.members().size(); i++) {
            Member m = n.members().get(i);
            Set<Double> allowed = memberScope.apply(m.memberId());
            String factName = memberFactNames.get(m.memberId());
            String p = "members[" + i + "]";
            fields.add(new Field(p + ".summary", maskOwnName(m.summary(), factName), allowed));
            for (int j = 0; j < m.warnings().size(); j++) {
                fields.add(new Field(p + ".warnings[" + j + "]", maskOwnName(m.warnings().get(j), factName), allowed));
            }
            for (int j = 0; j < m.patterns().size(); j++) {
                PatternFinding pf = m.patterns().get(j);
                fields.add(new Field(p + ".patterns[" + j + "].statement", maskOwnName(pf.statement(), factName), allowed));
                fields.add(new Field(p + ".patterns[" + j + "].evidence", maskOwnName(pf.evidence(), factName), allowed));
            }
            for (int j = 0; j < m.likelyWork().size(); j++) {
                LikelyWork lw = m.likelyWork().get(j);
                fields.add(new Field(p + ".likely_work[" + j + "].statement", maskOwnName(lw.statement(), factName), allowed));
                fields.add(new Field(p + ".likely_work[" + j + "].evidence", maskOwnName(lw.evidence(), factName), allowed));
            }
        }
        for (int i = 0; i < n.teamRisks().size(); i++) {
            TeamRisk r = n.teamRisks().get(i);
            fields.add(new Field("team_risks[" + i + "].detail", r.detail(), everything));
        }
        for (int i = 0; i < n.rebalancing().size(); i++) {
            Move mv = n.rebalancing().get(i);
            Set<Double> allowed = union(union(memberScope.apply(mv.fromMemberId()), memberScope.apply(mv.toMemberId())), raw(mv.hours()));
            fields.add(new Field("rebalancing[" + i + "].reason", mv.reason(), allowed));
        }
        for (int i = 0; i < n.suggestedAdjustments().size(); i++) {
            Adjustment a = n.suggestedAdjustments().get(i);
            Set<Double> allowed = union(memberScope.apply(a.memberId()), raw(a.deltaHours(), Math.abs(a.deltaHours())));
            fields.add(new Field("suggested_adjustments[" + i + "].reason", a.reason(), allowed));
        }
        return fields;
    }

    // ----- verification -------------------------------------------------------------------------

    private static double roundTo(double v, int decimals) {
        return BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_EVEN).doubleValue() + 0.0;
    }

    /**
     * Two tests in order: round1 equality, then equality at the cited number's own precision. The first
     * reproduces today's behaviour exactly (today's own check is {@code allowed.contains(round1(v))}, nothing
     * more -- {@code allowed} already carries both round1(fact) and round0(fact), so a cited integer matching
     * a fact's nearest-integer rounding already works today through round1(cited) landing on that stored
     * round0(fact) value; no separate round0(cited) test is needed to reproduce it, and adding one is NOT a
     * pure widening: citing "12.4" against a fact of 12.347 must stay unverified (12.4 is materially wrong at
     * its own one-decimal precision), but round0(12.4) == round0(12.347) == 12.0 would wrongly verify it if
     * checked directly. The second test is the new fallback, reached only before declaring UNVERIFIED,
     * comparing the cited value against facts' RAW (unrounded) values rounded to the cited number's own
     * precision -- this is the actual pure widening the design wants.
     */
    private static boolean matches(double cited, int decimals, Set<Double> allowed) {
        if (allowed.contains(round1(cited))) {
            return true;
        }
        if (decimals <= 1) {
            return false;
        }
        for (double fact : allowed) {
            if (roundTo(fact, decimals) == cited) {
                return true;
            }
        }
        return false;
    }

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
                if (matches(v, t.decimals(), f.allowed())) {
                    continue;
                }
                double r = round1(v);
                String elsewhere = known.contains(r) ? " (it is a fact of this run, but not of this field)" : "";
                unverified.add(f.path() + ": " + NarrativeContract.fmt(v) + " is not in the facts" + elsewhere);
            }
        }
        return new Report(checked, unverified, fields);
    }
}
