# Post-retarget defect sweep Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the four converging defects behind the wrong-looking live run of 2026-09-14 — unreachable
overload in the seed, three causes of `NumberVerifier` false negatives, bare-name pressure lists, and three
smaller cosmetic bugs — then correct four false documentation statements and remove two backlog entries whose
subject code is already gone.

**Architecture:** Nine tasks. Task 1 splits into 1a (correct `WorkFamily.weeklyHours`, guarded by a new jqwik
property test) and 1b (rebuild the experiment database from the corrected seed — a database-only task with no
diff). Tasks 2–6 are independent code fixes. Tasks 7–8 are documentation. No forecasting logic, model, or
`CLAUDE.md` hard rule changes.

**Tech Stack:** Java 21, JUnit 6, jqwik, the `server/tools/experiment.sh` driver, SQLite.

**Spec:** `docs/superpowers/specs/2026-09-15-post-retarget-defect-sweep-design.md` — read both; this plan
argues from that spec and departs from it once, explicitly, in Task 2 (see "Deviation from the spec" there).

## Global Constraints

- The language model never produces a forecast number; this sweep changes no rule in `CLAUDE.md`'s "Hard
  rules" section.
- Demand is never capped by capacity.
- Test-driven development for every code change; jqwik property tests are named `*PropertyTest.java` —
  **never** `*Properties.java`, which surefire silently skips (`docs/backlog.md`, "Java migration").
- The gate is `bash scripts/check.sh` run inside the development container (`bash scripts/devbox.sh shell`),
  never on the Windows host, where it skips Maven and still exits 0. Wipe
  `server/forecast-core/target/surefire-reports` before a run you intend to trust, and read the count from
  `TEST-*.xml`, never by summing `*.txt` (a class mixing JUnit `@Test` with jqwik `@Property` has the second
  engine overwrite the first's `.txt`).
- Baseline to beat: 409 tests, 0 failures, 13 skipped.
- `git commit` here runs the whole gate as a pre-commit hook: give every commit a generous timeout and never
  pass `--no-verify`.
- Ask before committing and before pushing; do not push `main`.

---

### Task 1a: raise `WorkFamily.weeklyHours` and guard it with a property test

Corrects the seed's weekly work supply, unchanged since the original seed commit, so a seeded member's history
can exceed the corrected 44 h capacity. Spec section 3.1.

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/WorkFamily.java:10-19`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkFamilyTest.java:46`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/RhythmTest.java` (a third stale
  reference the spec's own claim missed — see Step 4b)
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkFamilyPropertyTest.java`
- Test (unchanged, expected to still pass): `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/SeedGeneratorTest.java:141-147`

**Interfaces:**
- Consumes: `WorkFamily.weeklyHours` (existing field), `CapacityWriter.BASE_HOURS` (existing constant, 44.0),
  `WorkStyle.MAX_OVERTIME_FACTOR` (existing constant, 1.35), `SeedGenerator.generate(ExportEnvelope, SeedConfig)`
  (existing), `SeedConfig(int weeks, LocalDate end, long seed, boolean synthetic, int users)` (existing record).
- Produces: nothing new consumed by later tasks — task 1b consumes the corrected constants transitively
  through `SeedGenerator`.

- [ ] **Step 1: Write the failing property test**

Create `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkFamilyPropertyTest.java`:

```java
package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ExportEnvelope;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Property;

/**
 * The seed's whole purpose (design 2026-09-15, section 3.1): a member-week can land above capacity and a
 * member-week can land well below it, both inside a single team, so overload and rebalancing are observable
 * at all. `SeedGeneratorTest.loggedHoursTrackAssignedEstimatesAndNeverExceedPresence` already proves the
 * upper bound; this is the missing lower half, checked as a population-level rate rather than a bare
 * existence check: an event week (`Rhythm.EVENT_FACTOR` = 1.4) combined with a high personal factor (up to
 * 1.3) can already push a single member-week over capacity even under the pre-2026-09-15 constants, so "at
 * least one member-week above capacity" does not by itself discriminate a corrected seed from an
 * uncorrected one. A corrected seed is expected to put a meaningful fraction of member-weeks over capacity,
 * not one rare tail hit — the thresholds below come from the design's own arithmetic (section 3.1's table:
 * typical logged hours moves from ~28h under the old constants to a median near 37h under the new ones),
 * with margin on both sides. Fixture size: 120 users, 52 weeks, seed 7 — the same population task 1b
 * rebuilds the database from.
 */
class WorkFamilyPropertyTest {

    private static final SeedConfig CFG = new SeedConfig(52, LocalDate.of(2026, 9, 6), 7, true, 120);

    @Property(tries = 1)
    void memberWeeksSpanCapacityInBothDirectionsWithinATeam() {
        ExportEnvelope env = SeedGenerator.generate(null, CFG);
        Map<String, String> teamOf = new HashMap<>();
        for (var tm : env.rows("team_members")) {
            teamOf.put((String) tm.get("user_id"), (String) tm.get("team_id"));
        }
        Map<String, Map<String, Double>> perMemberWeek = new HashMap<>();
        for (var l : env.rows("time_logs")) {
            String user = (String) l.get("user_id");
            LocalDate day = LocalDate.parse((String) l.get("log_date"));
            String week = SeedConfig.mondayOf(day).toString();
            perMemberWeek.computeIfAbsent(user, k -> new HashMap<>()).merge(week, (Double) l.get("hours"), Double::sum);
        }
        int totalWeeks = 0;
        int overCapacity = 0;
        int underThreshold = 0;
        double sum = 0;
        Set<String> overloadedTeams = new HashSet<>();
        Set<String> underloadedTeams = new HashSet<>();
        for (var e : perMemberWeek.entrySet()) {
            String team = teamOf.get(e.getKey());
            for (double hours : e.getValue().values()) {
                totalWeeks++;
                sum += hours;
                if (hours > CapacityWriter.BASE_HOURS) {
                    overCapacity++;
                    if (team != null) {
                        overloadedTeams.add(team);
                    }
                } else if (hours < 0.7 * CapacityWriter.BASE_HOURS) {
                    underThreshold++;
                    if (team != null) {
                        underloadedTeams.add(team);
                    }
                }
            }
        }
        double mean = sum / totalWeeks;
        double overRate = (double) overCapacity / totalWeeks;
        assertTrue(mean > 27.5, "mean member-week " + mean + " h over " + totalWeeks + " weeks -- too close to the old ~23-28h supply");
        assertTrue(overRate > 0.03, "only " + overCapacity + "/" + totalWeeks + " (" + overRate + ") member-weeks exceeded capacity -- not a systematic shift");
        assertTrue(underThreshold > 0, "no member-week fell below 70% of capacity");
        overloadedTeams.retainAll(underloadedTeams);
        assertTrue(!overloadedTeams.isEmpty(), "no team held both an overloaded and an underloaded member-week");
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Inside the development container (`bash scripts/devbox.sh shell`):

```bash
cd server && mvn -q -pl forecast-core test -Dtest=WorkFamilyPropertyTest
```

Expected: FAIL — on the current `weeklyHours` constants, `mean` sits at 25.71 h, under the 27.5 h threshold
(confirmed by measurement: this fixture is fully deterministic — fixed `SeedConfig` seed and population, no
`@ForAll` inputs — so this is not a range, it is the exact number every run produces). Read the assertion
failure message for the actual computed `mean` — it is printed as part of the failure — and record it in
this task's report; if the assertion does *not* fail, stop and report BLOCKED with the actual number rather
than adjusting the threshold yourself, since that would mean the threshold needs to be recalibrated, which is
a plan decision, not an implementation one.

- [ ] **Step 3: Correct the constants**

In `server/forecast-core/src/main/java/com/workloadhub/forecast/seed/WorkFamily.java`, change the last
constructor argument (`weeklyHours`) of each enum constant per the spec's table:

```java
    // order = precedence: the first family with a matching whole word wins
    CALIBRATION(Set.of("calibration"), 0.55, 0.20, 0.20, 0.05, 12, 0.35, 42),
    DATA(Set.of("data", "ai", "dai"), 0.55, 0.20, 0.20, 0.05, 8, 0.55, 36),
    SUPPORT(Set.of("hr", "admin", "administration", "administrator", "finance", "purchasing", "it", "facility",
            "specialist", "generalist", "officer"), 0.50, 0.20, 0.30, 0.00, 4, 0.70, 26),
    SYSTEMS(Set.of("system", "systems"), 0.60, 0.15, 0.20, 0.05, 16, 0.30, 39),
    ELECTRONICS(Set.of("electric", "electronics", "ee", "software", "sw", "functions"), 0.50, 0.30, 0.15, 0.05, 10, 0.40, 39),
    VALIDATION(Set.of("validation", "verification", "homologation", "fleet", "test"), 0.45, 0.35, 0.15, 0.05, 12, 0.30, 39),
    DESIGN(Set.of("design", "simulation", "cfd", "dmu"), 0.60, 0.10, 0.25, 0.05, 20, 0.35, 39),
    COORDINATION(Set.of("project", "coordination", "leader", "manager", "workshop", "center"), 0.40, 0.10, 0.45, 0.05, 6, 0.60, 21),
    UNKNOWN(Set.of(), 0.60, 0.15, 0.20, 0.05, 16, 0.30, 39);
```

(CALIBRATION 32→42; SYSTEMS/DESIGN/ELECTRONICS/VALIDATION/UNKNOWN 30→39; DATA 28→36; SUPPORT 20→26;
COORDINATION 16→21 — matching the spec's table in section 3.1.)

- [ ] **Step 4: Update the pinned test values**

In `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkFamilyTest.java:46`:

```java
    @ParameterizedTest
    @CsvSource({"CALIBRATION, 12, 42", "COORDINATION, 6, 21", "SUPPORT, 4, 26"})
    void carriesTheDesignParameters(WorkFamily f, double median, double weekly) {
        assertEquals(median, f.medianEstimate);
        assertEquals(weekly, f.weeklyHours);
        assertEquals(1.0, f.delivery + f.defect + f.support + f.container, 1e-9);
    }
```

- [ ] **Step 4b: fix a third stale reference the spec missed — `RhythmTest`**

The spec's section 3.1 claims "only two places name it: `Rhythm.java:71`, and the `@CsvSource` at
`WorkFamilyTest.java:46`" — that claim is wrong. `RhythmTest.java`'s
`targetIsBaseTimesFactorsAndLeadersAreHalved` test also hardcodes `CALIBRATION.weeklyHours` as a literal
bound check, and it fails once Step 3 lands (it was written against the old value of 32). The test's own
fixture person (`AbsencePlannerTest.person(...)`) is constructed directly as `WorkFamily.CALIBRATION`, so this
is the same family this task changes from 32 to 42.

In `server/forecast-core/src/test/java/com/workloadhub/forecast/seed/RhythmTest.java`, in
`targetIsBaseTimesFactorsAndLeadersAreHalved`, change:

```java
        assertTrue(r.base(eng) >= 32 * 0.5 && r.base(eng) <= 32 * 1.3);
```

to:

```java
        assertTrue(r.base(eng) >= 42 * 0.5 && r.base(eng) <= 42 * 1.3);
```

- [ ] **Step 5: Run the property test and confirm it passes**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=WorkFamilyPropertyTest,WorkFamilyTest
```

Expected: PASS — with the corrected constants, `mean` measures 29.15 h (comfortably above the 27.5 h
threshold, ~1.65 h of margin) and `overRate` measures 6.55% (over twice the 3% floor: 248 of 3,789
member-weeks). Record both numbers, and the team-overlap result, in the report — this is what proves the
fix, not just a passing boolean.

- [ ] **Step 6: Run `SeedGeneratorTest` and confirm the existing upper-bound test still passes unchanged**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=SeedGeneratorTest
```

Expected: PASS — `loggedHoursTrackAssignedEstimatesAndNeverExceedPresence` still holds, because the ceiling
comes from presence (`WorkQueue.logDay`) and the overtime factor, not from `weeklyHours` (spec section 3.1,
"the ceiling is structural").

- [ ] **Step 7: Full module test run**

```bash
cd server && rm -rf forecast-core/target/surefire-reports && mvn -B -q verify
```

Expected: PASS, count at or above the 409-test baseline (this task added one property test).

- [ ] **Step 8: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/seed/WorkFamily.java \
        server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkFamilyTest.java \
        server/forecast-core/src/test/java/com/workloadhub/forecast/seed/RhythmTest.java \
        server/forecast-core/src/test/java/com/workloadhub/forecast/seed/WorkFamilyPropertyTest.java
git commit -m "fix(seed): raise WorkFamily.weeklyHours to make overload reachable

The weekly work supply was never raised to match the 44 h capacity correction
of 2026-09-13; a typical member sat at 65% of capacity, so overload was
arithmetically unreachable on seeded data. Scaled per family so a median
member lands near 84% instead, guarded by a new jqwik property test proving
member-weeks span capacity in both directions within one team.

RhythmTest also pinned CALIBRATION's old weeklyHours (32) as a literal bound
check -- a third stale reference the design's own claim of 'only two places'
missed -- corrected alongside WorkFamilyTest's pinned values."
```

(No pre-commit hook exists in this repo — run the gate yourself before committing; `git commit` itself is
fast.)

---

### Task 1b: rebuild the experiment database

Produces no diff — its output is a database file under the development container's `/data` mount and a
recorded set of assertion results in this plan's notes. Runs only after Task 1a is committed and green.
Spec sections 3.2, 3.3, 3.4.

**Files:** none modified. Output: `/data/workloadhub.db` (inside the container), with the pre-existing one
moved aside.

**Interfaces:**
- Consumes: `server/tools/experiment.sh` (existing driver — `init-db`, `seed --synthetic`, `import`), the
  corrected `WorkFamily.weeklyHours` from Task 1a.
- Produces: the rebuilt database that Task 7's README update and the end-to-end acceptance check (section
  11.2) run against. No later task in this plan reads a file this task writes; it is checked by hand.

- [ ] **Step 1: Rebuild, inside the development container**

```bash
bash scripts/devbox.sh shell
```

Inside the container:

```bash
bash server/tools/experiment.sh init-db --db /data/whf-v2.db
bash server/tools/experiment.sh seed --synthetic --users 120 --weeks 52 --seed 7 --end 2026-09-06 --out /data/seed-v2.json
bash server/tools/experiment.sh import --db /data/whf-v2.db /data/seed-v2.json
mv /data/workloadhub.db /data/workloadhub-40h.db
mv /data/whf-v2.db /data/workloadhub.db
```

- [ ] **Step 2: Assertion 1 — capacity corrected**

```bash
sqlite3 /data/workloadhub.db "SELECT DISTINCT base_capacity_hrs FROM user_capacity;"
```

Expected: exactly `44.0`, nothing else. Record the actual output in this task's notes when executing.

- [ ] **Step 3: Assertion 2 — overtime and weekly overload reachable**

```bash
sqlite3 /data/workloadhub.db "
SELECT count(*) FROM (SELECT user_id, log_date, sum(hours) h FROM time_logs GROUP BY user_id, log_date)
WHERE h > 8.8;"

sqlite3 /data/workloadhub.db "
SELECT count(*) AS over_44, avg(h) AS mean_week FROM (
  SELECT user_id, strftime('%Y-%W', log_date) wk, sum(hours) h
  FROM time_logs GROUP BY user_id, wk) WHERE h > 44.0;"
```

Expected: both counts above zero. On the mean: run the *unfiltered* population mean too (the query above,
without its outer `WHERE h > 44.0`, i.e. `SELECT count(*) AS n, avg(h) AS mean_week FROM (SELECT user_id,
strftime('%Y-%W', log_date) wk, sum(hours) h FROM time_logs GROUP BY user_id, wk);`) — this is the same
statistic Task 1a's `WorkFamilyPropertyTest` checks (`mean > 27.5`), so expect a similar figure, high-twenties
to low-thirties, not "mid thirties": the population mean is pulled down from a typical full-time member's
~36-37h (the design's per-member arithmetic, section 3.1's table) by team leaders logging at half rate,
season dips, ramp-up weeks and absences, none of which that per-member figure accounts for. Record both the
filtered-query mean (necessarily >44 by construction — not a meaningful check on its own) and the unfiltered
population mean.

- [ ] **Step 4: Assertion 3 — teams large enough**

```bash
sqlite3 /data/workloadhub.db "
SELECT t.id, t.name, count(*) n FROM team_members tm JOIN teams t ON t.id = tm.team_id
GROUP BY t.id ORDER BY n DESC;"
```

Expected: the largest team has at least eight members. Record the full list of team sizes, not just the
first few rows — ties at the top are expected (multiple department-scale teams of the same size), and Step 5
needs to try more than one of them if the first has no usable leader or no overloaded member.

- [ ] **Step 5: Assertion 4 — both pressures present in one team**

Ties at the largest size are common with this population (9 teams tied at 10 members were observed on one
run). Try tied-largest teams in turn — skipping any with "no TEAM_LEADER" (the sample host needs one to run
as) — until one satisfies the criterion below, or every tied-largest team has been tried:

```bash
bash server/examples/run-host-example.sh --team <a-largest-team-uuid> --end 2026-09-06
```

Then pull that run's persisted facts and check the `rebalancing_candidates` node directly (the console only
prints `overloaded`, never `underloaded` — see `HostExample.currentForecast`'s own comment on why day-summed
and window-summed overload can legitimately disagree, which is not a defect if you see it: window-net
overload, used here, is `max(0, windowDemand - windowCapacity)`, and a member can show day-level overload
while still netting under capacity for the whole window):

```bash
sqlite3 /data/workloadhub.db "SELECT facts_json FROM forecast_facts WHERE run_id IN
  (SELECT id FROM forecast_runs WHERE team_id='<team-uuid>' ORDER BY created_at DESC LIMIT 1);" > /data/facts.json
python3 -c "
import json
d = json.load(open('/data/facts.json'))
rc = d['rebalancing_candidates']
print('overloaded:', len(rc['overloaded']), rc['overloaded'])
print('underloaded:', len(rc['underloaded']), rc['underloaded'][:3])
"
```

Expected: at least one overloaded member and at least one underloaded member reported for the SAME team.
This is the real acceptance criterion (spec section 3.3, point 4) — record which team UUID satisfies it and
its `overloaded`/`underloaded` summary; later tasks (7, and the end-to-end acceptance in section 11.2) use
that specific team, not necessarily the single largest.

- [ ] **Step 6: Branch check — stop conditions**

If assertion 2's weekly count is zero: stop. Per spec section 3.4, the next suspect is `WorkStyle.discipline`,
but raising `DISCIPLINE_MIN` is a design change (it narrows the estimate-versus-logged gap the seed exists to
make separable), not a lever this task may pull — re-scope instead of patching.

If assertion 4 fails on every tied-largest team you can run (not just the first one or two): stop. That is a
`ReferenceData.syntheticUsers` distribution question (spec section 3.4), and Tasks 2 and 3 cannot be accepted
without it (section 11.2) — re-scope instead of patching. Ties are common at this population size (9 teams at
n=10 were observed on one run), and which specific team lacks a forecast-overloaded member is not
predictable from team size alone — the underlying arithmetic (a member's *typical* demand, not just an
unpredictable event week, can now sit above capacity for a high-personal-factor member, per section 3.1) is
per-member, so whether a given 10-member team happens to include such a member is somewhat random. Exhaust
the tied set before concluding this is a real distribution failure, not just an under-searched first
attempt.

- [ ] **Step 7: Record the results**

Add a short note to this plan's task list (or the execution ledger) with the four assertions' actual
numbers, so the plan's own record — not just a claim — shows the database was rebuilt and verified.

No commit: this task changes no tracked file.

---

### Task 2: `NumberVerifier` — three causes of false `UNVERIFIED`, with a deviation from the spec

Three independent fixes: the lookbehind that lets digits glued to identifiers open a number, task keys read as
negative numbers, and double rounding that a fact and its own two-decimal rendering can miss each other on.
Spec section 4, with one correction to section 4.1.

**Deviation from the spec:** Section 4.1 says to widen the `NUMBER` pattern's lookbehind from `(?<![\d.])` to
`(?<![\w.])` in both alternatives. Done literally, this also blocks numbers glued to *words* — breaking the
existing test `NumberVerifierTest.numbersGluedToAWordAreExtracted` (`MASE0.91` → 0.91, `demand52.5h` → 52.5)
and, worse, making such numbers entirely unchecked, which is the exact weakening the spec's own risk 1 warns
against. The two problems the spec is actually trying to fix are the false minus signs from `EE2-59` and
`hours_per_week_13w` — both caused only by the **hyphen** alternative's lookbehind, not the digit alternative.
This plan guards the sign alone, in both alternatives, and leaves the digit lookbehind as `(?<![\d.])`:

```java
private static final Pattern NUMBER = Pattern.compile(
        "(?:(?<![\\w.])-)?(?<![\\d.])\\d{1,3}(?:[ ,]\\d{3})+(?:[.,]\\d+)?(?![\\w.]*\\d)"
                + "|(?:(?<![\\w.])-)?(?<![\\d.])\\d+(?:[.,]\\d+)?(?![\\w.]*\\d)");
```

Hand-verified (per `docs/superpowers/specs/...` memory of this deviation): `MASE0.91` → 0.91; `demand52.5h` →
52.5; `EE2-59` → 2 then 59 (not −59); `bias -1.228` → −1.228; `week-30 h` → 30.0; `hours_per_week_13w` → 13 (a
small integer without an hours unit — already skipped by `SMALL_INTEGER_ALLOWANCE`, so it is not "unverified"
either way, but it should not be read as a *negative* number).

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/NumberVerifier.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/NumberVerifierTest.java`

**Interfaces:**
- Consumes: `NumberVerifier.NUMBER` (existing private `Pattern`), `NumberVerifier.walk`, `NumberVerifier.Field`
  (existing private helpers).
- Produces: `NumberVerifier.NumberToken` gains a `decimals` field — `record NumberToken(double value, boolean
  hours, int decimals)`. Any later code constructing a `NumberToken` (none outside this file today) must pass
  it. `NumberVerifier.raw(double...)` replaces the private `rounded(double...)` helper used by `textFields`.
  `NumberVerifier.matches(double cited, int decimals, Set<Double> allowed)` is a new package-private static
  method `verify` calls in place of the old `f.allowed().contains(round1(v))` check.

- [ ] **Step 1: Write the failing regression tests for 4.1 and 4.2**

Add to `NumberVerifierTest.java` (after `theHoursUnitIsRecognisedInItsCommonSpellings`):

```java
    @Test
    void aFalseMinusSignFromAHyphenatedWeekLabelIsNotProduced() {
        // Lowercase, so TASK_KEY (which requires an uppercase start) does not mask it either: isolates 4.1.
        // The bug is specifically a hyphen preceded by a LETTER: the current lookbehind (?<![\d.]) blocks a
        // digit-preceded hyphen already (e.g. "8080-8443" already reads positive today), but lets a
        // letter-preceded one open a false sign.
        assertEquals(List.of(30.0), NumberVerifier.numbersInText("week-30 h logged."));
    }

    @Test
    void aGenuineNegativeNumberIsStillRead() {
        assertEquals(List.of(-1.228), NumberVerifier.numbersInText("bias -1.228"));
    }

    @Test
    void aTeamCodesStrayDigitsAreRemovedByTheTaskKeyMask() {
        // The spec's own observed case: EE2-59 matches TASK_KEY whole, so neither "2" nor "59" survives --
        // the task-key mask, not the sign fix, is what removes them (4.1 alone would still read the "2").
        assertEquals(List.of(), NumberVerifier.numbersInText("Team EE2-59 owns the task."));
    }

    @Test
    void aTaskKeyIsMaskedBeforeItsDigitsAreRead() {
        Report r = NumberVerifier.verify(narrative("See WEB-3 and CT2-14 for details. Overload of 12.0 h."), FACTS);
        assertTrue(r.ok(), r.unverified().toString());
    }

    @Test
    void aTaskKeyDoesNotConsumeAFollowingIsoDate() {
        // TASK_KEY sits after DATE in the pre-clean chain, so an ISO date is already masked; this pins the order.
        assertEquals(List.of(52.0), NumberVerifier.numbersInText("WEB-3 due 2026-09-07, demand 52.0 h."));
    }
```

- [ ] **Step 2: Run and confirm failure**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=NumberVerifierTest
```

Expected: FAIL on `aFalseMinusSignFromAHyphenatedWeekLabelIsNotProduced` (`week-30 h` currently yields `-30.0`
— the current lookbehind lets a letter-preceded hyphen open a false sign) and on
`aTeamCodesStrayDigitsAreRemovedByTheTaskKeyMask` (`EE2-59` currently yields `2.0, 59.0` — the digit-preceded
hyphen there is already safe today, but the stray digits themselves are only removed once the task-key mask
exists). The other three — `aGenuineNegativeNumberIsStillRead`, `aTaskKeyDoesNotConsumeAFollowingIsoDate`, and
`aTaskKeyIsMaskedBeforeItsDigitsAreRead` — already pass today (the last one only by luck: `WEB-3`'s `3` and
`CT2-14`'s `14` slip through today via `SMALL_INTEGER_ALLOWANCE`, not because they are excluded from
consideration). Keep all three as pinning tests: after the mask lands, they pass for the right reason instead
of by coincidence.

- [ ] **Step 3: Fix the lookbehind (4.1) and add the task-key mask (4.2)**

In `NumberVerifier.java`, replace:

```java
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![\\d.])-?\\d{1,3}(?:[ ,]\\d{3})+(?:[.,]\\d+)?(?![\\w.]*\\d)|(?<![\\d.])-?\\d+(?:[.,]\\d+)?(?![\\w.]*\\d)");
```

with:

```java
    private static final Pattern NUMBER = Pattern.compile(
            "(?:(?<![\\w.])-)?(?<![\\d.])\\d{1,3}(?:[ ,]\\d{3})+(?:[.,]\\d+)?(?![\\w.]*\\d)"
                    + "|(?:(?<![\\w.])-)?(?<![\\d.])\\d+(?:[.,]\\d+)?(?![\\w.]*\\d)");
```

Add the task-key pattern next to `DATE`:

```java
    private static final Pattern TASK_KEY = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+\\b");
```

Add it to the pre-clean chain in `numbersWithUnits`, after `DATE`:

```java
    static List<NumberToken> numbersWithUnits(String text) {
        String cleaned = PERCENT.matcher(TIME.matcher(TASK_KEY.matcher(DATE.matcher(text).replaceAll(" ")).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
        ...
```

- [ ] **Step 4: Run and confirm the 4.1/4.2 tests pass**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=NumberVerifierTest,NumberVerifierPropertyTest
```

Expected: PASS, including the pre-existing `numbersGluedToAWordAreExtracted`,
`numbersInTextSkipDatesTimesAndPercentages`, and `thousandsSeparatorsDoNotBreakDecimalCommas`.

- [ ] **Step 5: Write the failing regression test for 4.3 (double rounding)**

The defect needs a fact whose raw value, rounded to one decimal under `HALF_EVEN`, lands one tenth away from
a legitimate two-decimal rendering of that same fact. `12.347` is such a value: `round1(12.347)` stores
`12.3`, but `12.347` itself renders to two decimals as `12.35`, and `round1(12.35)` is `12.4` under
`HALF_EVEN` (12.35 is exactly halfway between 12.3 and 12.4; 4 is the even neighbour) — so today the fact and
its own correct rendering land on different one-decimal buckets and never meet. Add a dedicated fixture
carrying this value in a team-scoped field, so the test does not depend on `FACTS`'s existing member rows:

```java
    static final JsonNode ROUNDING_FACTS = ExportFiles.mapper().readTree("""
            {"run": {"id": "r", "windows": [{"index": 1, "start": "2026-09-07", "end": "2026-09-11"}], "generated_at": "2026-09-03T10:00:00", "horizons": [1, 2]},
             "team": {"id": "t", "totals": [{"window": 1, "start": "2026-09-07", "demand": 12.347, "capacity": 88.0}]},
             "members": []}
            """);

    @Test
    void aTwoDecimalRenderingOfAFactNotStoredAtThatPrecisionIsVerified() {
        Report r = NumberVerifier.verify(NarrativeContract.parse(
                "{\"run_summary\": \"Team demand is 12.35 h.\", \"members\": []}"), ROUNDING_FACTS);
        assertTrue(r.ok(), r.unverified().toString());
    }

    @Test
    void aFabricatedNumberAtAnyPrecisionIsStillUnverified() {
        // 45.2 is far from every fact in ROUNDING_FACTS (12.347, 88.0) at every rounding: round1, round0 and
        // its own 1-decimal precision all miss.
        Report r = NumberVerifier.verify(NarrativeContract.parse(
                "{\"run_summary\": \"Team demand is 45.2 h.\", \"members\": []}"), ROUNDING_FACTS);
        assertFalse(r.ok());
    }

    @Test
    void aMembersTextStillCannotCiteAnotherMembersNumberAtAnyPrecision() {
        Report r = NumberVerifier.verify(narrative("B logged 20.53 h."), FACTS);
        assertFalse(r.ok(), "B's 20.5 h belongs to B's own scope, not A's");
    }

    @Test
    void aHighPrecisionFabricationIsNotAcceptedByRoundingItDownToAFact() {
        // 9.1234 does not round1- or round0-match 12.347, and rounding the true fact to 4 decimals
        // (12.3470) does not equal 9.1234 either: the fallback test compares against the fact's own raw
        // value at the cited precision, not the other way around, so a fabricated number with many decimals
        // gets no more benefit of the doubt than a plain one.
        Report r = NumberVerifier.verify(NarrativeContract.parse(
                "{\"run_summary\": \"Team demand is 9.1234 h.\", \"members\": []}"), ROUNDING_FACTS);
        assertFalse(r.ok());
    }
```

- [ ] **Step 6: Run and confirm it fails**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=NumberVerifierTest
```

Expected: FAIL on `aTwoDecimalRenderingOfAFactNotStoredAtThatPrecisionIsVerified` — `round1(12.347)` stores
`12.3`; the cited `12.35` rounds under today's `round1` to `12.4`; `12.3 != 12.4`, so a true rendering of the
fact is reported unverified. The other three tests already pass under today's code (they document the
boundary the fix must not cross) and serve as regression guards once Step 7 lands.

- [ ] **Step 7: Implement the precision-aware match**

Add a `decimals` field to `NumberToken` and populate it in `numbersWithUnits`:

```java
    record NumberToken(double value, boolean hours, int decimals) {
    }
```

```java
    static List<NumberToken> numbersWithUnits(String text) {
        String cleaned = PERCENT.matcher(TIME.matcher(TASK_KEY.matcher(DATE.matcher(text).replaceAll(" ")).replaceAll(" ")).replaceAll(" ")).replaceAll(" ");
        List<NumberToken> out = new ArrayList<>();
        Matcher m = NUMBER.matcher(cleaned);
        while (m.find()) {
            Matcher unit = HOURS_UNIT.matcher(cleaned);
            unit.region(m.end(), cleaned.length());
            String token = m.group();
            int dot = token.replace(',', '.').indexOf('.');
            int decimals = dot < 0 ? 0 : token.length() - dot - 1;
            out.add(new NumberToken(parseNumber(token), unit.lookingAt(), decimals));
        }
        return out;
    }
```

Replace the raw-fact storage: `walk` stores the raw value alongside the two roundings, and `raw(double...)`
replaces `rounded(double...)`:

```java
    private static void walk(JsonNode node, Set<Double> out) {
        if (node == null) {
            return;
        }
        if (node.isNumber()) {
            double v = node.asDouble();
            out.add(round1(v));
            out.add(round0(v));
            out.add(v);
        } else if (node.isObject()) {
            node.properties().forEach(e -> walk(e.getValue(), out));
        } else if (node.isArray()) {
            node.forEach(n -> walk(n, out));
        }
    }
```

```java
    private static Set<Double> raw(double... values) {
        Set<Double> out = new HashSet<>();
        for (double v : values) {
            out.add(round1(v));
            out.add(round0(v));
            out.add(v);
        }
        return out;
    }
```

Rename the two call sites in `textFields` from `rounded(...)` to `raw(...)`.

Add the precision-aware matcher:

```java
    private static double roundTo(double v, int decimals) {
        return BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_EVEN).doubleValue() + 0.0;
    }

    /**
     * Three tests in order: round1 equality, round0 equality, then equality at the cited number's own
     * precision. The first two reproduce today's behaviour exactly, so nothing that verifies today can stop
     * verifying; the third is a fallback reached only before declaring UNVERIFIED, so this is a pure widening.
     */
    private static boolean matches(double cited, int decimals, Set<Double> allowed) {
        if (allowed.contains(round1(cited)) || allowed.contains(round0(cited))) {
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
```

Update `verify` to use `matches` instead of the direct `contains(round1(v))` check:

```java
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
```

Note: `matches` needs the *raw* fact set, not just the two roundings, to test the third precision — this is
why `walk`/`raw` now store the raw value too. `known` (used only for the "elsewhere" message) can keep calling
the original `factNumbers`/`walk`, unaffected.

- [ ] **Step 8: Run and confirm the 4.3 tests pass, and nothing already-passing regresses**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=NumberVerifierTest,NumberVerifierPropertyTest
```

Expected: PASS, all tests including the negative tests from Step 5 and the earlier regression tests from
Step 1.

- [ ] **Step 9: Full module test run**

```bash
cd server && rm -rf forecast-core/target/surefire-reports && mvn -B -q verify
```

Expected: PASS.

- [ ] **Step 10: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/ai/NumberVerifier.java \
        server/forecast-core/src/test/java/com/workloadhub/forecast/ai/NumberVerifierTest.java
git commit -m "fix(ai): stop NumberVerifier flagging true numbers as unverified

Three independent causes converged in the 2026-09-14 live run's six false
UNVERIFIED items: a false minus sign from hyphenated codes like EE2-59, a
task key's own digits read as a fact, and double rounding letting a fact and
its own two-decimal rendering miss each other. Fixes the sign only (not the
digit lookbehind, which would stop checking numbers glued to words) plus a
task-key mask plus a precision-aware fallback match that cannot un-verify
anything that verifies today. Negative tests pin the boundary: a fabricated
number, a number scoped to another member, and a number that only matches a
fact at a precision the fact does not support all still fail."
```

(Bash timeout: at least 400000 ms.)

---

### Task 3: pressure lists carry ids, names and hours

`backlog_pressed`/`deadline_pressed` currently push bare names; they become objects with `member_id`, `name`
and the pressure figure, closing `docs/backlog.md`'s open item and letting `NumberVerifier.memberNumbers()`
scope the hours correctly. Spec section 5.

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/facts/FactsBuilder.java:290-320`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/facts/FactsBuilderTest.java:262-265`
- Modify: `server/forecast-core/src/main/resources/skills/whf-rebalancing-advice/SKILL.md` (rule 8)

**Interfaces:**
- Consumes: `MemberRow(UUID id, String fullName, ...)` (existing record, `data/rows/MemberRow.java`),
  `MemberWindowForecast(... double backlogExcessHrs, double dueExcessHrs)` (existing record,
  `api/MemberWindowForecast.java`), `FactsBuilder.map(Object...)`, `FactsBuilder.str(Object)`,
  `FactsBuilder.round1(double)` (existing private/package helpers used elsewhere in the class).
- Produces: `backlog_pressed` and `deadline_pressed` entries become
  `{"member_id": String, "name": String, "backlog_excess_hrs"|"due_excess_hrs": Double}` instead of a bare
  `String`. No other task in this plan reads these lists.

- [ ] **Step 1: Write the failing test**

Rewrite `FactsBuilderTest.java:262-265`'s assertions (the surrounding fixture setup is unchanged):

```java
        @SuppressWarnings("unchecked")
        Map<String, Object> candidates = (Map<String, Object>) facts.get("rebalancing_candidates");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> backlogPressed = (List<Map<String, Object>>) candidates.get("backlog_pressed");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deadlinePressed = (List<Map<String, Object>>) candidates.get("deadline_pressed");
        assertTrue(backlogPressed.stream().anyMatch(e -> pressed.fullName().equals(e.get("name")) && pressed.id().toString().equals(e.get("member_id"))));
        assertFalse(backlogPressed.stream().anyMatch(e -> late.fullName().equals(e.get("name"))), "late's backlog is absorbed by the run's own demand");
        assertTrue(deadlinePressed.stream().anyMatch(e -> late.fullName().equals(e.get("name")) && late.id().toString().equals(e.get("member_id"))));
        assertFalse(deadlinePressed.stream().anyMatch(e -> pressed.fullName().equals(e.get("name"))), "pressed has no due date at all");
        assertTrue(backlogPressed.stream().anyMatch(e -> e.get("backlog_excess_hrs") instanceof Double d && d > 0));
        assertTrue(deadlinePressed.stream().anyMatch(e -> e.get("due_excess_hrs") instanceof Double d && d > 0));
```

- [ ] **Step 2: Run and confirm it fails**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=FactsBuilderTest#theTwoListsNameTheMembersToActOn
```

Expected: FAIL — a compile error or `ClassCastException`, since the lists today hold `String`, not `Map`.

- [ ] **Step 3: Implement**

In `FactsBuilder.java`'s `rebalancing` method, replace the two bare-name lines:

```java
            if (!rows.isEmpty() && rows.get(rows.size() - 1).backlogExcessHrs() > 0) {
                backlogPressed.add(m.fullName());
            }
            if (rows.stream().anyMatch(r -> r.dueExcessHrs() > 0)) {
                deadlinePressed.add(m.fullName());
            }
```

with:

```java
            if (!rows.isEmpty() && rows.get(rows.size() - 1).backlogExcessHrs() > 0) {
                backlogPressed.add(map("member_id", str(m.id()), "name", m.fullName(),
                        "backlog_excess_hrs", round1(rows.get(rows.size() - 1).backlogExcessHrs())));
            }
            double maxDueExcess = rows.stream().mapToDouble(MemberWindowForecast::dueExcessHrs).max().orElse(0.0);
            if (maxDueExcess > 0) {
                deadlinePressed.add(map("member_id", str(m.id()), "name", m.fullName(), "due_excess_hrs", round1(maxDueExcess)));
            }
```

`backlog_excess_hrs` uses the last window's value (non-increasing across a run, so the last window is the
strictest test — unchanged selection rule). `due_excess_hrs` uses the maximum across the run's windows — the
worst window is what a leader acts on; no window index is added.

- [ ] **Step 4: Run and confirm the test passes**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=FactsBuilderTest
```

Expected: PASS.

- [ ] **Step 5: Update the rebalancing-advice skill's rule 8**

In `server/forecast-core/src/main/resources/skills/whf-rebalancing-advice/SKILL.md`, rule 8 currently reads:

```
8. `rebalancing_candidates.deadline_pressed` is the strongest case for moving work: those hours cannot be deferred, so either they move or the deadline slips. Say how many with `due_excess_hrs`. A positive `overdue_hrs` has already slipped and comes first of all — it is not a coming risk, it is a warning that is already true. `rebalancing_candidates.backlog_pressed` is the weaker, undated case: real pressure, but nothing is due by name yet.
```

Change it to state the entry shape:

```
8. `rebalancing_candidates.deadline_pressed` is the strongest case for moving work: those hours cannot be deferred, so either they move or the deadline slips. Each entry carries `member_id`, `name` and `due_excess_hrs` — say how many with that figure. A positive `overdue_hrs` has already slipped and comes first of all — it is not a coming risk, it is a warning that is already true. `rebalancing_candidates.backlog_pressed` is the weaker, undated case: real pressure, but nothing is due by name yet; each entry carries `member_id`, `name` and `backlog_excess_hrs`.
```

- [ ] **Step 6: Confirm `SkillTextsTest` and `FactsToolsTest` still pass unchanged**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=SkillTextsTest,FactsToolsTest
```

Expected: PASS. `SkillTextsTest` asserts only that the vocabulary (`backlog_excess_hrs`, `due_excess_hrs`,
`overdue_hrs`, `backlog_pressed`, `deadline_pressed`) appears in the skill texts — all five survive the
rewrite. `FactsToolsTest` asserts the `rebalancing_candidates` node's key set (`overloaded`, `underloaded`,
`backlog_pressed`, `deadline_pressed`), unaffected by entry shape.

- [ ] **Step 7: Full module test run**

```bash
cd server && rm -rf forecast-core/target/surefire-reports && mvn -B -q verify
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/facts/FactsBuilder.java \
        server/forecast-core/src/test/java/com/workloadhub/forecast/facts/FactsBuilderTest.java \
        server/forecast-core/src/main/resources/skills/whf-rebalancing-advice/SKILL.md
git commit -m "fix(facts): give backlog_pressed and deadline_pressed ids and hours

Closes docs/backlog.md's open item, one field further than it asked.
fullName() is not unique, so two members of one name previously collapsed
into a single bare-name entry; NumberVerifier.memberNumbers() scopes facts
by member_id, so a name-only list gave Copilot no id to scope a verified
number by. Each entry now carries member_id, name and its own pressure
figure (backlog_excess_hrs from the run's last window; due_excess_hrs, the
maximum across the run's windows)."
```

(Bash timeout: at least 400000 ms.)

---

### Task 4: horizon-neutral FORECAST progress label

`RunProgressTracker`'s `FORECAST` phase label hardcodes "the next two weeks", wrong whenever
`whf.forecast.windows` is not 2. Spec section 6.

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/service/RunProgressTracker.java:33`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/service/RunProgressTrackerTest.java:58`

**Interfaces:**
- Consumes/produces: nothing new; `PHASE_LABELS` stays a `static final Map<String, ProgressLabel>` and
  `phaseLabel(String)` stays a public static method. No caller changes.

- [ ] **Step 1: Update the failing-first test**

In `RunProgressTrackerTest.java:58`, the phase table's `FORECAST` entry:

```java
                {"FORECAST", "predicting the next two weeks", "prévision des deux prochaines semaines"},
```

becomes:

```java
                {"FORECAST", "predicting the coming weeks", "prévision des semaines à venir"},
```

- [ ] **Step 2: Run and confirm it fails**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=RunProgressTrackerTest
```

Expected: FAIL — the test now expects text the production map does not yet produce.

- [ ] **Step 3: Update the production label**

In `RunProgressTracker.java:33`:

```java
            Map.entry("FORECAST", new ProgressLabel("predicting the next two weeks", "prévision des deux prochaines semaines")),
```

becomes:

```java
            Map.entry("FORECAST", new ProgressLabel("predicting the coming weeks", "prévision des semaines à venir")),
```

- [ ] **Step 4: Run and confirm it passes**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=RunProgressTrackerTest,RunProgressTrackerPropertyTest
```

Expected: PASS.

- [ ] **Step 5: Full module test run**

```bash
cd server && rm -rf forecast-core/target/surefire-reports && mvn -B -q verify
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/service/RunProgressTracker.java \
        server/forecast-core/src/test/java/com/workloadhub/forecast/service/RunProgressTrackerTest.java
git commit -m "fix(service): make the FORECAST progress label horizon-neutral

whf.forecast.windows accepts one to six windows; the hardcoded 'next two
weeks' was wrong at five of its six settings. The window count is not
plumbed in — PHASE_LABELS stays a static table and the real horizon is
already in the run result."
```

(Bash timeout: at least 400000 ms.)

---

### Task 5: fix the sample host's Copilot status print order

`HostExample.copilot` reads `copilotStatus` (prints "no GitHub token stored") *before* saving a token supplied
via `WHF_EXAMPLE_GH_TOKEN`, so a `--narrate` run prints a false "no token" status immediately before narrating
successfully. Spec section 7. **Not covered by the gate** — `HostExample.java` is compiled by the source
launcher, not Maven; checked by hand only.

**Files:**
- Modify: `server/examples/HostExample.java` (the `copilot(UUID, boolean, String)` method, around lines
  375–397)

**Interfaces:** none — purely reorders two existing statements inside one private method.

- [ ] **Step 1: Reorder the token save above the status read**

Current shape (`copilot` method):

```java
        private void copilot(UUID runId, boolean narrate, String language) {
            CopilotStatus status = service.copilotStatus(user);
            System.out.printf("%ncopilotStatus(%s): token %s, runtime %s (%s), authenticated %s, login %s%n  %s%n", name(user), status.hasToken(),
                    status.runtimeAvailable(), status.runtimeVersion(), status.authenticated(), status.login(), status.message());

            // Already narrated? A page that reloads reads the stored narrative instead of paying for a new one.
            Optional<NarrativeResult> stored = service.narrative(runId, language);
            System.out.println("narrative(" + language + ") already stored: " + stored.map(n -> n.status().toString()).orElse("no"));

            if (!narrate) {
                System.out.println("  (pass --narrate to call the model; it needs WHF_TOKEN_KEY and a token saved for this user)");
                return;
            }
            String token = System.getenv("WHF_EXAMPLE_GH_TOKEN");
            if (token != null && !token.isBlank()) {
                tokens.save(user, token.trim());      // the host does this once, from its settings page
            }
            NarrativeResult result = service.narrate(new NarrativeRequest(runId, user, language, null));
```

Change to hoist the save block above the status call:

```java
        private void copilot(UUID runId, boolean narrate, String language) {
            String token = System.getenv("WHF_EXAMPLE_GH_TOKEN");
            if (token != null && !token.isBlank()) {
                tokens.save(user, token.trim());      // the host does this once, from its settings page
            }
            CopilotStatus status = service.copilotStatus(user);
            System.out.printf("%ncopilotStatus(%s): token %s, runtime %s (%s), authenticated %s, login %s%n  %s%n", name(user), status.hasToken(),
                    status.runtimeAvailable(), status.runtimeVersion(), status.authenticated(), status.login(), status.message());

            // Already narrated? A page that reloads reads the stored narrative instead of paying for a new one.
            Optional<NarrativeResult> stored = service.narrative(runId, language);
            System.out.println("narrative(" + language + ") already stored: " + stored.map(n -> n.status().toString()).orElse("no"));

            if (!narrate) {
                System.out.println("  (pass --narrate to call the model; it needs WHF_TOKEN_KEY and a token saved for this user)");
                return;
            }
            NarrativeResult result = service.narrate(new NarrativeRequest(runId, user, language, null));
```

The save now runs unconditionally (matching its own comment — "the host does this once, from its settings
page") rather than only inside the `--narrate` branch; `WHF_EXAMPLE_GH_TOKEN` is opt-in, so a `--narrate`-less
path gains a truthful status rather than a side effect anyone would be surprised by.

- [ ] **Step 2: Check by hand — this is not covered by the gate**

Inside the development container, with a real or test GitHub token:

```bash
WHF_EXAMPLE_GH_TOKEN=<token> bash server/examples/run-host-example.sh --team <team-uuid> --narrate --lang en
```

Expected: `copilotStatus` prints `token true` (not `false`) before narration runs, when a token is supplied.
Record the observed output in the plan's notes — there is no automated assertion for this task.

- [ ] **Step 3: Commit**

```bash
git add server/examples/HostExample.java
git commit -m "fix(examples): save the token before printing copilotStatus

The observed 2026-09-14 run printed 'no GitHub token stored' immediately
before narrating successfully — a print-order bug, not an authentication
bypass. HostExample.java is compiled by the source launcher and not by
Maven, so this is checked by hand, not by the gate."
```

(Bash timeout: at least 400000 ms — the pre-commit hook still runs the Maven gate for the rest of the repo
even though this file is outside it.)

---

### Task 6: `Accuracy` — no team row over zero scored rows

`Accuracy.evaluate` unconditionally adds a team-scope `AccuracyScore`, so an empty `rows` list produces a row
of NaNs. Spec section 8.

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/eval/Accuracy.java` (around line 70)
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/AccuracyScore.java` (javadoc)
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/eval/AccuracyTest.java:131-139`
  (`anEmptyRangeGivesNaNScoresAndNoRows`)
- Check (expected unchanged): `server/examples/HostExample.java`'s `accuracy(UUID)` method (lines ~344–364) —
  confirm it does not assume `scores().get(0)` is always the team row.

**Interfaces:**
- Consumes: `AccuracyResult(UUID teamId, LocalDate from, LocalDate to, LocalDateTime evaluatedAt, List<AccuracyRow>
  current, List<AccuracyScore> scores, int nonWorkingDays)` (existing record).
- Produces: `AccuracyResult.scores()` may now be an **empty list** when `rows` is empty at evaluation time —
  this is a new, narrower contract than "always at least the team row"; `HostExample` must not assume index 0
  exists.

- [ ] **Step 1: Update the failing-first test**

Rename and rewrite `AccuracyTest.anEmptyRangeGivesNaNScoresAndNoRows` (it no longer gives NaN scores — it
gives no scores at all):

```java
    @Test
    void anEmptyRangeGivesNoScoresAndNoRows() {
        AccuracyResult r = Accuracy.evaluate(TEAM, MON, TUE, SAT, List.of(), List.of(), new TreeMap<>());
        assertTrue(r.current().isEmpty());
        assertTrue(r.scores().isEmpty(), "nothing was scored, so there is no team row either");
        assertEquals(0, r.nonWorkingDays());
    }
```

- [ ] **Step 2: Run and confirm it fails**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=AccuracyTest#anEmptyRangeGivesNoScoresAndNoRows
```

Expected: FAIL — `r.scores()` currently has size 1 (the all-NaN team row).

- [ ] **Step 3: Guard the team row**

In `Accuracy.java`, change:

```java
        List<AccuracyScore> scores = new ArrayList<>();
        scores.add(score(TEAM, teamId.toString(), rows, logged));
```

to:

```java
        List<AccuracyScore> scores = new ArrayList<>();
        if (!rows.isEmpty()) {
            scores.add(score(TEAM, teamId.toString(), rows, logged));
        }
```

- [ ] **Step 4: Reword `AccuracyScore`'s javadoc to distinguish the two NaN cases**

In `AccuracyScore.java`:

```java
/**
 * Accuracy over a set of rows: {@code scope} is "team", "member" or "lead"; {@code key} the team id, the user
 * id or the lead. A scope with zero rows is not scored at all and produces no entry in
 * {@link com.workloadhub.forecast.api.AccuracyResult#scores()} — the member and lead scopes are always built
 * from non-empty groups, so only the team scope can be absent this way. {@code mase} is scored on the
 * {@code maseN} rows whose member has a log on the same weekday seven days earlier, and is NaN whenever there
 * are none, even for a scope that was otherwise scored ({@code n > 0}).
 */
```

- [ ] **Step 5: Run and confirm the test passes**

```bash
cd server && mvn -q -pl forecast-core test -Dtest=AccuracyTest,AccuracyPropertyTest
```

Expected: PASS. The property tests build with `@IntRange(min = 1, ...)` members and non-empty hour lists, so
they always produce at least one row and are unaffected by this guard.

- [ ] **Step 6: Verify `HostExample` does not assume `scores().get(0)` is the team row unconditionally**

Read `server/examples/HostExample.java`'s `accuracy(UUID team)` method: it iterates `a.scores()` with
`s.scope().equals("team")` rather than indexing `scores().get(0)` directly, so no change is needed there. If a
future read of the file finds an unguarded `scores.get(0)`, add a guard printing the existing "no scored day
in range" message instead.

- [ ] **Step 7: Full module test run**

```bash
cd server && rm -rf forecast-core/target/surefire-reports && mvn -B -q verify
```

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/eval/Accuracy.java \
        server/forecast-core/src/main/java/com/workloadhub/forecast/api/AccuracyScore.java \
        server/forecast-core/src/test/java/com/workloadhub/forecast/eval/AccuracyTest.java
git commit -m "fix(eval): stop Accuracy emitting an all-NaN team row over zero rows

Accuracy.evaluate added the team score unconditionally, so an empty
evaluation range produced a row of NaNs. Guarded on !rows.isEmpty(); the
member and lead scores were already built from non-empty groups and are
unaffected. mase keeps its NaN when maseN == 0 and n > 0 -- that row was
scored, and a MASE with no lag-7 comparison is genuinely undefined; the two
cases are now distinguished in AccuracyScore's javadoc."
```

(Bash timeout: at least 400000 ms.)

---

### Task 7: false documentation, four statements

Each corrected against the repository or the remote as verified in the spec (section 9). Documentation only —
no test changes.

**Files:**
- Modify: `server/README.md:117` (the seed recipe and its surrounding sentences)
- Modify: `docs/backlog.md:9-10` and `docs/backlog.md:50-52` (the archive-branch-as-tag error)
- Modify: `CLAUDE.md` ("Where the project stands" section, the "`main` has not been fast-forwarded yet"
  sentence, and a new closing paragraph once this sweep lands)

- [ ] **Step 1: `server/README.md` — correct the seed recipe**

Replace the line:

```
$X seed --synthetic --users 40 --weeks 26 --seed 7 --end 2026-09-06 --out /tmp/synthetic.json
```

with:

```
$X seed --synthetic --users 120 --weeks 52 --seed 7 --end 2026-09-06 --out /tmp/synthetic.json
```

Immediately after the `# 4. or a synthetic ...` comment line, add:

```
# team size is emergent (ReferenceData.syntheticUsers: perDept = n / 9, capped at 10 members per team), so a
# run needs roughly eighty users before any team reaches eight members -- a team of three can never show
# rebalancing. Several teams tie for the largest size; not every one of them has a forecast-overloaded
# member, so try more than one before concluding rebalancing isn't showing up. The population's mean
# member-week should land in the high twenties to low thirties against the 44 h capacity, with some weeks
# well above it; a seed averaging in the low twenties predates the 2026-09-15 weekly-supply correction and
# is stale.
```

- [ ] **Step 2: `docs/backlog.md:9-10` and `:50-52` — correct the archive-branch-as-tag error**

At lines 9-10, replace the tag claim and its worktree command:

```
`git worktree add ../whf-archive archive/python-desktop-v1`.
```

with the branch form:

```
`git fetch origin archive/python-desktop-v1 && git worktree add ../whf-archive origin/archive/python-desktop-v1`.
```

and correct the surrounding sentence to call it a branch, not a tag.

At lines 50-52, replace:

```
The branch of that name lived only on the remote deleted on 2026-09-12, so `archive/python-desktop-v1` is
now a **tag** at `3c6f836`, the last commit on `dev` that still holds those trees (`d985a56` removed them).
It differs from the old branch tip `5c69bf6` only in that `service/tests/test_parity_compare.py` had by then
moved to `server/tools/tests/` to stay in the live repository; the `whf` CLI the parity procedure runs is
identical. The parity
```

with:

```
The branch survived the remote deletion of 2026-09-12 and the remote was restored on 2026-09-14, so
`archive/python-desktop-v1` **is a branch on the remote**, not a tag — `git ls-remote` shows
`refs/heads/archive/python-desktop-v1` at `5c69bf6`, and the repository has no tags at all. The distinguishing
detail this passage used to offer -- that a tag differed from `5c69bf6` only by
`service/tests/test_parity_compare.py` having moved to `server/tools/tests/` -- is now moot twice over: no
such tag exists, and the parity procedure including `server/tools/tests/` was retired on 2026-09-14. The parity
```

(Keep the paragraph's continuation after "The parity" intact — only the sentence(s) before it change.)

- [ ] **Step 3: `CLAUDE.md` — correct the stale fast-forward claim**

Replace:

```
had never been collecting. The gate stands at 409 tests, 0 failures, 13 skipped. `main` has not been
fast-forwarded yet. The plan's closing notes record the rest; `docs/backlog.md` holds what was left open.
```

with:

```
had never been collecting. The gate stands at 409 tests, 0 failures, 13 skipped. `main` was fast-forwarded to
`dev` afterward -- `git ls-remote --heads origin` shows both at `765aa9f`. The plan's closing notes record the
rest; `docs/backlog.md` holds what was left open.
```

- [ ] **Step 4: `CLAUDE.md` — add the closing paragraph for this sweep, once it lands**

This step is deferred to the end of execution (after Task 8 and the whole-branch review), not done now: add
a paragraph to "Where the project stands" summarizing the defect sweep (task 1a/1b's seed and database
correction, task 2's `NumberVerifier` fixes, task 3's pressure-list shape change, tasks 4-6's cosmetic fixes,
and the new gate count) once the branch's fix wave is complete and the gate is green. Do this as the last step
of the whole-plan execution, not as part of this task's own commit.

- [ ] **Step 5: Commit (steps 1–3 only; step 4 is deferred)**

```bash
git add server/README.md docs/backlog.md CLAUDE.md
git commit -m "docs: correct the seed recipe, the archive branch, and the ff status

server/README.md's seed recipe (--users 40) produced the database this sweep
replaces; docs/backlog.md called archive/python-desktop-v1 a tag twice, when
git ls-remote shows it is a branch and the repository has no tags at all;
CLAUDE.md's 'main has not been fast-forwarded yet' is stale since both
branches sit at 765aa9f."
```

(Bash timeout: at least 400000 ms — even a docs-only commit runs the pre-commit gate.)

---

### Task 8: remove two dead backlog entries

Both name code deleted on 2026-09-14; both concerns are already resolved or moot, per the spec's verification
(section 10). Documentation only.

**Files:**
- Modify: `docs/backlog.md` (delete the entry at line 322, "Level B of the evaluation report has no naive
  baseline", and the entry starting at line 351, "The evaluation harness applies a shorter history gate than a
  run does")

**Interfaces:** none.

- [ ] **Step 1: Delete "Level B of the evaluation report has no naive baseline"**

Remove the bullet (currently at `docs/backlog.md:322`, inside the "Open after the weekly hours forecast
landed" entry's sub-list):

```
  - **Level B of the evaluation report has no naive baseline.** `open_only_mae` was dropped because it
    read `DemandRow::openHours`, deleted with the open/new/planned split, and the 2026-09-13 design names
    no replacement. Level B now reports `mae`, `bias`, `overload_precision` and `overload_recall` with
    nothing to compare `mae` against. Last week's logged hours per member-week is the obvious candidate.
```

Its subject (`Report`, Level B) was removed with the evaluation harness on 2026-09-14, and the concern is
already met: `AccuracyScore` carries `mase`/`maseN`, scored against a lag-7 naive baseline — exactly the
"last week's logged hours per member-week" candidate the entry proposed.

- [ ] **Step 2: Delete "The evaluation harness applies a shorter history gate than a run does"**

Remove the whole bullet, from `- **The evaluation harness applies a shorter history gate than a run does
(2026-09-14). Important; go back to this.**` through its closing sentence ("Landed unresolved with task 8 of
`docs/superpowers/plans/2026-09-14-weekly-hours-forecast.md`.").

The divergent caller (`eval/Harness`) is gone; `Backtest.minHistoryWeeks` has exactly one caller
(`Backtest.origins`), which has exactly one production caller (`ForecastRunner.prepare`, passing
`Horizon.maxHorizon(origin, windows)`), so there is one gate and nothing to diverge from. `accuracy(teamId,
from, to)` scores forecasts already stored and computes no history gate of its own.

- [ ] **Step 3: Record both deletions under the evaluation-removal note**

Add one line near the existing "Evaluation removed (2026-09-14)" entry in `docs/backlog.md`'s "Java
migration" section, noting that these two follow-on items were closed on this sweep's date because their
subject code no longer exists.

- [ ] **Step 4: Commit**

```bash
git add docs/backlog.md
git commit -m "docs: close two backlog entries whose subject code is already gone

Both named code deleted on 2026-09-14 (the evaluation harness and its
Level B report). AccuracyScore's lag-7 mase already gives the naive
baseline the first entry asked for; the harness's shorter history gate had
exactly one caller, now deleted, so there is nothing left to diverge from."
```

(Bash timeout: at least 400000 ms.)

---

## After all eight tasks: end-to-end acceptance (spec section 11.2)

Not a task with a commit of its own — the plan's acceptance gate, run once all of Tasks 1–8 are on `dev`.

- [ ] Wipe `server/forecast-core/target/surefire-reports`, run `bash scripts/check.sh` inside the development
  container, and read the counts from `TEST-*.xml`. Expect at or above 409 tests, 0 failures.
- [ ] On the database rebuilt in Task 1b, against the specific team Task 1b's Step 5 confirmed satisfies
  assertion 4 (team `8caab1cf-ed99-48e7-8819-6bf63892c02d` on the run recorded during this plan's execution —
  overloaded: Hind Naciri 18 (8.1h), Karim Fassi 23 (2.7h); underloaded: Hind Haddad 24 (31.7h spare), Nadia
  Ziani 15 (71.0h spare) and others; re-derive if the database is rebuilt again with a different seed/config):

  ```bash
  bash server/examples/run-host-example.sh --team 8caab1cf-ed99-48e7-8819-6bf63892c02d --narrate --lang en
  ```

  Two requirements: **zero `UNVERIFIED` items** (proves Task 2), and **a non-empty `rebalancing` list, with
  `backlog_pressed` or `deadline_pressed` entries carrying ids and hours** (proves Tasks 1a, 1b and 3
  together). A run still reporting `overloaded members: none` means Task 1's assertion 4 was not actually met
  and the sweep is not finished, whatever the gate says.
- [ ] Complete Task 7's Step 4 (the `CLAUDE.md` closing paragraph) once the above both pass.
- [ ] Offer the fast-forward of `main` to the owner; do not run `scripts/release.sh` without asking first.

## Out of scope (unchanged from the spec, section 12)

The rule that Copilot never produces a forecast number; capping demand by capacity; restoring CI triggers; the
`archive/python-desktop-v1` branch itself; the real WorkloadHub export; any provider key other than the user's
own GitHub Copilot token; the forecasting model, feature matrix, and facts contract beyond the two lists in
Task 3; every seed parameter except `WorkFamily.weeklyHours`; the team-size distribution in
`ReferenceData.syntheticUsers`.
