# Post-retarget defect sweep

Date: 2026-09-15
Status: designed, approved in brainstorming, not yet planned

Amended the same day, after an audit of the seed generator found a second cause of the missing overload:
the weekly work supply was never raised to match the 44 h capacity. Task 1 split into 1a (the seed
generator) and 1b (the database); sections 1, 2, 3, 9, 11, 12 and 13 changed with it.

Amended again on 2026-09-15, from the whole-branch review that closed out the plan built from this spec.
Three statements below were found false by execution rather than by re-reading: section 3.1's claim that
only two places name `WorkFamily.weeklyHours` missed a third (`RhythmTest.java`, found only when it failed
the gate after the constants changed — see the plan's Task 1a, Step 4b); section 4.1's literal instruction
to widen the `NUMBER` lookbehind to `(?<![\w.])` in both alternatives, if followed as written, breaks the
existing `numbersGluedToAWordAreExtracted` test and stops checking numbers glued to words, which is exactly
the weakening this section's own risk warning cautions against — the plan's Task 2 deviates from this
instruction and guards the sign only, leaving the digit lookbehind untouched (see the plan's "Deviation from
the spec" note); and every occurrence below of "the mid thirties" describing the population-wide mean
member-week is corrected to "high twenties to low thirties", the figure actually measured
(`WorkFamilyPropertyTest`'s fixture and the rebuilt database both land around 28-29 h) — the higher figure
conflated a typical individual full-time non-leader member's own logged hours (~36-37h, this section's own
per-member arithmetic) with the population-wide mean, which the same arithmetic never claimed to be: team
leaders logging at half rate, season dips, ramp-up weeks and absences pull the population mean well below
any single member's typical figure.

## 1. Why

A live run of `server/examples/run-host-example.sh --team 448b095d-e164-4785-a5fa-431760ea941c --narrate
--lang en` on 2026-09-14 finished successfully and produced a report that was wrong in ways the gate cannot
see. It reported `overloaded members: none`, an empty `rebalancing` list, empty `suggested_adjustments`, and
66.4 h of demand against 240 h of capacity — about 28 % utilisation for a team of three. It also reported six
`UNVERIFIED` numbers, every one of which turned out to be a true statement the verifier could not confirm.

None of that is a forecasting error. Four separate defects converged:

1. Two separate causes make overload unreachable, one in the data and one still in the code.

   The experiment database predates the weekly-hours retarget. Every one of its 722 `user_capacity` rows
   carries `base_capacity_hrs = 40.0`, and no member-day in 1591 exceeds 8.0 h. This is exactly the failure
   the retarget design predicted for stale data (CLAUDE.md, section 22 of the 2026-09-13 design): with a
   40 h capacity row and an 8 h daily ceiling, overload is arithmetically unreachable. The 40-user seed also
   produced teams of three, so a team can never hold both an overloaded and an underloaded member, which is
   what `rebalancing` needs.

   Rebuilding the database does not fix it, because the seed generator's **weekly work supply** was never
   raised to match the corrected 44 h capacity. Section 22 of the 2026-09-13 design lifted the *daily*
   ceiling and landed in `WorkStyle`; `WorkFamily.weeklyHours`, which sets the supply, still carries the
   values of the original seed commit `eeba100`. Section 3.1 gives the arithmetic and the measurement. A
   re-seed alone would therefore reproduce the symptom on fresh data, so the seed is corrected first.
2. `NumberVerifier` marks true numbers unverified. Three independent causes, all reproducible against the
   observed narrative.
3. The `rebalancing_candidates.backlog_pressed` and `deadline_pressed` lists hold bare names, so Copilot
   cannot join a pressed member to their entry and the verifier cannot scope their hours.
4. Three smaller defects: a hardcoded "two weeks" progress label, a misleading `copilotStatus` line in the
   sample host, and an all-NaN accuracy score row over zero scored rows.

Alongside these, four documentation statements are false and two backlog entries name code deleted on
2026-09-14.

This document covers all of it as one sweep. It changes no forecasting logic, no model, and no rule in
CLAUDE.md's "Hard rules".

## 2. Shape of the work

Nine tasks, one plan, landing on `dev` as the convention requires, then one review over the accumulated
commits, one fix wave, the gate green by hand in the development container, and finally the fast-forward of
`main`. Task 1 splits: **1a** corrects the seed generator's weekly work supply, **1b** rebuilds the
experiment database from the corrected seed. In that order, and both before anything else — 1b produces the
database the rest is observed on, and 1a is what makes that database able to show overload at all.
Tasks 2 to 6 are independent of each other. Tasks 7 and 8 are documentation.

Severity, stated plainly so the plan does not treat these as equals: task 1 is the only one making the
system look broken today; task 2 is the only one touching a safety property; tasks 4 to 6 are cosmetic;
tasks 7 and 8 are hygiene.

## 3. Task 1: the seed's weekly supply, then the database

### 3.1 Task 1a: raise the weekly work supply

`WorkFamily.weeklyHours` is the seed's weekly work supply, and it is sized for a world that no longer
exists. `git log` on the file returns one commit — `eeba100`, the original seed — while the retarget's
changes landed in `WorkStyle` (`c57bed4`, `b32e487`). The daily ceiling was corrected; the supply was not.

The arithmetic against a 44 h capacity:

| Stage | Source | Typical | Best case |
| --- | --- | --- | --- |
| family supply | `WorkFamily.weeklyHours` | 30 | 32 |
| × personal factor | `Rhythm.baseFactor`, clamped to [0.5, 1.3] | 1.0 | 1.3 |
| × season, ramp, availability | `Rhythm.target` | 1.0 | 1.0 |
| × event week | `EVENT_FACTOR`, ~6 weeks in 52 | 1.0 | 1.4 |
| = weekly target | `Rhythm.target` | **30 h** | 58 h |
| × discipline | `WorkStyle`, `clamp(0.95 + 0.08·g, 0.70, 1.00)` | 0.93 | 1.00 |
| = logged | the model's target | **≈28 h** | — |

A typical member sits at 65 % of capacity. Measured on the current database: 480 member-weeks, average
22.9 h, **zero above 40 h and zero above 44 h**, with 49 member-weeks pinned at exactly 40.0 against 5 to 7
at neighbouring values — the old seed's 8.0 h × 5 clamp, which confirms the database is stale as well.

This matters more for a forecast than for the history. The model predicts logged hours per member-week from
past logged hours. On a history averaging 28 h it predicts 28 h; `EVENT_FACTOR` weeks are ~11 % of the
history and there is no event-week feature to anticipate them, so the model regresses to the mean and
predicted overload is structurally zero. **More users cannot fix this** — a larger population multiplies
member-weeks and leaves per-member utilisation at 65 % exactly.

The correction is the constants, scaled so a median member sits near 84 % of capacity instead of 65 %:

| Family | From | To |
| --- | --- | --- |
| CALIBRATION | 32 | 42 |
| SYSTEMS, DESIGN, ELECTRONICS, VALIDATION, UNKNOWN | 30 | 39 |
| DATA | 28 | 36 |
| SUPPORT | 20 | 26 |
| COORDINATION | 16 | 21 |

The mean is deliberately **not** centred on 44: at 39 h the existing `baseFactor` spread of [0.5, 1.3] does
the work on its own — a median member logs ≈37 h and is healthy, a high-factor member crosses capacity, a
low-factor member and a team leader (`base` × 0.5) become genuine rebalancing targets, and event weeks push
more members over. Centring on 44 would leave half of every team permanently overloaded, which is not a
dataset anyone can read.

Three properties of the existing code make this a safe lever rather than a tuning exercise:

- **The ceiling is structural.** `Rhythm.target` sets only the Poisson *arrival rate*
  (`arrivals = Poisson(target / meanEstimate)`), and `WorkQueue.logDay` bounds each log by
  `w.actual - w.logged`. Hours are capped by presence, not by the constants:
  `SeedGeneratorTest.java:141-147` already asserts no member-week exceeds
  `CapacityWriter.BASE_HOURS × WorkStyle.MAX_OVERTIME_FACTOR` = 59.4 h, and that bound is unaffected by this
  change. Raising the supply cannot make a week run away.
- **Overload arrives through the intended mechanism.** Five present days at `weekdayWeights` summing to 5
  give exactly 44 h of presence, so a week can only exceed 44 h on an overtime day — `WorkStyle.hoursOn`,
  fired at 0.30 a day for the 17 % "heavy" members and 0.06 for the rest. Overload becomes a property of
  members who run long and record most of it, which is what it is.
- **The surplus becomes backlog, not waste.** Arrivals that outrun presence stay in the queue, so the same
  change is what makes `backlog_excess_hrs` and `due_excess_hrs` non-zero — the facts tasks 2 and 3 need in
  order to be observable at all.

Nothing else reads `weeklyHours` in production code. Three places name it, not two as first thought here:
`Rhythm.java:71`; the `@CsvSource` at `WorkFamilyTest.java:46`, which pins `CALIBRATION, 12, 32`,
`COORDINATION, 6, 16` and `SUPPORT, 4, 20`; and `RhythmTest.java`'s
`targetIsBaseTimesFactorsAndLeadersAreHalved`, which also hardcodes `CALIBRATION.weeklyHours` (32) as a
literal bound check on `Rhythm.base(...)` — found only when execution ran it against the corrected
constants and it failed the gate (plan Task 1a, Step 4b). All three of the families `WorkFamilyTest` pins are
among the ones this task changes, and `RhythmTest`'s fixture person is built directly from
`WorkFamily.CALIBRATION`, so all three tests change with the constants.

#### The guard

A jqwik property test, `seed/WorkFamilyPropertyTest.java` (never `*Properties.java`, which surefire
silently skips), asserting over a seeded history that member-weeks span capacity in both directions:

- at least one member-week above `CapacityWriter.BASE_HOURS`;
- at least one member-week below 70 % of it;
- both present within a single team.

This is the missing lower half of the bracket `SeedGeneratorTest` already establishes from above. Together
they pin the seed's whole purpose: a dataset that can show overload and spare capacity side by side. The
assertion is written against `CapacityWriter.BASE_HOURS` rather than a literal 44, so the next capacity
change fails the test instead of silently reintroducing this mismatch — which is exactly how it arose.

The test needs enough population and horizon for the narrow overload band to be reached reliably; the plan
records the fixture size it settled on and why, rather than shrinking the fixture until it is fast.

### 3.2 Task 1b: rebuild the experiment database

This task produces no diff. Its output is a database under the box's `/data` mount and a recorded set of
assertion results. It runs only after 1a is green.

The old database is moved aside rather than deleted, so the 40 h / 8 h failure can be shown side by side
rather than described. The new database then occupies the default path, so a forgotten `--db` can no longer
silently reproduce the defect.

Inside the box (`bash scripts/devbox.sh shell`):

```bash
bash server/tools/experiment.sh init-db --db /data/whf-v2.db
bash server/tools/experiment.sh seed --synthetic --users 120 --weeks 52 --seed 7 --end 2026-09-06 --out /data/seed-v2.json
bash server/tools/experiment.sh import --db /data/whf-v2.db /data/seed-v2.json
mv /data/workloadhub.db /data/workloadhub-40h.db
mv /data/whf-v2.db /data/workloadhub.db
```

`seed` takes no `--db`: it writes a JSON export only, which is why the recipe is three commands and not one.
The flags above are the driver's own (`Experiment.java:98`, `--out --export --users --weeks --end --seed
--format` with `--synthetic` and `--force` as switches).

#### Why 120 users

Team size is emergent, not configurable. `ReferenceData.syntheticUsers` computes
`perDept = Math.max(3, n / DEPTS.size())` with `DEPTS.size() == 9`, and caps a team at ten members through
`isManager = (i - 1) % 10 == 0` — one manager plus at most nine reports.

- `n = 40` gives `perDept = 4`: a department of four yields one manager with two reports, a team of three.
  That is the team the observed run used, and it is why the old database has 29 teams averaging 1.4 members.
- `n = 120` gives `perDept = 13`: each department yields one team of ten plus a runt of two.

Ten-member teams are the point. Overload and underload can only coexist in one team — and therefore
`rebalancing` can only be non-empty — when the team is large enough to hold both.

There is no team-size knob to set, so the task verifies the distribution it got rather than assuming one.

### 3.3 Assertions

The task is not done until all four pass, and each result is recorded in the plan's task notes. The
numbering is referenced from sections 9 and 11.2 and does not change.

1. Capacity corrected:
   ```sql
   SELECT DISTINCT base_capacity_hrs FROM user_capacity;
   ```
   Must return 44.0 and nothing else. The old database returns 40.0 on all 722 rows.

2. Overtime and overload reachable — the daily half and the weekly half of section 3.1:
   ```sql
   SELECT count(*) FROM (SELECT user_id, log_date, sum(hours) h FROM time_logs GROUP BY user_id, log_date)
   WHERE h > 8.8;

   SELECT count(*) AS over_44, avg(h) AS mean_week FROM (
     SELECT user_id, strftime('%Y-%W', log_date) wk, sum(hours) h
     FROM time_logs GROUP BY user_id, wk) WHERE h > 44.0;
   ```
   Both counts must be above zero. The `mean_week` this query computes is filtered to `h > 44.0`, so it is
   necessarily above 44 by construction and not a meaningful check on its own; also run the *unfiltered*
   population mean (the same query without its outer `WHERE h > 44.0`) and expect it in the high twenties to
   low thirties, not the mid thirties — the population mean is pulled down from a typical full-time member's
   own ~36-37h (this section's per-member arithmetic, above) by team leaders logging at half rate, season
   dips, ramp-up weeks and absences. The old database returns 0 above 8.0 h across 1591 member-days, and 0
   above 44 h across 480 member-weeks at an unfiltered population mean of 22.9 h. This assertion is what
   proves task 1a landed in the data rather than only in the tests.

3. Teams large enough:
   ```sql
   SELECT t.name, count(*) n FROM team_members tm JOIN teams t ON t.id = tm.team_id
   GROUP BY t.id ORDER BY n DESC LIMIT 5;
   ```
   The largest must be at least eight.

4. Both pressures present in one team: a run on the largest team yields at least one overloaded member and
   at least one underloaded member. This is the real acceptance criterion — it is what makes tasks 2 and 3
   observable at all.

### 3.4 Where this plan can branch

The branch this section originally guarded — "the seed still caps logging, so task 1 is a code fix rather
than a data refresh" — has already been taken: reading the code found it before running the seed, and it is
task 1a. Two branches remain.

If assertion 2's **weekly** count is still zero after 1a, the supply is not the only thing holding overload
down and the next suspect is `discipline`, whose haircut makes a member's effective ceiling
`44 × discipline`. Do not reach for it by reflex: raising `DISCIPLINE_MIN` narrows the estimate-versus-logged
gap the seed exists to make separable (`WorkStyle`'s class comment), so it is a design change, not a tuning
knob. Stop and re-scope.

If assertion 4 fails while assertions 1 to 3 pass, overload and underload exist in the population but not
together in one team. That is a `ReferenceData.syntheticUsers` distribution question, not a supply one, and
it is also a stop — tasks 2 and 3 cannot be accepted without it (section 11.2).

## 4. Task 2: NumberVerifier

All six `UNVERIFIED` items in the observed run were verifier artifacts. Three independent causes.

### 4.1 Numbers glued to identifiers

`NumberVerifier.java:37-38` guards both alternatives of `NUMBER` with `(?<![\d.])`. A digit preceded by a
letter or an underscore therefore still opens a number.

Change the lookbehind in both alternatives to `(?<![\w.])`.

**Amended 2026-09-15 (whole-branch review): the actual landed fix deviates from this instruction.** Followed
literally, widening the *digit* lookbehind to `(?<![\w.])` also blocks a number glued to a *word* (not just a
hyphenated code), breaking the existing test `NumberVerifierTest.numbersGluedToAWordAreExtracted`
(`MASE0.91` → 0.91, `demand52.5h` → 52.5 would stop being read at all) — the exact weakening this section's
own risk warning below cautions against. The two problems actually being fixed (the false minus signs from
`EE2-59` and `hours_per_week_13w`) are both caused only by the **hyphen** alternative's sign-guard, not by the
digit lookbehind. The landed fix (plan Task 2) guards the sign alone, in both alternatives, via
`(?:(?<![\w.])-)?` before the digit, and leaves the digit lookbehind exactly as it was, `(?<![\d.])`:

```java
private static final Pattern NUMBER = Pattern.compile(
        "(?:(?<![\\w.])-)?(?<![\\d.])\\d{1,3}(?:[ ,]\\d{3})+(?:[.,]\\d+)?(?![\\w.]*\\d)"
                + "|(?:(?<![\\w.])-)?(?<![\\d.])\\d+(?:[.,]\\d+)?(?![\\w.]*\\d)");
```

This preserves word-glued number extraction while still removing both observed false minus signs. The
measured output below (from following this section's original instruction literally) does not reflect what
was actually implemented; see the plan's "Deviation from the spec" note under Task 2 for the full argument.

Measured on the observed narrative, the current pattern yields:

```
2  59  -30  12.1  -43.0  40.0  -1.228  13  12.35  2  68  2  69
```

With the changed lookbehind it yields:

```
59  30  12.1  43.0  40.0  -1.228  12.35  68  69
```

That removes the stray `2` read out of the team code `EE2`; both false minus signs, because a hyphen
preceded by a word character no longer opens a number and matching resumes at the digit; and `13`, read out
of the *fact name* `hours_per_week_13w` — the trailing guard `(?![\w.]*\d)` passes there because no digit
follows the `w`, while the new lookbehind blocks on the preceding `_`.

### 4.2 Task keys

Add a mask to the pre-clean chain at line 108, after `DATE`:

```java
private static final Pattern TASK_KEY = Pattern.compile("\\b[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)+\\b");
```

Applied on top of 4.1, the observed narrative reduces to exactly `12.1 43.0 40.0 -1.228 12.35`.

The mask was checked against the strings that must survive it: `mae 9.43`, `MASE 0.821`, and
`window 1 (2026-09-07) demand 22.0h vs 40.0h` all pass through unchanged. It sits after `DATE` so an ISO
date is already masked and cannot be partially consumed, though `[A-Z]` cannot start on a date in any case.

### 4.3 Double rounding

`walk` (line 75-88) stores only `round1(v)` and `round0(v)`, and `verify` compares `round1(cited)` (line
240). A fact of 12.347 is stored as 12.3. Copilot writes `12.35`, a legitimate two-decimal rendering of it.
`round1(12.35)` is 12.4 under `HALF_EVEN`. The fact and its own rendering do not meet, and a true statement
is reported unverified.

The fix is to match a cited number against facts rounded to the *cited number's own precision*:

- `NumberToken` gains a `decimals` field: the length of the fractional group, 0 when there is none.
- The scope sets carry raw fact values instead of pre-rounded ones. `walk` adds `v`; the private helper
  `rounded(double...)` becomes `raw(double...)`.
- A new `matches(double cited, int decimals, Set<Double> allowed)` tries three tests in order: `round1`
  equality, `round0` equality, then equality at scale `decimals`.

The first two tests reproduce today's behaviour exactly, so **no number that verifies today can stop
verifying**. The third is a fallback reached only before declaring `UNVERIFIED`, so the change is a pure
widening. Cost is O(|facts|) per cited number instead of a set lookup; facts per run number in the hundreds
and narrative numbers in the tens.

### 4.4 Acceptance, including negative tests

Relaxing this verifier is the one change in this sweep that can quietly weaken the property CLAUDE.md calls
permanent — that every number Copilot writes is checked, and that `UNVERIFIED` is stored and reported, never
promoted. Negative tests are therefore acceptance criteria, not extras. The task is not done without tests
proving each of these still fails:

- a fabricated number present in no fact at any precision;
- a member's text citing another member's hours, so scope isolation survives the widening;
- a cited number that matches a fact only at a precision the fact does not support.

Plus one regression test per cause in 4.1 to 4.3, using the literal strings from the observed run.
`NumberVerifierTest` already carries a `rebalancing_candidates` fixture (line 27) that these extend.

## 5. Task 3: the pressure lists carry ids and hours

`FactsBuilder.rebalancing` builds `overloaded` and `underloaded` as objects but pushes bare names for the
two pressure lists (lines 315 and 318):

```java
if (!rows.isEmpty() && rows.get(rows.size() - 1).backlogExcessHrs() > 0) {
    backlogPressed.add(m.fullName());
}
if (rows.stream().anyMatch(r -> r.dueExcessHrs() > 0)) {
    deadlinePressed.add(m.fullName());
}
```

This is already a known defect: `docs/backlog.md:317-321` records it, asks for exactly `{member_id, name}`,
and names a second consequence beyond the verifier — `fullName()` is not unique, so two members of one name
collapse into a single entry and the leader cannot tell which to act on. **This task closes that backlog
item**, going one field further than it asked.

The entries become objects carrying the id, the name and the hours:

```json
"backlog_pressed":  [{ "member_id": "…", "name": "A. Dupont", "backlog_excess_hrs": 12.4 }],
"deadline_pressed": [{ "member_id": "…", "name": "B. Martin", "due_excess_hrs": 6.1 }]
```

`backlog_excess_hrs` is the last window's value, matching the existing selection rule and its comment:
backlog excess is non-increasing across a run's windows, so the last window is the strictest test.
`due_excess_hrs` is per window, so the entry carries the **maximum across the run's windows** — the worst
window is what a leader acts on. No window index is added; nothing consumes one.

Carrying the hours is what makes `skills/whf-rebalancing-advice` rule 8 coherent. It already instructs
Copilot to "say how many with `due_excess_hrs`", but that value lives only in the member rows today. It is
also what makes `NumberVerifier.memberNumbers()` scope those hours to the right member.

### 5.1 What comes with it

- `skills/whf-rebalancing-advice/SKILL.md` rule 8: describe the entry shape, so Copilot knows the hours are
  on the candidate entry.
- `facts/FactsBuilderTest.java:262-265` asserts `.contains(pressed.fullName())` on the lists. It must look
  for an entry whose `name` matches instead.
- `ai/NumberVerifierTest.java` fixture, for the member-scoping test in 4.4.

### 5.2 What does not change, and why

- **`FactsTools` needs no change.** Lines 129-139 deliberately serve the whole `rebalancing_candidates`
  node rather than whitelisting keys, because whitelisting dropped these two lists once before (2026-09-13
  design, ruling 18.5). `FactsToolsTest:54` asserts the node's key set, which is unaffected by entry shape.
- **`NumberVerifier.memberNumbers()` needs no change.** Lines 144-153 already walk any entry with a textual
  `member_id`. It simply starts working.
- **`SkillTextsTest` is expected to pass unchanged.** Lines 32 and 34 assert only that the vocabulary
  `backlog_excess_hrs`, `due_excess_hrs`, `overdue_hrs`, `backlog_pressed` and `deadline_pressed` appears in
  the skill texts, and all five survive the rewrite. If the rule 8 rewording drops one, restore it rather
  than relax the test.

## 6. Task 4: the progress label

`RunProgressTracker.java:33` hardcodes the horizon:

```java
Map.entry("FORECAST", new ProgressLabel("predicting the next two weeks", "prévision des deux prochaines semaines")),
```

`whf.forecast.windows` accepts one to six, so the label is wrong at five of its six settings.

It becomes horizon-neutral:

```java
Map.entry("FORECAST", new ProgressLabel("predicting the coming weeks", "prévision des semaines à venir")),
```

The window count is not plumbed in. `PHASE_LABELS` is a `static final` table and `phaseLabel(String)` is a
public static method used for stored statuses, so carrying the number would mean a constructor parameter, a
new instance method, a neutral fallback on the static anyway, and every caller of that static touched — for
a cosmetic string whose real horizon is already in the run result.

`service/RunProgressTrackerTest.java:58` pins the old phrase inside the phase table and changes with it.
`ai/SkillTextsTest.java:39` already guards the product skills against `"two-week forecast"`; no equivalent
guard is added for the tracker, because the phase table is asserted whole by the test above.

## 7. Task 5: the sample host's Copilot line

The observed run printed `token false` and "no GitHub token stored" immediately before narrating
successfully. This is not an authentication bypass — `DefaultForecastService.java:289` throws
`TOKEN_MISSING` when no token is stored — it is print order. In `HostExample.copilot`, `copilotStatus` is
read at line 375 while the token is saved at line 389, inside the `--narrate` branch.

Fix: hoist the `WHF_EXAMPLE_GH_TOKEN` save block to the top of `copilot(...)`, above the `copilotStatus`
call. The printed status then describes the token narration will actually use. Saving unconditionally
matches the block's own comment — "the host does this once, from its settings page" — and the environment
variable is explicitly opt-in, so the `--narrate`-less path gains a truthful status rather than a side
effect anyone would be surprised by.

`HostExample.java` is compiled by the source launcher and not by Maven, so **the gate cannot verify this
change**. It is checked by re-running the example, and the plan says so rather than claiming coverage.

## 8. Task 6: accuracy over zero scored rows

`Accuracy.evaluate` adds the team score unconditionally at line 70:

```java
List<AccuracyScore> scores = new ArrayList<>();
scores.add(score(TEAM, teamId.toString(), rows, logged));
```

Over an empty `rows` this emits a row of NaNs. The member and lead scores are built from non-empty groups
and so always have `n > 0`; only the team row is affected.

Fix: guard that one line on `!rows.isEmpty()`. `AccuracyResult.scores` is then empty when nothing was
scored, which is the honest answer, and `nonWorkingDays` still explains why.

`mase` keeps its NaN when `maseN == 0` and `n > 0`: that row *was* scored, and a MASE with no lag-7
comparison is genuinely undefined. `AccuracyScore`'s javadoc ("NaN when empty") is reworded to distinguish
the two cases.

This is a semantics fix, not a serialization one. `AccuracyResult` and `AccuracyScore` are referenced only
by `ForecastService`, `DefaultForecastService`, `Accuracy`, `HostExample` and two tests; there is no `rest/`
package, so accuracy never reaches JSON and NaN's invalidity as JSON does not currently bite.

`eval/AccuracyTest.java` and `eval/AccuracyPropertyTest.java` change with it. `HostExample` must be checked
for an unguarded `scores.get(0)`; the empty range should print the existing "no scored day in range"
message.

## 9. Task 7: false documentation

Four statements, each verified against the repository or the remote.

1. **`server/README.md:117`** gives the seed recipe as `--users 40 --weeks 26`. That recipe produced the
   database this whole sweep exists to replace. It becomes `--users 120 --weeks 52`, with a sentence saying
   why the user count matters: since `perDept` is `n / 9`, a run needs roughly eighty users before any team
   reaches eight members, and a team of three can never show rebalancing. Lines 107 to 120 otherwise stand,
   since task 1b leaves the good database at the default path. The sentence states the mechanism rather than
   a guaranteed distribution — the distribution is what task 1's assertion 3 measures.

   The section also gains the figure a reader can check their own seed against: a population mean
   member-week in the high twenties to low thirties against the 44 h capacity, with some weeks well above
   it. A seed averaging in the low twenties predates the 2026-09-15 weekly-supply correction and the
   database is wrong.

2. **`docs/backlog.md:9-10`** calls `archive/python-desktop-v1` a tag and gives
   `git worktree add ../whf-archive archive/python-desktop-v1`.
   **`docs/backlog.md:50-52`** states it "is now a **tag** at `3c6f836`".
   Both are false. `git ls-remote` shows `refs/heads/archive/python-desktop-v1` at `5c69bf6`, and the
   repository has **no tags at all**. The correction: it is a branch on the remote at `5c69bf6`, checked out
   with `git fetch origin archive/python-desktop-v1 && git worktree add ../whf-archive
   origin/archive/python-desktop-v1`. The distinguishing detail the passage offers — that the tag differed
   from `5c69bf6` only by `service/tests/test_parity_compare.py` having moved to `server/tools/tests/` — is
   now moot twice over: the tag does not exist, and the parity procedure including `server/tools/tests/` was
   retired on 2026-09-14.

3. **`CLAUDE.md`, "Where the project stands"**: "`main` has not been fast-forwarded yet" is stale.
   `git ls-remote --heads origin` shows `main` and `dev` both at `765aa9f`.

4. **`CLAUDE.md`, "Where the project stands"** gains a paragraph for this sweep once it lands.

## 10. Task 8: two dead backlog entries

Both name code deleted on 2026-09-14. Both are deleted, with one line recorded under the evaluation-removal
note. The reasoning was checked rather than assumed.

**`docs/backlog.md:322`, "Level B of the evaluation report has no naive baseline."** Its subject is
`Report`, removed with the harness. The concern it raises is also already met elsewhere: `AccuracyScore`
carries `mase` and `maseN`, scored on the rows whose member has a log on the same weekday seven days
earlier. A MASE against a lag-7 baseline is exactly the "last week's logged hours per member-week" candidate
the entry proposes. Nothing survives to act on.

**`docs/backlog.md:351`, "The evaluation harness applies a shorter history gate than a run does.
Important; go back to this."** The entry describes a divergence between two callers: `eval/Harness` set
`maxHorizon = windows`, while a run uses `Horizon.maxHorizon(origin, windows)`, which is `windows + 1`, so
with `minHistoryWeeks(maxHorizon) = 10 + maxHorizon` the harness admitted a team on twelve weeks of history
where a run demands thirteen.

Verified: `Backtest.minHistoryWeeks` has exactly one caller, `Backtest.origins:65`; and `Backtest.origins`
has exactly one production caller, `ForecastRunner.java:126`, which passes
`Horizon.maxHorizon(origin, windows)`. `Harness` was the divergent caller, so with it deleted there is one
gate and nothing to diverge from. `accuracy(teamId, from, to)` scores forecasts already stored and computes
none, so it applies no history gate of its own. The deletion note records this, so the question is not
reopened.

## 11. Testing

Test-driven throughout, per CLAUDE.md's hard rule: a failing test first for every change in tasks 1a, 2, 3,
4 and 6. jqwik property files are named `*PropertyTest.java` — never `*Properties.java`, which surefire
silently skips.

New or changed tests, by task:

| Task | Tests |
| --- | --- |
| 1a | `seed/WorkFamilyPropertyTest` (new, section 3.1): member-weeks span `CapacityWriter.BASE_HOURS` in both directions, both within one team. `seed/WorkFamilyTest:46` pins three of the changed values in its `@CsvSource` and changes with them. `seed/SeedGeneratorTest:141-147` is expected to pass unchanged — its 59.4 h ceiling comes from presence, not from the constants |
| 2 | `ai/NumberVerifierTest`: one regression test per cause in 4.1 to 4.3 using the observed strings, plus the three negative tests of 4.4 |
| 3 | `facts/FactsBuilderTest:262-265` rewritten for the entry shape; `ai/NumberVerifierTest` fixture; `ai/SkillTextsTest` expected to pass unchanged |
| 4 | `service/RunProgressTrackerTest:58` phase table |
| 6 | `eval/AccuracyTest` and `eval/AccuracyPropertyTest` for the zero-row case |

Task 1b asserts through SQL, task 5 is not reachable by the gate, and tasks 7 and 8 touch documentation
only.

### 11.1 The gate

`bash scripts/check.sh` inside the development container, never on the Windows host, where it skips Maven
and still exits 0. Wipe `server/forecast-core/target/surefire-reports` first, and read the result from
`TEST-*.xml` — never by summing the `*.txt` files, which undercount by about thirty wherever a class mixes
JUnit `@Test` with jqwik `@Property`.

Baseline to beat: 409 tests, 0 failures, 13 skipped. Budget about seventeen minutes per run, and several
runs. CI is paused by the owner's decision of 2026-09-14, so this and section 11.2 are the only checks that
exist.

### 11.2 End-to-end acceptance

On the rebuilt database, against one of the ten-member teams:

```bash
bash server/examples/run-host-example.sh --team <big-team-uuid> --narrate --lang en
```

Two requirements:

- **zero `UNVERIFIED` items** — this proves task 2;
- **a non-empty `rebalancing` list, with `backlog_pressed` or `deadline_pressed` entries carrying ids and
  hours** — this proves tasks 1a, 1b and 3 together.

A run that still reports `overloaded members: none` means task 1's assertion 4 was not actually met and the
sweep is not finished, whatever the gate says.

## 12. Out of scope

- The rule that Copilot never produces a forecast number. Task 2 makes the verifier more *precise*; it does
  not make it more permissive in principle, which is what section 4.4's negative tests exist to prove.
- Capping demand by capacity.
- Restoring the CI triggers.
- The `archive/python-desktop-v1` branch itself, whose history shares no ancestor with `dev` and must never
  be merged into it.
- The real WorkloadHub export, and any real-mode seed output, both of which stay outside the repository.
- Any provider key other than the user's own GitHub Copilot token.
- The forecasting model, the feature matrix, the facts contract beyond the two lists in task 3, and the
  window count's range.
- Every seed parameter except `WorkFamily.weeklyHours`. `discipline`, the overtime shares and factors,
  `medianEstimate`, `EVENT_FACTOR` and the seasonal curve all stay as they are. Task 1a is a single lever,
  chosen because the mechanisms around it are already bounded and tested; reaching for a second one is the
  re-scope section 3.4 describes, not part of this sweep.
- The team-size distribution in `ReferenceData.syntheticUsers`. Task 1b works with what 120 users yields and
  measures it (assertion 3) rather than adding a knob.

## 13. Risks

1. **Task 2 weakens the verifier.** The likeliest serious outcome of this sweep. Mitigated by the
   fallback-only formulation in 4.3, which cannot un-verify anything that verifies today, and by the
   mandatory negative tests in 4.4. If a negative test cannot be made to fail, stop: the widening is wider
   than intended.
2. **Task 1a changes what every future experiment measures.** Raising the weekly supply moves the population
   mean member-week from ~23 h to the high twenties to low thirties, so any figure quoted from a seeded
   database before this
   change — including `docs/eval/2026-09-10-java-parity-synthetic/` — describes a different population.
   That record is already annotated as historical and is not re-run; the plan adds a line saying the supply
   changed on this date, so a future reader does not compare across it. The ceiling cannot run away
   (section 3.1: presence bounds a member-week at 59.4 h, asserted already), so the risk is comparability,
   not correctness.
3. **Overload is still not reached after 1a.** Section 3.4 names the two remaining branches and makes both
   a stop rather than something to absorb — in particular, `discipline` is not a tuning knob.
4. **Task 3 needs a second pass.** It is the task most likely to ripple, through the skill text and the
   narrator's behaviour rather than through compilation.
5. **Task 5 is invisible to the gate.** Checked by hand only, and reported as such rather than claimed as
   covered.
6. **The gate costs about seventeen minutes and is growing.** Batch the tasks before running it rather than
   running it per task.
