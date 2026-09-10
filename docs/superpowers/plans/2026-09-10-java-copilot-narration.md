# Java Copilot Narration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Narrate a finished forecast run from the Java module through the user's own Copilot seat: tools over the stored facts, a validated and number-verified JSON narrative, its cost, live progress, a REST surface, and the CLI commands `narrate` and `copilot status`.

**Architecture:** A small `CopilotGateway` seam wraps `copilot-sdk-java` (in-process runtime); the `Narrator` orchestrates one session (system message with the six product skills embedded, nine read-only tools, two attempts, contract validation, number verification, usage) and streams progress into `RunProgressTracker`. Outcomes are stored in a recreated `forecast_narratives` table and exposed through `DefaultForecastService`, an optional Spring MVC controller, and picocli commands.

**Tech Stack:** Java 21, Spring Boot 4.1.1, `copilot-sdk-java` 1.0.13-preview.6 with its `linux-x64` runtime, JNA 5.19.1, Jackson 3 (`tools.jackson`), Flyway, JUnit 6, jqwik 1.10.1, picocli 4.7.7, Spring MVC (optional) with `spring-boot-starter-webmvc-test`.

**Spec:** `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md` (this plan argues from it; read it first). Background: `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md` sections 10 to 12, and `docs/superpowers/specs/2026-09-07-planned-work-and-likely-work-design.md` section 6.

## Global Constraints

- The language model never produces a forecast number: every figure in a narrative must exist in the facts; `UNVERIFIED` is stored and reported, never promoted to `OK`.
- Copilot access goes through the Copilot SDK with the user's own token from `GitHubTokenStore.load(userId)`, read in exactly one place (the service's `narrate`/`copilotStatus`). No other provider, no API key, no `copilot login` on the server. The token is never logged, never in progress text, facts or results.
- English and French only for narratives (`en`, `fr`); anything else is `INVALID_REQUEST`.
- Test-driven: write the failing test, run it, implement, run it green, commit. Property tests (jqwik) for arithmetic invariants.
- No test talks to Copilot. The suite never extracts the 91 MB runtime (`NativeRuntimeLoader.resolve()` is called only by `copilotStatus` in production and by the manual live procedure).
- Build gate: `cd server && mvn -B -q verify` stays under three minutes without Docker; PostgreSQL tests run through Testcontainers when Docker is reachable, else skip with a message.
- Style as the existing module: `final` classes, records for values, package-private unless the type crosses a package, `LinkedHashMap` for JSON-shaped maps, ISO dates, Monday weeks.
- Commits: imperative subject, a short body saying why. Commit after every task's green run.
- Work on the current branch; `dev` receives it once the whole plan is reviewed.

---

## File structure

`server/forecast-core/src/main/java/com/workloadhub/forecast/`

| File | Responsibility |
|---|---|
| `api/NarrativeStatus.java` (new) | `OK`, `UNVERIFIED`, `FAILED` |
| `api/NarrativeResult.java` (replace) | the stored narration row as the host sees it |
| `api/CopilotStatus.java` (replace) | token, runtime, auth, quota |
| `ForecastProperties.java` (modify) | `whf.work-dir` |
| `ai/NarrationOutcome.java` (new) | what one narration produced, before storage |
| `ai/SkillTexts.java` (new) | the six product skills from the classpath |
| `ai/Prompts.java` (new) | system, user and retry prompts |
| `ai/NarrativeContract.java` (new) | parse, validate, cross-check with the facts; the `Narrative` records |
| `ai/NumberVerifier.java` (new) | numbers in text against the facts |
| `ai/Usage.java`, `ai/UsageMetrics.java`, `ai/UsageEvent.java` (new) | the one usage shape |
| `ai/ToolSpec.java`, `ai/FactsTools.java` (new) | the nine tools |
| `ai/CopilotGateway.java`, `ai/CopilotConnection.java`, `ai/NarrationSession.java`, `ai/SessionSpec.java`, `ai/AuthStatus.java`, `ai/RuntimeInfo.java`, `ai/NarrationEvent.java`, `ai/NarrationProgress.java` (new) | the seam around the SDK |
| `ai/Narrator.java` (new) | one narration end to end |
| `ai/SdkCopilotGateway.java` (new) | the real gateway |
| `service/RunProgressTracker.java` (modify) | narration steps and live tails |
| `service/DefaultForecastService.java` (modify) | `narrate`, `narrative`, `copilotStatus` |
| `store/JdbcNarrativeStore.java` (new) | `forecast_narratives` |
| `web/ForecastController.java`, `web/ForecastExceptionHandler.java`, `web/ForecastWebConfiguration.java` (new) | REST |
| `ForecastAutoConfiguration.java` (modify) | new beans, web import |
| `resources/db/forecast/{sqlite,postgresql}/V2__narratives_with_status.sql` (new) | recreate the table |
| `resources/skills/whf-*/SKILL.md` (new, six) | product skills |
| `resources/ai/contract.schema.json` (new) | the contract as JSON Schema text |

`server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/`: `NarrateCommand.java`, `CopilotCommand.java` (new), `Services.java`, `ForecastCli.java` (modify).

Tests mirror each file under `src/test/java`; `ai/FakeGateway.java` is the scripted fake; `samplehost/SampleHostApplication.java` and `samplehost/SampleHostIntegrationTest.java` are the host test.

---

### Task 1: Dependencies, API records, `whf.work-dir`, the V2 migration and `JdbcNarrativeStore`

**Files:**
- Modify: `server/pom.xml` (dependencyManagement: add jna, spring-boot web test artifacts are managed by the parent already)
- Modify: `server/forecast-core/pom.xml`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/NarrativeStatus.java`
- Replace: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/NarrativeResult.java`
- Replace: `server/forecast-core/src/main/java/com/workloadhub/forecast/api/CopilotStatus.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/ForecastProperties.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/NarrationOutcome.java`
- Create: `server/forecast-core/src/main/resources/db/forecast/sqlite/V2__narratives_with_status.sql`
- Create: `server/forecast-core/src/main/resources/db/forecast/postgresql/V2__narratives_with_status.sql`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/store/JdbcNarrativeStore.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/JdbcNarrativeStoreTest.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/store/ForecastMigrationsTest.java` (V2 assertion)

**Interfaces:**
- Produces: `NarrativeStatus`, `NarrativeResult(UUID id, UUID runId, String language, NarrativeStatus status, String model, String narrativeJson, String rawText, String verificationJson, String usageJson, String error, int attempts, int toolCalls, LocalDateTime createdAt)`, `CopilotStatus(UUID userId, boolean hasToken, boolean runtimeAvailable, String runtimePath, String runtimeVersion, Boolean authenticated, String login, String quotaJson, String message)`, `NarrationOutcome(NarrativeStatus status, String reason, String narrativeJson, String rawText, String verificationJson, String usageJson, String model, int attempts, List<String> toolCalls, String error)`, `JdbcNarrativeStore.save(UUID runId, String language, NarrationOutcome outcome, LocalDateTime createdAt) -> NarrativeResult`, `latest(UUID runId, String language) -> Optional<NarrativeResult>`, `find(UUID id) -> Optional<NarrativeResult>`, `ForecastProperties.getWorkDir()`.

- [ ] **Step 1: Add the dependencies**

In `server/pom.xml`, inside `<properties>` add `<jna.version>5.19.1</jna.version>` and inside `<dependencyManagement><dependencies>` add:

```xml
      <dependency><groupId>net.java.dev.jna</groupId><artifactId>jna</artifactId><version>${jna.version}</version></dependency>
```

In `server/forecast-core/pom.xml` add, after the `xgboost4j_2.12` line:

```xml
    <dependency><groupId>com.github</groupId><artifactId>copilot-sdk-java</artifactId></dependency>
    <!-- The in-process runtime (native/linux-x64/runtime.node); the SDK finds it on the runtime classpath. -->
    <dependency><groupId>com.github</groupId><artifactId>copilot-sdk-java-runtime</artifactId><classifier>linux-x64</classifier><scope>runtime</scope></dependency>
    <!-- Optional in the SDK's own pom; required for the in-process runtime. -->
    <dependency><groupId>net.java.dev.jna</groupId><artifactId>jna</artifactId></dependency>
    <!-- The REST controller compiles against Spring MVC and only activates when the host has it. -->
    <dependency><groupId>org.springframework</groupId><artifactId>spring-webmvc</artifactId><optional>true</optional></dependency>
```

and, in the test dependencies:

```xml
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc-test</artifactId><scope>test</scope></dependency>
```

Run: `cd server && mvn -B -q -pl forecast-core dependency:resolve`
Expected: no error (the SDK, its runtime and JNA resolve; `spring-webmvc` resolves through the parent's BOM).

- [ ] **Step 2: Write the failing store test**

`server/forecast-core/src/test/java/com/workloadhub/forecast/store/JdbcNarrativeStoreTest.java`:

```java
package com.workloadhub.forecast.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NarrationOutcome;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JdbcNarrativeStoreTest {

    static final UUID TEAM = UUID.fromString("40000000-0000-0000-0000-000000000001");
    static final UUID USER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    static final LocalDateTime T0 = LocalDateTime.of(2026, 9, 10, 9, 0, 0, 123456000);

    static DataSource sqlite() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        ForecastMigrations.run(ds);
        return ds;
    }

    static NarrationOutcome ok() {
        return new NarrationOutcome(NarrativeStatus.OK, null, "{\"run_summary\":\"fine\"}", null, "{\"checked\":2,\"unverified\":[]}",
                "{\"source\":\"metrics\"}", "gpt-5", 1, List.of("get_run_overview", "get_member_forecast"), null);
    }

    static NarrationOutcome failed() {
        return new NarrationOutcome(NarrativeStatus.FAILED, "invalid_output", null, "still nope", "{}", "{\"source\":\"events\"}", "gpt-5", 2,
                List.of("get_run_overview"), "invalid_output: the answer is not valid JSON");
    }

    void lifecycle(DataSource ds) {
        Dialect dialect = Dialect.of(ds);
        JdbcRunStore runs = new JdbcRunStore(ds, dialect);
        UUID run = runs.create(new RunRequest(TEAM, USER, LocalDate.of(2026, 9, 6), null, null), T0);
        JdbcNarrativeStore store = new JdbcNarrativeStore(ds, dialect);

        NarrativeResult first = store.save(run, "en", ok(), T0.plusMinutes(1));
        assertEquals(NarrativeStatus.OK, first.status());
        assertEquals(run, first.runId());
        assertEquals("en", first.language());
        assertEquals("gpt-5", first.model());
        assertEquals("{\"run_summary\":\"fine\"}", first.narrativeJson());
        assertNull(first.rawText());
        assertEquals(1, first.attempts());
        assertEquals(2, first.toolCalls());
        assertEquals(T0.plusMinutes(1), first.createdAt());
        assertEquals(first, store.find(first.id()).orElseThrow());
        assertEquals(first, store.latest(run, "en").orElseThrow());
        assertTrue(store.latest(run, "fr").isEmpty());

        NarrativeResult second = store.save(run, "en", failed(), T0.plusMinutes(2));
        assertEquals(NarrativeStatus.FAILED, second.status());
        assertNull(second.narrativeJson());
        assertEquals("still nope", second.rawText());
        assertEquals("invalid_output: the answer is not valid JSON", second.error());
        assertEquals(second, store.latest(run, "en").orElseThrow(), "the newest row wins whatever its status");

        NarrativeResult french = store.save(run, "fr", ok(), T0.plusMinutes(3));
        assertEquals(french, store.latest(run, "fr").orElseThrow());
        assertEquals(second, store.latest(run, "en").orElseThrow());
        assertTrue(store.find(UUID.randomUUID()).isEmpty());
        assertTrue(store.latest(UUID.randomUUID(), "en").isEmpty());
    }

    @Test
    void sqlite() {
        lifecycle(sqlite());
    }

    @Test
    void postgresql() {
        DataSource ds = DatabaseTestSupport.postgresOrSkip();
        WorkloadHubSchema.createPostgresql(ds);
        ForecastMigrations.run(ds);
        lifecycle(ds);
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=JdbcNarrativeStoreTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`NarrationOutcome`, `NarrativeStatus`, `JdbcNarrativeStore` do not exist).

- [ ] **Step 4: The API records and the outcome**

`api/NarrativeStatus.java`:

```java
package com.workloadhub.forecast.api;

/** How a narration ended: every number verified, some numbers not found in the facts, or no usable narrative. */
public enum NarrativeStatus {
    OK,
    UNVERIFIED,
    FAILED
}
```

`api/NarrativeResult.java` (replace the whole file):

```java
package com.workloadhub.forecast.api;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One stored narration. {@code narrativeJson} is null when {@code status} is FAILED, and {@code rawText} then holds
 * the last answer; {@code error} is "<reason>: <detail>" with reason in timeout, model_error, invalid_output.
 */
public record NarrativeResult(UUID id, UUID runId, String language, NarrativeStatus status, String model, String narrativeJson, String rawText,
        String verificationJson, String usageJson, String error, int attempts, int toolCalls, LocalDateTime createdAt) {
}
```

`api/CopilotStatus.java` (replace the whole file):

```java
package com.workloadhub.forecast.api;

import java.util.UUID;

/**
 * Whether this user can narrate: a stored token, a runtime the SDK can load, and, when both hold, whether the token is
 * accepted and how much quota is left. {@code authenticated} and {@code login} are null when no token is stored or the
 * runtime is unavailable; {@code quotaJson} is null when the quota could not be read and {@code message} says why.
 */
public record CopilotStatus(UUID userId, boolean hasToken, boolean runtimeAvailable, String runtimePath, String runtimeVersion, Boolean authenticated,
        String login, String quotaJson, String message) {
}
```

`ai/NarrationOutcome.java`:

```java
package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.api.NarrativeStatus;
import java.util.List;

/**
 * What one narration produced, before it is stored. {@code reason} is null unless FAILED (then timeout, model_error or
 * invalid_output); {@code narrativeJson} is null when FAILED; {@code rawText} is the last answer when FAILED, else null.
 */
public record NarrationOutcome(NarrativeStatus status, String reason, String narrativeJson, String rawText, String verificationJson,
        String usageJson, String model, int attempts, List<String> toolCalls, String error) {

    public NarrationOutcome {
        toolCalls = List.copyOf(toolCalls);
    }
}
```

In `ForecastProperties.java` add the field, getter and setter next to `tokenKey`:

```java
    private String workDir = System.getProperty("user.home") + "/.workloadhub-forecast";
    public String getWorkDir() { return workDir; }
    public void setWorkDir(String workDir) { this.workDir = workDir; }
```

- [ ] **Step 5: The V2 migrations**

`resources/db/forecast/sqlite/V2__narratives_with_status.sql`:

```sql
-- forecast_narratives was empty in every deployment before narration existed; recreate it with the
-- status columns rather than patch it: a FAILED narration keeps its cost and its last answer.
DROP TABLE forecast_narratives;
CREATE TABLE forecast_narratives (
  id                TEXT PRIMARY KEY,
  run_id            TEXT NOT NULL REFERENCES forecast_runs(id),
  language          TEXT NOT NULL,
  status            TEXT NOT NULL,
  model             TEXT,
  narrative_json    TEXT,
  raw_text          TEXT,
  verification_json TEXT NOT NULL,
  usage_json        TEXT NOT NULL,
  error             TEXT,
  attempts          INTEGER NOT NULL,
  tool_calls        INTEGER NOT NULL,
  created_at        TEXT NOT NULL
);
CREATE INDEX forecast_narratives_run_idx ON forecast_narratives (run_id, language, created_at);
```

`resources/db/forecast/postgresql/V2__narratives_with_status.sql`:

```sql
-- forecast_narratives was empty in every deployment before narration existed; recreate it with the
-- status columns rather than patch it: a FAILED narration keeps its cost and its last answer.
DROP TABLE forecast_narratives;
CREATE TABLE forecast_narratives (
  id                uuid PRIMARY KEY,
  run_id            uuid NOT NULL REFERENCES forecast_runs(id),
  language          varchar(2) NOT NULL,
  status            varchar(16) NOT NULL,
  model             varchar(64),
  narrative_json    text,
  raw_text          text,
  verification_json text NOT NULL,
  usage_json        text NOT NULL,
  error             text,
  attempts          integer NOT NULL,
  tool_calls        integer NOT NULL,
  created_at        timestamp NOT NULL
);
CREATE INDEX forecast_narratives_run_idx ON forecast_narratives (run_id, language, created_at);
```

In `ForecastMigrationsTest.check(DataSource)` add, after the two `hasColumn(ds, "users", ...)` assertions:

```java
        assertTrue(hasColumn(ds, "forecast_narratives", "status"), "V2 recreated the narratives table");
        assertTrue(hasColumn(ds, "forecast_narratives", "raw_text"));
        assertTrue(hasColumn(ds, "forecast_narratives", "tool_calls"));
```

- [ ] **Step 6: The store**

`store/JdbcNarrativeStore.java`:

```java
package com.workloadhub.forecast.store;

import com.workloadhub.forecast.ai.NarrationOutcome;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One row per narration in forecast_narratives, whatever its status, on SQLite or PostgreSQL. */
public final class JdbcNarrativeStore {

    private static final String COLUMNS = "id, run_id, language, status, model, narrative_json, raw_text, verification_json, usage_json, error,"
            + " attempts, tool_calls, created_at";

    private final JdbcClient jdbc;
    private final Dialect dialect;

    public JdbcNarrativeStore(DataSource dataSource, Dialect dialect) {
        this.jdbc = JdbcClient.create(dataSource);
        this.dialect = dialect;
    }

    private String ph(String type) {
        return dialect.placeholder(type);
    }

    private static String ts(LocalDateTime t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MICROS).toString();
    }

    public NarrativeResult save(UUID runId, String language, NarrationOutcome outcome, LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO forecast_narratives (" + COLUMNS + ") VALUES (" + ph("uuid") + ", " + ph("uuid") + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + ph("timestamp") + ")")
                .param(id.toString()).param(runId.toString()).param(language).param(outcome.status().name()).param(outcome.model())
                .param(outcome.narrativeJson()).param(outcome.rawText()).param(outcome.verificationJson()).param(outcome.usageJson())
                .param(outcome.error()).param(outcome.attempts()).param(outcome.toolCalls().size()).param(ts(createdAt))
                .update();
        return find(id).orElseThrow();
    }

    public Optional<NarrativeResult> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forecast_narratives WHERE id = " + ph("uuid")).param(id.toString())
                .query().listOfRows().stream().findFirst().map(JdbcNarrativeStore::row);
    }

    /** The newest narration of that language for the run, whatever its status. */
    public Optional<NarrativeResult> latest(UUID runId, String language) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM forecast_narratives WHERE run_id = " + ph("uuid")
                + " AND language = ? ORDER BY created_at DESC, id DESC LIMIT 1")
                .param(runId.toString()).param(language).query().listOfRows().stream().findFirst().map(JdbcNarrativeStore::row);
    }

    private static NarrativeResult row(Map<String, Object> r) {
        return new NarrativeResult(UUID.fromString(str(r, "id")), UUID.fromString(str(r, "run_id")), str(r, "language"),
                NarrativeStatus.valueOf(str(r, "status")), str(r, "model"), str(r, "narrative_json"), str(r, "raw_text"), str(r, "verification_json"),
                str(r, "usage_json"), str(r, "error"), ((Number) r.get("attempts")).intValue(), ((Number) r.get("tool_calls")).intValue(),
                dateTime(r.get("created_at")));
    }

    private static String str(Map<String, Object> r, String col) {
        Object v = r.get(col);
        return v == null ? null : v.toString();
    }

    private static LocalDateTime dateTime(Object v) {
        if (v instanceof java.sql.Timestamp t) {
            return t.toLocalDateTime();
        }
        return LocalDateTime.parse(v.toString().replace(' ', 'T'));
    }
}
```

- [ ] **Step 7: Run the tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='JdbcNarrativeStoreTest,ForecastMigrationsTest,JdbcRunStoreTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (the PostgreSQL case skips without Docker with the "Docker is not reachable" message).

Then the whole module compiles: `cd server && mvn -B -q compile test-compile` must succeed. `DefaultForecastService` does not construct `NarrativeResult` or `CopilotStatus` yet, so the record changes compile.

- [ ] **Step 8: Commit**

```bash
git add server/pom.xml server/forecast-core/pom.xml server/forecast-core/src
git commit -m "feat(server): narratives table with status, API records and the Copilot SDK dependencies

A FAILED narration keeps its cost and last answer, so the table gains
status, raw_text, error, attempts and tool_calls; it was empty everywhere,
so V2 recreates it. The SDK, its linux-x64 runtime and JNA come in now so
the next tasks build against them."
```

---

### Task 2: Product skills, `SkillTexts`, the contract schema text and `Prompts`

**Files:**
- Create: `server/forecast-core/src/main/resources/skills/whf-domain/SKILL.md`, `.../whf-forecast-interpretation/SKILL.md`, `.../whf-pattern-discovery/SKILL.md`, `.../whf-likely-work/SKILL.md`, `.../whf-rebalancing-advice/SKILL.md`, `.../whf-report-style/SKILL.md`
- Create: `server/forecast-core/src/main/resources/ai/contract.schema.json`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/SkillTexts.java`
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/Prompts.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/SkillTextsTest.java`, `.../ai/PromptsTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `SkillTexts.NAMES` (`List<String>`, the six names in prompt order), `SkillTexts.load() -> List<SkillTexts.Skill>` with `record Skill(String name, String text)`; `Prompts.load() -> Prompts`, `Prompts.systemMessage() -> String`, `Prompts.userPrompt(JsonNode facts, String language) -> String`, `Prompts.retryPrompt(List<String> problems) -> String`, `Prompts.contractSchema() -> String`, `Prompts.SUPPORTED_LANGUAGES` (`Set<String>` of `en`, `fr`).

- [ ] **Step 1: Write the failing tests**

`ai/SkillTextsTest.java`:

```java
package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SkillTextsTest {

    @Test
    void sixSkillsLoadInPromptOrderWithTheirFrontMatter() {
        List<SkillTexts.Skill> skills = SkillTexts.load();
        assertEquals(List.of("whf-domain", "whf-forecast-interpretation", "whf-pattern-discovery", "whf-likely-work", "whf-rebalancing-advice",
                "whf-report-style"), skills.stream().map(SkillTexts.Skill::name).toList());
        assertEquals(SkillTexts.NAMES, skills.stream().map(SkillTexts.Skill::name).toList());
        for (SkillTexts.Skill s : skills) {
            assertTrue(s.text().startsWith("---\nname: " + s.name() + "\n"), s.name() + " front matter");
            assertTrue(s.text().contains("\ndescription: "), s.name());
            assertTrue(s.text().length() < 6000, s.name() + " should stay short");
        }
    }

    @Test
    void theSkillsSpeakTheJavaFactsVocabulary() {
        String all = String.join("\n", SkillTexts.load().stream().map(SkillTexts.Skill::text).toList());
        assertTrue(all.contains("xgboost") && all.contains("seasonal_naive"));
        assertTrue(!all.contains("chronos2") && !all.contains("tsb") && !all.contains(" gbm"), "old model names are gone");
        assertTrue(all.contains("planned_hours") && all.contains("likely_work") && all.contains("due_hours"));
        assertTrue(all.contains("40 h"), "the Java module's default capacity");
    }
}
```

`ai/PromptsTest.java`:

```java
package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.data.ExportFiles;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PromptsTest {

    static final String FACTS = """
            {"run": {"id": "11111111-1111-1111-1111-111111111111", "as_of": "2026-09-06", "weeks": ["2026-09-07", "2026-09-14"]},
             "team": {"id": "44444444-4444-4444-4444-444444444444", "name": "Mobile Apps"},
             "members": [{"id": "aaaaaaaa-0000-0000-0000-000000000004", "name": "Sara Tazi", "role": "TEAM_LEADER"},
                         {"id": "aaaaaaaa-0000-0000-0000-000000000005", "name": "Omar Benali", "role": "MEMBER"}],
             "model": {"champion": "xgboost"}}
            """;

    static JsonNode facts() {
        return ExportFiles.mapper().readTree(FACTS);
    }

    @Test
    void theSystemMessageStatesTheRulesAndEmbedsEverySkillOnce() {
        Prompts p = Prompts.load();
        String lower = p.systemMessage().toLowerCase();
        assertTrue(lower.contains("never invent") && lower.contains("tools") && lower.contains("json") && lower.contains("one decimal"));
        for (String name : SkillTexts.NAMES) {
            String heading = "## Skill: " + name;
            assertEquals(p.systemMessage().indexOf(heading), p.systemMessage().lastIndexOf(heading), name + " appears once");
            assertTrue(p.systemMessage().contains(heading), name);
        }
        assertFalse(p.systemMessage().contains("\n---\nname:"), "front matter is stripped from the embedded skills");
    }

    @Test
    void theUserPromptNamesRunMembersWeeksLanguageAndContract() {
        Prompts p = Prompts.load();
        String en = p.userPrompt(facts(), "en");
        assertTrue(en.contains("Mobile Apps") && en.contains("2026-09-07") && en.contains("2026-09-14") && en.contains("2026-09-06"));
        assertTrue(en.contains("member_id aaaaaaaa-0000-0000-0000-000000000004 (Sara Tazi, TEAM_LEADER)"));
        assertTrue(en.contains("member_id aaaaaaaa-0000-0000-0000-000000000005 (Omar Benali, MEMBER)"));
        assertTrue(en.contains("get_run_overview") && en.contains("get_planned_work") && en.contains("get_rebalancing_candidates"));
        assertTrue(en.contains(p.contractSchema()));
        assertTrue(en.contains("Language: en") && en.contains("in English"));
        String fr = p.userPrompt(facts(), "fr");
        assertTrue(fr.contains("Language: fr") && fr.contains("en français"));
        assertTrue(en.trim().endsWith("Return only the JSON document."));
    }

    @Test
    void theRetryPromptListsEveryProblem() {
        String text = Prompts.load().retryPrompt(List.of("members[1].member_id: unknown member", "the answer is not valid JSON"));
        assertTrue(text.contains("- members[1].member_id: unknown member") && text.contains("- the answer is not valid JSON"));
        assertTrue(text.contains("only the JSON"));
    }

    @Test
    void theContractSchemaIsJsonWithTheTopLevelFields() {
        JsonNode schema = ExportFiles.mapper().readTree(Prompts.load().contractSchema());
        assertEquals(false, schema.path("additionalProperties").asBoolean());
        for (String field : List.of("run_summary", "members", "team_risks", "rebalancing", "suggested_adjustments", "model_notes")) {
            assertTrue(schema.path("properties").has(field), field);
        }
        assertTrue(schema.path("$defs").path("member").path("properties").has("likely_work"));
        assertTrue(schema.path("$defs").path("move").path("properties").has("task_keys"));
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='SkillTextsTest,PromptsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`SkillTexts`, `Prompts` missing).

- [ ] **Step 3: The six skills**

`resources/skills/whf-domain/SKILL.md`:

```markdown
---
name: whf-domain
description: Vocabulary and data dictionary of the WorkloadHub forecast facts. Use when reading run facts about teams, members, tasks, capacity, demand, overload or planned work.
---

# WorkloadHub domain

## Organisation
- A **department** is a team without a manager; a **team** has a manager (the team leader), who also does technical work and is counted like any member.
- A **member** is a person counted in the workload, identified by a UUID string (`id`) and named by `name`. Weeks start on Monday; working days are Monday to Friday; public holidays are off days.

## Task record (in `open_tasks`, `likely_work.planned`)
`key` (the task's key, cite it as given), `title`, `type` (the application's task type), `family` (delivery, defect, support, analysis, maintenance, other: the type folded into a work family), `priority`, `estimated_hours`, `remaining_hours`, `due_date`, `overdue`, `project_key`, `in_progress`.

## Forecast rows (`forecast`, per member and week)
- **demand**: predicted hours, never capped. It is `open_hours` (remaining hours of tasks already assigned) plus `new_hours` (hours of tasks predicted to arrive) plus `planned_hours` (hours of backlog tasks allocated to this member by the planned-work rule).
- **capacity**: available hours after holidays, absences and the team's capacity plan (default 40 h per week, 8 h per working day); `working_days` and `absence_hours` say why it is lower.
- **overload**: max(0, demand minus capacity). Demand is never cut to fit capacity.
- **low / high**: an interval around demand from the model's backtest residuals; wide bands mean an unstable history.
- **due_hours**: remaining hours of the member's open tasks that fall due that week.

## Member facts
`history_13w` (weekly arrival hours and task counts), `logged_hours_4w` (hours logged per week), `unlogged_tasks` (finished tasks with no time logged; their actual hours were estimated), `reopened_tasks`, `patterns` (see whf-pattern-discovery), `likely_work` (see whf-likely-work).

## Team and run facts
`team.totals` (demand, capacity and planned per week), `team.team_capacity` (the team's own capacity plan: total and allocated), `team.planned_backlog` (backlog tasks per project with estimated hours and hours in the window), `projects` (key, name, status, open and backlog task counts, first due date), `pending_holidays` (declared but unconfirmed), `data_quality` (unresolved assignments, unlogged tasks, history weeks).

## Model facts
- **champion**: the arrival model that won the backtest, `xgboost` (gradient boosting) or `seasonal_naive` (repeats history). **champion_mase** below 1.0 means it beat the seasonal-naive floor; near 1.0 means little better than repeating history. `forced_model` is set when the caller chose the model.
- **backtest_origins**: the past dates the models were scored on. **planned_basis** says how planned work was allocated, or `disabled`. **limitations**: known blind spots of this run.
```

`resources/skills/whf-forecast-interpretation/SKILL.md`:

```markdown
---
name: whf-forecast-interpretation
description: How to explain a member's two-week forecast, capacity, overload, interval and the model quality facts without adding or changing any number.
---

# Interpreting the forecast

- Lead with the decision-relevant figure: overload hours per week, then demand versus capacity.
- Say where the demand comes from: `open_hours` (already assigned work), `new_hours` (expected arrivals) and `planned_hours` (backlog work allocated to this member). A high open share means the backlog, not new work, is the problem; planned hours are work that exists but is not assigned yet.
- Capacity below 40 h means holidays, an absence or the team's capacity plan; `working_days` and `absence_hours` on the row say which. Name the cause.
- An interval (low, high) that spans more than half of capacity means the history is noisy; say the forecast is uncertain rather than quoting the band as fact.
- Overdue open tasks are placed forward from the first forecast week; they inflate week one by design. Mention `overdue_open` (in patterns) or the overdue tasks when they drive the overload.
- `due_hours` above capacity in a week means deadlines, not arrivals, are the pressure; say so.
- Projects with backlog tasks (`team.planned_backlog`) raise expected work; cite the project key.
- Model quality: `champion_mase` well below 1.0 is reliable; near or above 1.0 means the numbers are close to a naive repeat of history and the narrative should say so. `xgboost` is gradient boosting on engineered features; `seasonal_naive` repeats history. When `model.unavailable` names a model, say the forecast used the other one and give the reason in plain words.
- Never round, sum, subtract or convert numbers yourself. If a derived figure is not in the facts, describe the relationship in words.
- Risk levels: high when overload is greater than 0 in either week or `overdue_open` is at least 3; medium when demand is above 85 percent of capacity or the interval's high crosses capacity; low otherwise.
```

`resources/skills/whf-pattern-discovery/SKILL.md`:

```markdown
---
name: whf-pattern-discovery
description: How to read the per-member pattern statistics of a forecast run and turn them into evidenced findings about assignment style, rhythm, trend, estimate bias, cycle time and lateness.
---

# Reading pattern statistics

Each member's `patterns` object is computed deterministically over the last 13 weeks (arrivals) and the whole history (completions). Report a pattern only when the statistic supports it, and quote the statistic as evidence.

| Statistic | Meaning | Say something when |
|-----------|---------|--------------------|
| share_manual, share_self_picked, share_project | share of the recent tasks by assignment mode | one share is at least 0.5 (dominant style) |
| top_weekday, weekday_shares | weekday on which tasks most often arrive | the top share is at least 0.35 |
| hours_per_week_13w, trend_hours_per_week | average weekly arrival hours and its slope per week | the slope is at least 5 percent of the average in either direction |
| estimate_ratio_median | actual hours over estimated hours on completed tasks | below 0.85 (over-estimates) or above 1.15 (under-estimates) |
| cycle_days_median, cycle_days_by_type | assignment-to-completion days, overall and per work family | notably longer than the team's other members for the same family |
| lateness_days_median, share_late | completion relative to due date | share_late above 0.4 or median lateness above 2 days |
| hours_by_project, share_with_project | how work is spread over projects | one project takes more than 0.6 of the hours |
| cluster | members with similar behaviour share a cluster number | when explaining that a member behaves like others |
| open_tasks, open_est_hours, overdue_open | current backlog | overdue_open is greater than 0 |

Use the kinds `assignment_style`, `weekday_rhythm`, `trend`, `estimate_bias`, `cycle_time`, `lateness`, `project_phase`, `cluster` or `other`. A `null` statistic means there is not enough history: say so rather than guessing.
```

`resources/skills/whf-likely-work/SKILL.md`:

```markdown
---
name: whf-likely-work
description: How to write the "likely work" items of a member from the planned allocation, the project roles and the recent mix, without inventing tasks or hours.
---

# Likely work

Each member's `likely_work` holds `planned` (backlog tasks allocated to the member by the share rule, with `share`, `expected_date`, `expected_week` or `after_window`, and `hours_in_window`), `project_roles` (the member's share of each live project's assigned tasks over 26 weeks and their dominant task types there) and `recent_mix` (task-type counts over 13 weeks). The team's `planned_backlog` lists the backlog per project.

- Reason only from the planned list, the project roles and the recent mix. Say "likely" or "probably", never "will".
- Confidence `high` only when a planned task is allocated to the member (name its key); `medium` when a live project matches a role the member already holds; `low` otherwise.
- When the planned list is empty, say that no planned work is recorded and the expectation rests on history alone.
- At most four items per member, one sentence each, with the fact it rests on as evidence (a task key, a project key and share, or a type count).
- Never write an hours figure that is not in the facts; `hours_in_window` and `estimated_hours` may be cited as given.
```

`resources/skills/whf-rebalancing-advice/SKILL.md`:

```markdown
---
name: whf-rebalancing-advice
description: Rules for proposing task moves between members of the same team when the forecast shows overload, and for warning team leaders.
---

# Rebalancing rules

1. Source: a member listed under `rebalancing_candidates.overloaded`. Target: a member listed under `underloaded`, in the same week, with `spare_hours` at least the hours moved.
2. Prefer targets whose patterns show the same work family (`cycle_days_by_type` covers it) and whose project roles include the task's project, and who are not absent that week (capacity is not reduced).
3. Move whole open tasks where possible: pick from the source's `open_tasks`, prefer tasks not yet started (`in_progress` false) and not overdue, and name their keys in `task_keys`.
4. Hours moved must be at most the source's overload in that week. Do not propose moves that would push the target above its capacity.
5. When no target has spare hours, do not invent one: raise a team risk instead and recommend that the department lead be told.
6. Confidence: `high` when the move fits rules 1 to 4 fully; `medium` when the family or project match is weak; `low` when the source overload is inside the forecast interval's noise.
7. Warnings: every member with overload above 0 gets a warning naming the week and the hours; a member with `overdue_open` above 0 gets a backlog warning.
```

`resources/skills/whf-report-style/SKILL.md`:

```markdown
---
name: whf-report-style
description: Tone, length and structure for the narrative fields returned to a team leader, in English or French.
---

# Report style

- Audience: a team leader with five minutes. Short sentences, concrete figures with their unit (h) and week (ISO date).
- run_summary: three to six sentences: who is overloaded, by how much, why, and the single most useful action.
- Member summary: at most 120 words. Lead with the number that matters, then the reason, then what to do.
- Patterns: one sentence each, with the statistic and value as evidence, for example "share_project 0.62".
- Warnings: one line each, starting with the week.
- Team risks: a title of at most eight words and a two-sentence detail.
- No praise, no hedging words such as "might" when the facts are clear, no repetition of the same figure in several fields.
- Inside a member's summary, patterns, warnings and likely work, cite only that member's own numbers plus the run-wide ones (team totals, project facts, model quality). Another member's hours belong in that member's section, in a team risk or in a rebalancing reason, where comparing people is the point.
- Language: the one given in the prompt (English or French); keep member names and task keys as given.
```

- [ ] **Step 4: The contract schema text**

`resources/ai/contract.schema.json`:

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "Narrative",
  "type": "object",
  "additionalProperties": false,
  "required": ["run_summary", "members"],
  "properties": {
    "run_summary": {"type": "string", "minLength": 1, "maxLength": 2000},
    "members": {"type": "array", "items": {"$ref": "#/$defs/member"}},
    "team_risks": {"type": "array", "default": [], "items": {"$ref": "#/$defs/team_risk"}},
    "rebalancing": {"type": "array", "default": [], "items": {"$ref": "#/$defs/move"}},
    "suggested_adjustments": {"type": "array", "default": [], "items": {"$ref": "#/$defs/adjustment"}},
    "model_notes": {"type": "string", "default": "", "maxLength": 1000}
  },
  "$defs": {
    "level": {"type": "string", "enum": ["low", "medium", "high"]},
    "member": {
      "type": "object",
      "additionalProperties": false,
      "required": ["member_id", "name", "risk_level", "summary"],
      "properties": {
        "member_id": {"type": "string", "description": "The member's id from get_run_overview, copied exactly"},
        "name": {"type": "string"},
        "risk_level": {"$ref": "#/$defs/level"},
        "summary": {"type": "string", "minLength": 1, "maxLength": 1200},
        "patterns": {"type": "array", "default": [], "items": {"$ref": "#/$defs/pattern"}},
        "warnings": {"type": "array", "default": [], "items": {"type": "string"}},
        "likely_work": {"type": "array", "default": [], "maxItems": 4, "items": {"$ref": "#/$defs/likely_work"}}
      }
    },
    "pattern": {
      "type": "object",
      "additionalProperties": false,
      "required": ["kind", "statement", "evidence"],
      "properties": {
        "kind": {"type": "string", "enum": ["assignment_style", "weekday_rhythm", "trend", "estimate_bias", "cycle_time", "lateness", "project_phase", "cluster", "other"]},
        "statement": {"type": "string", "minLength": 1, "maxLength": 400},
        "evidence": {"type": "string", "minLength": 1, "maxLength": 400}
      }
    },
    "likely_work": {
      "type": "object",
      "additionalProperties": false,
      "required": ["statement", "evidence", "confidence"],
      "properties": {
        "statement": {"type": "string", "minLength": 1, "maxLength": 300},
        "evidence": {"type": "string", "minLength": 1, "maxLength": 300},
        "confidence": {"$ref": "#/$defs/level"}
      }
    },
    "team_risk": {
      "type": "object",
      "additionalProperties": false,
      "required": ["title", "detail", "severity"],
      "properties": {
        "title": {"type": "string", "minLength": 1, "maxLength": 120},
        "detail": {"type": "string", "minLength": 1, "maxLength": 800},
        "severity": {"$ref": "#/$defs/level"},
        "member_ids": {"type": "array", "default": [], "items": {"type": "string"}}
      }
    },
    "move": {
      "type": "object",
      "additionalProperties": false,
      "required": ["from_member_id", "to_member_id", "week", "hours", "reason", "confidence"],
      "properties": {
        "from_member_id": {"type": "string"},
        "to_member_id": {"type": "string"},
        "week": {"type": "string", "format": "date", "description": "A forecast week's Monday, ISO 8601"},
        "hours": {"type": "number", "exclusiveMinimum": 0},
        "reason": {"type": "string", "minLength": 1, "maxLength": 600},
        "confidence": {"$ref": "#/$defs/level"},
        "task_keys": {"type": "array", "default": [], "items": {"type": "string"}, "description": "Open tasks of the source member to move"}
      }
    },
    "adjustment": {
      "type": "object",
      "additionalProperties": false,
      "required": ["member_id", "week", "delta_hours", "reason"],
      "properties": {
        "member_id": {"type": "string"},
        "week": {"type": "string", "format": "date"},
        "delta_hours": {"type": "number"},
        "reason": {"type": "string", "minLength": 1, "maxLength": 600}
      }
    }
  }
}
```

- [ ] **Step 5: `SkillTexts` and `Prompts`**

`ai/SkillTexts.java`:

```java
package com.workloadhub.forecast.ai;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The six product skills, classpath resources read once, in the order they appear in the system message. */
final class SkillTexts {

    static final List<String> NAMES = List.of("whf-domain", "whf-forecast-interpretation", "whf-pattern-discovery", "whf-likely-work",
            "whf-rebalancing-advice", "whf-report-style");

    record Skill(String name, String text) {

        /** The Markdown body without the YAML front matter, for embedding in the system message. */
        String body() {
            if (!text.startsWith("---\n")) {
                return text;
            }
            int end = text.indexOf("\n---\n", 4);
            return end < 0 ? text : text.substring(end + 5).strip();
        }
    }

    private SkillTexts() {
    }

    static List<Skill> load() {
        List<Skill> out = new ArrayList<>();
        for (String name : NAMES) {
            out.add(new Skill(name, resource("skills/" + name + "/SKILL.md")));
        }
        return List.copyOf(out);
    }

    static String resource(String path) {
        try (InputStream in = SkillTexts.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + path, e);
        }
    }
}
```

`ai/Prompts.java`:

```java
package com.workloadhub.forecast.ai;

import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import tools.jackson.databind.JsonNode;

/** What Copilot is told: the rules and the skills (system), the task and the contract (user), the problems (retry). */
public final class Prompts {

    public static final Set<String> SUPPORTED_LANGUAGES = Set.of("en", "fr");

    static final String RULES = """
            You are the workload analyst inside WorkloadHub AI Forecasting. A deterministic engine has
            already computed every number: demand, capacity, overload, intervals, pattern statistics, planned work and model quality.
            Your job is to read those facts through the tools and explain them to a team leader.

            Hard rules:
            1. Never invent, estimate or recompute a number. Every figure you write must come from a tool result,
               copied exactly as given (hours with one decimal, for example 12.5). If a number is not in the tools, do not write it.
            2. Use the tools. Start with get_run_overview, then query every member listed there, then the project timelines,
               the planned work and the rebalancing candidates. Do not answer before you have looked at every member.
            3. Answer with one JSON document that matches the contract in the user message. No prose before or after it,
               no Markdown fences. Field names and enumerations must match exactly. Member ids are the id strings from
               get_run_overview, copied exactly; task keys are copied exactly.
            4. Patterns need evidence: quote the statistic (name and value) that supports each statement.
            5. Rebalancing moves go from a member with overload to a member with spare capacity in the same week,
               respect the target's capacity, name the tasks moved, and give the hours moved and the reason.
            6. Write in the language given in the user message, plainly, for a busy team leader.

            The product skills below are the rules of this domain. Follow them.
            """;

    private final String systemMessage;
    private final String contractSchema;

    private Prompts(String systemMessage, String contractSchema) {
        this.systemMessage = systemMessage;
        this.contractSchema = contractSchema;
    }

    public static Prompts load() {
        StringBuilder sb = new StringBuilder(RULES);
        for (SkillTexts.Skill s : SkillTexts.load()) {
            sb.append("\n## Skill: ").append(s.name()).append("\n\n").append(s.body()).append('\n');
        }
        return new Prompts(sb.toString(), SkillTexts.resource("ai/contract.schema.json").strip());
    }

    public String systemMessage() {
        return systemMessage;
    }

    public String contractSchema() {
        return contractSchema;
    }

    public String userPrompt(JsonNode facts, String language) {
        JsonNode run = facts.path("run");
        JsonNode team = facts.path("team");
        StringJoiner members = new StringJoiner(", ");
        for (JsonNode m : facts.path("members")) {
            members.add("member_id " + m.path("id").asText() + " (" + m.path("name").asText() + ", " + m.path("role").asText("member") + ")");
        }
        StringJoiner weeks = new StringJoiner(", ");
        for (JsonNode w : run.path("weeks")) {
            weeks.add(w.asText());
        }
        String languageLine = language.equals("fr")
                ? "Language: fr. Rédigez chaque champ narratif en français; gardez les noms des membres et les clés des tâches tels quels."
                : "Language: en. Write every narrative field in English; keep member names and task keys as given.";
        return "Analyse forecast run " + run.path("id").asText() + " for team '" + team.path("name").asText() + "' (team id " + team.path("id").asText()
                + "), run date " + run.path("as_of").asText() + ", forecast weeks " + weeks + ".\n" + languageLine + "\n"
                + "Members to cover, each exactly once: " + members + ".\n\n"
                + "Procedure: 1) get_run_overview; 2) for each member: get_member_forecast, get_member_capacity, get_member_patterns, "
                + "get_member_history, get_member_open_tasks; 3) get_project_timelines; 4) get_planned_work; 5) get_rebalancing_candidates; "
                + "6) write the JSON document.\n\n"
                + "Contract (JSON Schema):\n" + contractSchema + "\n\n"
                + "Return only the JSON document.";
    }

    public String retryPrompt(List<String> problems) {
        StringBuilder sb = new StringBuilder("Your previous answer was rejected for these reasons:\n");
        for (String p : problems) {
            sb.append("- ").append(p).append('\n');
        }
        sb.append("\nFix every point and return only the JSON document, with no text around it.");
        return sb.toString();
    }
}
```

- [ ] **Step 6: Run the tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='SkillTextsTest,PromptsTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add server/forecast-core/src/main/resources/skills server/forecast-core/src/main/resources/ai server/forecast-core/src/main/java/com/workloadhub/forecast/ai server/forecast-core/src/test/java/com/workloadhub/forecast/ai
git commit -m "feat(server): product skills, prompts and the narrative contract schema for the Java narrator

The six skills are rewritten for the Java facts (xgboost and
seasonal_naive, 40 h, string ids, planned and likely work) and embedded
in a REPLACE system message, so the prompt is one deterministic string."
```

---

### Task 3: `NarrativeContract`: parse, validate, cross-check with the facts

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/NarrativeContract.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/NarrativeContractTest.java`, `.../ai/ContractSchemaTest.java`

**Interfaces:**
- Consumes: `Prompts.contractSchema()` (Task 2), `ExportFiles.mapper()`.
- Produces: `NarrativeContract.parse(String text) -> Narrative` (throws `NarrativeContract.ContractException` whose `problems()` lists every problem), `NarrativeContract.validateAgainstFacts(Narrative, JsonNode facts) -> List<String>`, the records `Narrative(String runSummary, List<Member> members, List<TeamRisk> teamRisks, List<Move> rebalancing, List<Adjustment> suggestedAdjustments, String modelNotes, JsonNode tree)` with `toJson()`, `Member(String memberId, String name, String riskLevel, String summary, List<PatternFinding> patterns, List<String> warnings, List<LikelyWork> likelyWork)`, `PatternFinding(String kind, String statement, String evidence)`, `LikelyWork(String statement, String evidence, String confidence)`, `TeamRisk(String title, String detail, String severity, List<String> memberIds)`, `Move(String fromMemberId, String toMemberId, LocalDate week, double hours, String reason, String confidence, List<String> taskKeys)`, `Adjustment(String memberId, LocalDate week, double deltaHours, String reason)`; the constant sets `NarrativeContract.LEVELS`, `NarrativeContract.KINDS`; `NarrativeContract.FIELDS` (`Map<String, Set<String>>` of object name to its allowed keys, for the schema drift test).

- [ ] **Step 1: Write the failing tests**

`ai/NarrativeContractTest.java`:

```java
package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NarrativeContract.ContractException;
import com.workloadhub.forecast.ai.NarrativeContract.Narrative;
import com.workloadhub.forecast.data.ExportFiles;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

class NarrativeContractTest {

    static final String A = "aaaaaaaa-0000-0000-0000-000000000004";
    static final String B = "aaaaaaaa-0000-0000-0000-000000000005";

    static final String FACTS = """
            {"run": {"id": "r", "as_of": "2026-09-03", "weeks": ["2026-09-07", "2026-09-14"]},
             "team": {"id": "t", "name": "Web Platform"},
             "members": [
               {"id": "%s", "name": "Sara Tazi", "open_tasks": [{"key": "WEB-1"}, {"key": "WEB-2"}],
                "forecast": [{"week": "2026-09-07", "demand": 52.0, "capacity": 40.0, "overload": 12.0},
                             {"week": "2026-09-14", "demand": 40.0, "capacity": 40.0, "overload": 0.0}]},
               {"id": "%s", "name": "Omar Benali", "open_tasks": [{"key": "WEB-9"}],
                "forecast": [{"week": "2026-09-07", "demand": 20.0, "capacity": 40.0, "overload": 0.0},
                             {"week": "2026-09-14", "demand": 35.0, "capacity": 40.0, "overload": 0.0}]}]}
            """.formatted(A, B);

    static JsonNode facts() {
        return ExportFiles.mapper().readTree(FACTS);
    }

    static ObjectNode good() {
        String json = """
                {"run_summary": "Two members, one overloaded in week one.",
                 "members": [
                   {"member_id": "%s", "name": "Sara Tazi", "risk_level": "high",
                    "summary": "Sara has 52.0 h of demand against 40.0 h of capacity in the week of 2026-09-07.",
                    "patterns": [{"kind": "assignment_style", "statement": "Mostly project-driven work.", "evidence": "share_project 0.6"}],
                    "warnings": ["Overload of 12.0 h in the week of 2026-09-07."],
                    "likely_work": [{"statement": "WEB-3 is likely to land.", "evidence": "planned WEB-3 share 0.7", "confidence": "high"}]},
                   {"member_id": "%s", "name": "Omar Benali", "risk_level": "low", "summary": "Spare capacity.", "patterns": [], "warnings": []}],
                 "team_risks": [{"title": "Week one overload", "detail": "One member above capacity.", "severity": "medium", "member_ids": ["%s"]}],
                 "rebalancing": [{"from_member_id": "%s", "to_member_id": "%s", "week": "2026-09-07", "hours": 8.0,
                                  "reason": "Omar has 20.0 h spare.", "confidence": "medium", "task_keys": ["WEB-1"]}],
                 "suggested_adjustments": [],
                 "model_notes": "Champion xgboost, MASE 0.9."}
                """.formatted(A, B, A, A, B);
        return (ObjectNode) ExportFiles.mapper().readTree(json);
    }

    static String text(JsonNode n) {
        return ExportFiles.mapper().writeValueAsString(n);
    }

    @Test
    void parsesPlainAndFencedJson() {
        Narrative n = NarrativeContract.parse(text(good()));
        assertEquals("high", n.members().get(0).riskLevel());
        assertEquals(LocalDate.of(2026, 9, 7), n.rebalancing().get(0).week());
        assertEquals(List.of("WEB-1"), n.rebalancing().get(0).taskKeys());
        assertEquals("high", n.members().get(0).likelyWork().get(0).confidence());
        assertEquals(List.of(), n.members().get(1).likelyWork(), "absent optional lists are empty");
        Narrative fenced = NarrativeContract.parse("```json\n" + text(good()) + "\n```");
        assertEquals(n.runSummary(), fenced.runSummary());
        Narrative wrapped = NarrativeContract.parse("Here it is: " + text(good()) + " Done.");
        assertEquals(n.modelNotes(), wrapped.modelNotes());
        assertTrue(n.toJson().startsWith("{") && n.toJson().contains("\"run_summary\""));
    }

    @Test
    void rejectsInvalidJsonUnknownFieldsAndBadValues() {
        ContractException notJson = assertThrows(ContractException.class, () -> NarrativeContract.parse("Here is my analysis: {"));
        assertTrue(notJson.problems().get(0).contains("not valid JSON"), notJson.problems().toString());
        ObjectNode extra = good();
        extra.put("extra_field", 1);
        assertTrue(problems(extra).stream().anyMatch(p -> p.contains("extra_field")));
        ObjectNode badLevel = good();
        ((ObjectNode) badLevel.path("members").get(0)).put("risk_level", "severe");
        assertTrue(problems(badLevel).stream().anyMatch(p -> p.startsWith("members[0].risk_level")));
        ObjectNode badKind = good();
        ((ObjectNode) badKind.path("members").get(0).path("patterns").get(0)).put("kind", "vibe");
        assertTrue(problems(badKind).stream().anyMatch(p -> p.startsWith("members[0].patterns[0].kind")));
        ObjectNode tooLong = good();
        ((ObjectNode) tooLong.path("members").get(0)).put("summary", "x".repeat(1201));
        assertTrue(problems(tooLong).stream().anyMatch(p -> p.startsWith("members[0].summary") && p.contains("1200")));
        ObjectNode zeroHours = good();
        ((ObjectNode) zeroHours.path("rebalancing").get(0)).put("hours", 0);
        assertTrue(problems(zeroHours).stream().anyMatch(p -> p.startsWith("rebalancing[0].hours")));
        ObjectNode badDate = good();
        ((ObjectNode) badDate.path("rebalancing").get(0)).put("week", "next monday");
        assertTrue(problems(badDate).stream().anyMatch(p -> p.startsWith("rebalancing[0].week")));
        ObjectNode fiveLikely = good();
        var list = ((ObjectNode) fiveLikely.path("members").get(0)).putArray("likely_work");
        for (int i = 0; i < 5; i++) {
            list.addObject().put("statement", "s").put("evidence", "e").put("confidence", "low");
        }
        assertTrue(problems(fiveLikely).stream().anyMatch(p -> p.startsWith("members[0].likely_work") && p.contains("4")));
        ObjectNode missing = good();
        ((ObjectNode) missing.path("members").get(1)).remove("summary");
        assertTrue(problems(missing).stream().anyMatch(p -> p.startsWith("members[1].summary")));
    }

    static List<String> problems(JsonNode n) {
        return assertThrows(ContractException.class, () -> NarrativeContract.parse(text(n))).problems();
    }

    @Test
    void crossChecksMembersWeeksAndMoves() {
        assertEquals(List.of(), NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(good())), facts()));

        ObjectNode unknown = good();
        ((ObjectNode) unknown.path("members").get(1)).put("member_id", "nobody");
        ((ObjectNode) unknown.path("rebalancing").get(0)).put("week", "2026-09-28");
        List<String> p1 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(unknown)), facts());
        assertTrue(p1.stream().anyMatch(s -> s.contains("nobody")) && p1.stream().anyMatch(s -> s.contains("2026-09-28")), p1.toString());
        assertTrue(p1.stream().anyMatch(s -> s.contains("missing") && s.contains(B)), "the replaced member is now missing");

        ObjectNode onlyOne = good();
        ((tools.jackson.databind.node.ArrayNode) onlyOne.path("members")).remove(1);
        List<String> p2 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(onlyOne)), facts());
        assertTrue(p2.stream().anyMatch(s -> s.contains("missing") && s.contains(B)), p2.toString());

        ObjectNode dup = good();
        ((tools.jackson.databind.node.ArrayNode) dup.path("members")).add(dup.path("members").get(0).deepCopy());
        List<String> p3 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(dup)), facts());
        assertTrue(p3.stream().anyMatch(s -> s.contains("more than once") && s.contains(A)), p3.toString());

        ObjectNode same = good();
        ((ObjectNode) same.path("rebalancing").get(0)).put("to_member_id", A);
        List<String> p4 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(same)), facts());
        assertTrue(p4.stream().anyMatch(s -> s.contains("same member")), p4.toString());

        ObjectNode beyond = good();
        ((ObjectNode) beyond.path("rebalancing").get(0)).put("hours", 20.0);
        List<String> p5 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(beyond)), facts());
        assertTrue(p5.stream().anyMatch(s -> s.contains("overload")), p5.toString());

        ObjectNode overfill = good();
        ((ObjectNode) overfill.path("rebalancing").get(0)).put("hours", 25.0);
        ObjectNode richFacts = (ObjectNode) facts();
        ((ObjectNode) richFacts.path("members").get(0).path("forecast").get(0)).put("overload", 30.0);
        List<String> p6 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(overfill)), richFacts);
        assertTrue(p6.stream().anyMatch(s -> s.contains("capacity")), p6.toString());

        ObjectNode wrongTask = good();
        ((ObjectNode) wrongTask.path("rebalancing").get(0)).putArray("task_keys").add("WEB-9").add("WEB-404");
        List<String> p7 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(wrongTask)), facts());
        assertTrue(p7.stream().anyMatch(s -> s.contains("WEB-9")) && p7.stream().anyMatch(s -> s.contains("WEB-404")), p7.toString());

        ObjectNode zeroDelta = good();
        zeroDelta.putArray("suggested_adjustments").addObject().put("member_id", A).put("week", "2026-09-07").put("delta_hours", 0).put("reason", "rest week");
        List<String> p8 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(zeroDelta)), facts());
        assertTrue(p8.stream().anyMatch(s -> s.contains("delta_hours")), p8.toString());

        ObjectNode riskUnknown = good();
        ((ObjectNode) riskUnknown.path("team_risks").get(0)).putArray("member_ids").add("ghost");
        List<String> p9 = NarrativeContract.validateAgainstFacts(NarrativeContract.parse(text(riskUnknown)), facts());
        assertTrue(p9.stream().anyMatch(s -> s.contains("ghost")), p9.toString());
    }
}
```

`ai/ContractSchemaTest.java` (the prompt's schema and the validator cannot drift apart):

```java
package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.workloadhub.forecast.data.ExportFiles;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ContractSchemaTest {

    static Set<String> keys(JsonNode object) {
        Set<String> out = new TreeSet<>();
        object.path("properties").propertyNames().forEach(out::add);
        return out;
    }

    @Test
    void theValidatorAndTheSchemaNameTheSameFields() {
        JsonNode schema = ExportFiles.mapper().readTree(Prompts.load().contractSchema());
        Map<String, Set<String>> fields = NarrativeContract.FIELDS;
        assertEquals(fields.get("narrative"), keys(schema));
        for (String def : new String[] {"member", "pattern", "likely_work", "team_risk", "move", "adjustment"}) {
            assertEquals(fields.get(def), keys(schema.path("$defs").path(def)), def);
        }
        Set<String> levels = new TreeSet<>();
        schema.path("$defs").path("level").path("enum").forEach(n -> levels.add(n.asText()));
        assertEquals(new TreeSet<>(NarrativeContract.LEVELS), levels);
        Set<String> kinds = new TreeSet<>();
        schema.path("$defs").path("pattern").path("properties").path("kind").path("enum").forEach(n -> kinds.add(n.asText()));
        assertEquals(new TreeSet<>(NarrativeContract.KINDS), kinds);
    }
}
```

If `JsonNode.propertyNames()` does not exist in the Jackson 3 version on the classpath, use `object.path("properties").properties().forEach(e -> out.add(e.getKey()))` (the `properties()` form is used in `DefaultForecastService.getRun` already).

- [ ] **Step 2: Run them to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='NarrativeContractTest,ContractSchemaTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement `NarrativeContract`**

`ai/NarrativeContract.java`:

```java
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
            "move", new TreeSet<>(Set.of("from_member_id", "to_member_id", "week", "hours", "reason", "confidence", "task_keys")),
            "adjustment", new TreeSet<>(Set.of("member_id", "week", "delta_hours", "reason")));

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

    record Move(String fromMemberId, String toMemberId, LocalDate week, double hours, String reason, String confidence, List<String> taskKeys) {
    }

    record Adjustment(String memberId, LocalDate week, double deltaHours, String reason) {
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
            moves.add(new Move(w.text(vn, p, "from_member_id", 1, 200, true), w.text(vn, p, "to_member_id", 1, 200, true), w.date(vn, p, "week"), hours,
                    w.text(vn, p, "reason", 1, 600, true), w.oneOf(vn, p, "confidence", LEVELS), w.strings(vn, p, "task_keys")));
        }
        List<Adjustment> adjustments = new ArrayList<>();
        i = 0;
        for (JsonNode an : w.array(tree, "", "suggested_adjustments", false)) {
            String p = "suggested_adjustments[" + i++ + "]";
            w.object(an, p, FIELDS.get("adjustment"));
            adjustments.add(new Adjustment(w.text(an, p, "member_id", 1, 200, true), w.date(an, p, "week"), w.number(an, p, "delta_hours"),
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
        Set<LocalDate> weeks = new HashSet<>();
        for (JsonNode w : facts.path("run").path("weeks")) {
            weeks.add(LocalDate.parse(w.asText().substring(0, 10)));
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
            if (mv.week() == null || !weeks.contains(mv.week())) {
                problems.add("rebalancing week " + mv.week() + " is not a forecast week");
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
            JsonNode source = forecastRow(memberNodes.get(mv.fromMemberId()), mv.week());
            JsonNode target = forecastRow(memberNodes.get(mv.toMemberId()), mv.week());
            if (source == null || target == null) {
                continue;
            }
            double overload = source.path("overload").asDouble(0);
            if (mv.hours() > overload + TOLERANCE) {
                problems.add("rebalancing move of " + fmt(mv.hours()) + " h from member " + mv.fromMemberId() + " in the week of " + mv.week()
                        + " exceeds their overload of " + fmt(overload) + " h; the source has no such overload to move");
            }
            double demand = target.path("demand").asDouble(0);
            double capacity = target.path("capacity").asDouble(0);
            if (demand + mv.hours() > capacity + TOLERANCE) {
                problems.add("rebalancing move of " + fmt(mv.hours()) + " h to member " + mv.toMemberId() + " in the week of " + mv.week()
                        + " would bring their demand to " + fmt(demand + mv.hours()) + " h, above their capacity of " + fmt(capacity) + " h");
            }
        }
        for (Adjustment a : n.suggestedAdjustments()) {
            if (!known.contains(a.memberId())) {
                problems.add("adjustment names unknown member " + a.memberId());
            }
            if (a.week() == null || !weeks.contains(a.week())) {
                problems.add("adjustment week " + a.week() + " is not a forecast week");
            }
            if (a.deltaHours() == 0 || !Double.isFinite(a.deltaHours())) {
                problems.add("suggested adjustment for member " + a.memberId() + " in the week of " + a.week() + " has a delta_hours of " + a.deltaHours()
                        + ", which is not a usable value");
            }
        }
        return problems;
    }

    private static JsonNode forecastRow(JsonNode member, LocalDate week) {
        if (member == null || week == null) {
            return null;
        }
        for (JsonNode row : member.path("forecast")) {
            String w = row.path("week").asText();
            if (w.length() >= 10 && w.substring(0, 10).equals(week.toString())) {
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
```

- [ ] **Step 4: Run the tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='NarrativeContractTest,ContractSchemaTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/ai/NarrativeContract.java server/forecast-core/src/test/java/com/workloadhub/forecast/ai
git commit -m "feat(server): the narrative contract, validated by hand over the JSON tree

Every problem names its path so the retry prompt can list them; the
allowed keys are one map the schema text is tested against, and the fact
checks port the Python ones plus task_keys against the source's open tasks."
```

---

### Task 4: `NumberVerifier`: every number in the narrative against the facts

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/NumberVerifier.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/NumberVerifierTest.java`, `.../ai/NumberVerifierPropertyTest.java`

**Interfaces:**
- Consumes: `NarrativeContract.Narrative` and its records (Task 3).
- Produces: `NumberVerifier.verify(Narrative, JsonNode facts) -> Report`, `record Report(int checked, List<String> unverified, Map<String, List<Double>> fields)` with `ok()` and `toJson()`, `NumberVerifier.factNumbers(JsonNode) -> Set<Double>`, `NumberVerifier.numbersInText(String) -> List<Double>`, `NumberVerifier.numbersWithUnits(String) -> List<NumberToken>` with `record NumberToken(double value, boolean hours)`, `NumberVerifier.round1(double)`, `NumberVerifier.SMALL_INTEGER_ALLOWANCE = 20`.

- [ ] **Step 1: Write the failing tests**

`ai/NumberVerifierTest.java`:

```java
package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NarrativeContract.Narrative;
import com.workloadhub.forecast.ai.NumberVerifier.Report;
import com.workloadhub.forecast.data.ExportFiles;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NumberVerifierTest {

    static final String A = "aaaaaaaa-0000-0000-0000-000000000004";
    static final String B = "aaaaaaaa-0000-0000-0000-000000000005";

    static final JsonNode FACTS = ExportFiles.mapper().readTree("""
            {"run": {"id": "r", "weeks": ["2026-09-07", "2026-09-14"], "generated_at": "2026-09-03T10:00:00", "horizons": [1, 2]},
             "team": {"id": "t", "totals": [{"week": "2026-09-07", "demand": 72.5, "capacity": 88.0}]},
             "members": [
               {"id": "%s", "name": "A", "forecast": [{"week": "2026-09-07", "demand": 52.04, "capacity": 40.0, "overload": 12.04}]},
               {"id": "%s", "name": "B", "forecast": [{"week": "2026-09-07", "demand": 20.5, "capacity": 40.0, "overload": 0.0}]}],
             "model": {"champion": "xgboost", "champion_mase": 0.913},
             "rebalancing_candidates": {
               "overloaded": [{"member_id": "%s", "name": "A", "overload_hours": 12.0}],
               "underloaded": [{"member_id": "%s", "name": "B", "spare_hours": 19.5}]}}
            """.formatted(A, B, A, B));

    static Narrative narrative(String summary, List<String> warnings, String runSummary, String extraJson) {
        String warn = String.join(",", warnings.stream().map(w -> "\"" + w + "\"").toList());
        String json = "{\"run_summary\": \"" + runSummary + "\", \"members\": ["
                + "{\"member_id\": \"" + A + "\", \"name\": \"A\", \"risk_level\": \"high\", \"summary\": \"" + summary + "\", \"patterns\": [], \"warnings\": [" + warn + "]},"
                + "{\"member_id\": \"" + B + "\", \"name\": \"B\", \"risk_level\": \"low\", \"summary\": \"fine\", \"patterns\": [], \"warnings\": []}]"
                + (extraJson.isEmpty() ? "" : ", " + extraJson) + "}";
        return NarrativeContract.parse(json);
    }

    static Narrative narrative(String summary) {
        return narrative(summary, List.of(), "ok", "");
    }

    @Test
    void factNumbersRoundToOneDecimalAndToIntegers() {
        Set<Double> nums = NumberVerifier.factNumbers(FACTS);
        assertTrue(nums.containsAll(Set.of(52.0, 12.0, 40.0, 20.5, 0.9, 1.0, 2.0)), nums.toString());
    }

    @Test
    void numbersInTextSkipDatesTimesAndPercentages() {
        assertEquals(List.of(52.0, 40.0, 0.91), NumberVerifier.numbersInText("In the week of 2026-09-07 at 10:30, demand is 52.0 h (30% above 40 h), MASE 0.91."));
    }

    @Test
    void numbersGluedToAWordAreExtracted() {
        assertEquals(List.of(0.91), NumberVerifier.numbersInText("MASE0.91"));
        assertEquals(List.of(52.5), NumberVerifier.numbersInText("demand52.5h"));
    }

    @Test
    void thousandsSeparatorsDoNotBreakDecimalCommas() {
        assertEquals(List.of(1200.0), NumberVerifier.numbersInText("1,200 h"));
        assertEquals(List.of(1200.5), NumberVerifier.numbersInText("1 200,5 h"));
        assertEquals(List.of(52.5), NumberVerifier.numbersInText("52,5 h"));
        assertEquals(List.of(12.34), NumberVerifier.numbersInText("12,34"));
    }

    @Test
    void verifiedWhenEveryNumberMatchesTheFacts() {
        Report r = NumberVerifier.verify(narrative("Demand 52.0 h against 40 h, overload 12.0 h in week 2026-09-07."), FACTS);
        assertTrue(r.ok() && r.checked() == 3 && r.unverified().isEmpty(), r.toString());
        assertTrue(r.toJson().contains("\"checked\":3") || r.toJson().contains("\"checked\" : 3"));
    }

    @Test
    void anUnverifiedNumberIsReportedWithItsField() {
        Report r = NumberVerifier.verify(narrative("Demand will reach 63.5 h.", List.of("Expect 12 h overload."), "ok", ""), FACTS);
        assertFalse(r.ok());
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("63.5") && u.contains("members[0].summary")), r.unverified().toString());
        assertEquals(2, r.checked(), "12 h matches member A's own overload of 12.04, rounded");
    }

    @Test
    void smallIntegersWithoutAnHoursUnitAreNeverFlagged() {
        assertTrue(NumberVerifier.verify(narrative("Over " + NumberVerifier.SMALL_INTEGER_ALLOWANCE + " tasks in 2 weeks, 13 weeks of history."), FACTS).ok());
    }

    @Test
    void aSmallNumberWrittenAsHoursIsChecked() {
        Report r = NumberVerifier.verify(narrative("Overload of 8 h in week 2026-09-07."), FACTS);
        assertFalse(r.ok());
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("8") && u.contains("members[0].summary")));
    }

    @Test
    void theHoursUnitIsRecognisedInItsCommonSpellings() {
        for (String written : List.of("8 h", "8h", "8 hrs", "8 hours", "8 hour", "8 heures")) {
            assertFalse(NumberVerifier.verify(narrative("Overload of " + written + "."), FACTS).ok(), written);
        }
        assertTrue(NumberVerifier.verify(narrative("8 high-priority tasks arrive."), FACTS).ok(), "a word starting with h is not a unit");
    }

    @Test
    void aMembersTextMayNotCiteAnotherMembersNumber() {
        Report r = NumberVerifier.verify(narrative("Demand is 20.5 h."), FACTS);
        assertFalse(r.ok());
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("20.5") && u.contains("members[0].summary") && u.contains("not of this field")));
    }

    @Test
    void aMembersTextMayCiteRunTeamAndModelNumbers() {
        assertTrue(NumberVerifier.verify(narrative("Demand 52.0 h of the team's 72.5 h against 88.0 h, MASE 0.91."), FACTS).ok());
    }

    @Test
    void aMembersTextMayCiteTheirOwnRebalancingCandidacyOnly() {
        Report r = NumberVerifier.verify(narrative("Overload of 12.0 h; B has 19.5 h spare."), FACTS);
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("19.5")), "B's spare hours belong in B's section");
        assertTrue(NumberVerifier.verify(narrative("Overload of 12.0 h."), FACTS).ok());
    }

    @Test
    void teamLevelTextMayCiteAnyMembersNumber() {
        assertTrue(NumberVerifier.verify(narrative("fine", List.of(), "A is at 52.0 h, B at 20.5 h against 40.0 h.", ""), FACTS).ok());
    }

    @Test
    void aRebalancingReasonMayCiteTheMovesOwnHours() {
        String move = "\"rebalancing\": [{\"from_member_id\": \"" + A + "\", \"to_member_id\": \"" + B + "\", \"week\": \"2026-09-07\", \"hours\": 6.5,"
                + " \"reason\": \"Move 6.5 h of A's 12.0 h overload to B, who has 19.5 h spare.\", \"confidence\": \"high\"}]";
        assertTrue(NumberVerifier.verify(narrative("fine", List.of(), "ok", move), FACTS).ok());
    }

    @Test
    void aValueThatOnlyRoundsOntoAnUnrelatedFactIsFlagged() {
        Report r = NumberVerifier.verify(narrative("Overload of 3.5 h."), FACTS);
        assertFalse(r.ok());
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("3.5")));
    }

    @Test
    void anAdjustmentReasonMayCiteItsOwnDeltaHours() {
        String adj = "\"suggested_adjustments\": [{\"member_id\": \"" + A + "\", \"week\": \"2026-09-07\", \"delta_hours\": -2.5,"
                + " \"reason\": \"Trim 2.5 h: the audit day is already counted in capacity.\"}]";
        assertTrue(NumberVerifier.verify(narrative("fine", List.of(), "ok", adj), FACTS).ok());
    }

    @Test
    void likelyWorkTextIsScopedLikeTheMembersOtherText() {
        String likely = "\"likely_work\": [{\"statement\": \"WEB-3 probably lands.\", \"evidence\": \"spare 19.5 h\", \"confidence\": \"low\"}]";
        String json = "{\"run_summary\": \"ok\", \"members\": [{\"member_id\": \"" + A + "\", \"name\": \"A\", \"risk_level\": \"low\", \"summary\": \"fine\", " + likely + "},"
                + "{\"member_id\": \"" + B + "\", \"name\": \"B\", \"risk_level\": \"low\", \"summary\": \"fine\"}]}";
        Report r = NumberVerifier.verify(NarrativeContract.parse(json), FACTS);
        assertTrue(r.unverified().stream().anyMatch(u -> u.contains("members[0].likely_work[0].evidence") && u.contains("19.5")), r.unverified().toString());
    }
}
```

`ai/NumberVerifierPropertyTest.java`:

```java
package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NumberVerifier.Report;
import com.workloadhub.forecast.data.ExportFiles;
import java.util.List;
import java.util.StringJoiner;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.Size;
import tools.jackson.databind.JsonNode;

class NumberVerifierPropertyTest {

    static JsonNode facts(List<Double> values) {
        StringJoiner totals = new StringJoiner(",");
        for (double v : values) {
            totals.add("{\"week\": \"2026-09-07\", \"demand\": " + v + "}");
        }
        return ExportFiles.mapper().readTree("{\"run\": {\"weeks\": [\"2026-09-07\"]}, \"team\": {\"totals\": [" + totals + "]}, \"members\": []}");
    }

    static NarrativeContract.Narrative withSummary(String runSummary) {
        return NarrativeContract.parse("{\"run_summary\": \"" + runSummary + "\", \"members\": []}");
    }

    @Property
    void everyFactWrittenWithOneDecimalInATeamFieldIsVerified(@ForAll @Size(min = 1, max = 8) List<@DoubleRange(min = 0, max = 500) Double> values) {
        StringJoiner text = new StringJoiner(", ");
        for (double v : values) {
            text.add(Double.toString(NumberVerifier.round1(v)) + " h");
        }
        Report r = NumberVerifier.verify(withSummary("Totals: " + text + "."), facts(values));
        assertTrue(r.ok(), r.unverified().toString());
        assertTrue(r.checked() == values.size());
    }

    @Property
    void aNumberAbsentFromTheFactsIsReportedWithItsField(@ForAll @Size(min = 1, max = 8) List<@DoubleRange(min = 0, max = 500) Double> values) {
        double invented = NumberVerifier.round1(values.stream().mapToDouble(Double::doubleValue).max().orElse(0)) + 7.3;
        Report r = NumberVerifier.verify(withSummary("Demand will reach " + Double.toString(NumberVerifier.round1(invented)) + " h."), facts(values));
        assertTrue(!r.ok(), "invented " + invented);
        assertTrue(r.unverified().get(0).startsWith("run_summary:"), r.unverified().toString());
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='NumberVerifierTest,NumberVerifierPropertyTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement `NumberVerifier`**

`ai/NumberVerifier.java`:

```java
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
```

`NarrativeContract.fmt` is the package-private static of Task 3.

- [ ] **Step 4: Run the tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='NumberVerifierTest,NumberVerifierPropertyTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If `numbersGluedToAWordAreExtracted` fails on `demand52.5h`, the culprit is the `\b` after `h` in `HOURS_UNIT` at end of input: Java's `\b` at end of string after a letter is a boundary, so it passes; if it does not, replace `\\b` with `(?![\\p{L}\\d_])`.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/ai/NumberVerifier.java server/forecast-core/src/test/java/com/workloadhub/forecast/ai
git commit -m "feat(server): verify every number of a narrative against the facts it may cite

Port of the Python verifier with string member ids and likely-work text
in the member scope; two jqwik properties pin that facts written with one
decimal verify and that an invented number is reported with its field."
```

---

### Task 5: `Usage`: one shape from the session metrics, the streamed events, or nothing

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/UsageMetrics.java`, `.../ai/UsageEvent.java`, `.../ai/Usage.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/UsageTest.java`

**Interfaces:**
- Produces: `record UsageMetrics(Double totalNanoAiu, Long totalUserRequests, Double totalPremiumRequestCost, Long totalApiDurationMs, Map<String, ModelMetric> models)` with nested `record ModelMetric(long requests, long inputTokens, long outputTokens, long cacheReadTokens, Long reasoningTokens)`; `record UsageEvent(String model, Long inputTokens, Long outputTokens, Long cacheReadTokens, Long reasoningTokens)`; `Usage.empty()`, `Usage.fromMetrics(UsageMetrics)`, `Usage.fromEvents(List<UsageEvent>)` each returning `Map<String, Object>` with the keys `input_tokens, output_tokens, cache_read_tokens, reasoning_tokens, requests, premium_requests, ai_credits, usd, api_seconds, models, source`; `Usage.toJson(Map<String, Object>)`.

- [ ] **Step 1: Write the failing test**

`ai/UsageTest.java`:

```java
package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class UsageTest {

    static final Set<String> KEYS = Set.of("input_tokens", "output_tokens", "cache_read_tokens", "reasoning_tokens", "requests", "premium_requests",
            "ai_credits", "usd", "api_seconds", "models", "source");

    static UsageMetrics metrics(Double nanoAiu) {
        Map<String, UsageMetrics.ModelMetric> models = new LinkedHashMap<>();
        models.put("gpt-5", new UsageMetrics.ModelMetric(3, 1000, 200, 800, 0L));
        models.put("gpt-5-mini", new UsageMetrics.ModelMetric(1, 250, 50, 0, null));
        return new UsageMetrics(nanoAiu, 4L, 1.5, 12500L, models);
    }

    @Test
    void emptyHasEveryKeyAndNoSource() {
        Map<String, Object> u = Usage.empty();
        assertEquals(KEYS, u.keySet());
        assertEquals("none", u.get("source"));
        assertEquals(Map.of(), u.get("models"));
        for (String k : KEYS) {
            if (!k.equals("models") && !k.equals("source")) {
                assertNull(u.get(k), k);
            }
        }
    }

    @Test
    void metricsCarryTheCreditsTheMoneyAndTheTokens() {
        Map<String, Object> u = Usage.fromMetrics(metrics(3.2e9));
        assertEquals(KEYS, u.keySet());
        assertEquals("metrics", u.get("source"));
        assertEquals(1250L, u.get("input_tokens"));
        assertEquals(250L, u.get("output_tokens"));
        assertEquals(800L, u.get("cache_read_tokens"));
        assertEquals(0L, u.get("reasoning_tokens"), "a model that reports no reasoning tokens used zero");
        assertEquals(4L, u.get("requests"));
        assertEquals(1.5, u.get("premium_requests"));
        assertEquals(3.2, (Double) u.get("ai_credits"), 1e-9);
        assertEquals(0.032, (Double) u.get("usd"), 1e-9);
        assertEquals(12.5, u.get("api_seconds"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> models = (Map<String, Map<String, Object>>) u.get("models");
        assertEquals(Map.of("requests", 3L, "input_tokens", 1000L, "output_tokens", 200L), models.get("gpt-5"));
        assertEquals(Map.of("requests", 1L, "input_tokens", 250L, "output_tokens", 50L), models.get("gpt-5-mini"));
        assertTrue(Usage.toJson(u).contains("\"source\""));
    }

    @Test
    void metricsWithoutCreditsReportNoMoney() {
        Map<String, Object> u = Usage.fromMetrics(metrics(null));
        assertNull(u.get("ai_credits"));
        assertNull(u.get("usd"));
        assertEquals(1.5, u.get("premium_requests"));
        assertEquals("metrics", u.get("source"));
    }

    @Test
    void eventsSumTheTokensAndCountTheRequests() {
        Map<String, Object> u = Usage.fromEvents(List.of(new UsageEvent("gpt-5", 100L, 50L, 10L, null), new UsageEvent("gpt-5", 30L, 5L, 0L, null)));
        assertEquals(KEYS, u.keySet());
        assertEquals("events", u.get("source"));
        assertEquals(130L, u.get("input_tokens"));
        assertEquals(55L, u.get("output_tokens"));
        assertEquals(10L, u.get("cache_read_tokens"));
        assertNull(u.get("reasoning_tokens"), "no event carried it: unknown, not zero");
        assertEquals(2L, u.get("requests"));
        assertEquals(Map.of("gpt-5", Map.of("requests", 2L, "input_tokens", 130L, "output_tokens", 55L)), u.get("models"));
        assertNull(u.get("premium_requests"));
        assertNull(u.get("ai_credits"));
        assertNull(u.get("usd"));
        assertNull(u.get("api_seconds"));
    }

    @Test
    void noEventsIsAZeroRequestNarration() {
        Map<String, Object> u = Usage.fromEvents(List.of());
        assertEquals("events", u.get("source"));
        assertEquals(0L, u.get("requests"));
        assertNull(u.get("input_tokens"));
        assertEquals(Map.of(), u.get("models"));
    }

    @Provide
    Arbitrary<List<UsageEvent>> events() {
        Arbitrary<Long> count = Arbitraries.longs().between(0, 1_000_000).injectNull(0.3);
        return Combinators.combine(count, count, count, count).as((in, out, cache, reasoning) -> new UsageEvent(null, in, out, cache, reasoning))
                .list().ofMaxSize(12);
    }

    @Property
    void eventsTotalExactlyWhatTheEventsCarried(@ForAll("events") List<UsageEvent> events) {
        Map<String, Object> u = Usage.fromEvents(events);
        assertEquals((long) events.size(), u.get("requests"));
        check(u, "input_tokens", events.stream().map(UsageEvent::inputTokens).toList());
        check(u, "output_tokens", events.stream().map(UsageEvent::outputTokens).toList());
        check(u, "cache_read_tokens", events.stream().map(UsageEvent::cacheReadTokens).toList());
        check(u, "reasoning_tokens", events.stream().map(UsageEvent::reasoningTokens).toList());
    }

    static void check(Map<String, Object> u, String key, List<Long> reported) {
        List<Long> present = new ArrayList<>(reported.stream().filter(v -> v != null).toList());
        if (present.isEmpty()) {
            assertNull(u.get(key), key);
        } else {
            assertEquals(present.stream().mapToLong(Long::longValue).sum(), u.get(key), key);
        }
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=UsageTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`ai/UsageMetrics.java`:

```java
package com.workloadhub.forecast.ai;

import java.util.Map;

/** The session's billed usage as the SDK's usage RPC reports it, reduced to what the module keeps. */
record UsageMetrics(Double totalNanoAiu, Long totalUserRequests, Double totalPremiumRequestCost, Long totalApiDurationMs,
        Map<String, ModelMetric> models) {

    record ModelMetric(long requests, long inputTokens, long outputTokens, long cacheReadTokens, Long reasoningTokens) {
    }
}
```

`ai/UsageEvent.java`:

```java
package com.workloadhub.forecast.ai;

/** One streamed usage event: tokens per model call, nothing about money; a field an event did not carry is null. */
record UsageEvent(String model, Long inputTokens, Long outputTokens, Long cacheReadTokens, Long reasoningTokens) {
}
```

`ai/Usage.java`:

```java
package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.data.ExportFiles;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What one narration cost, in one shape whatever the source. The session metrics are the billed truth (credits,
 * premium requests, API time); the streamed events carry tokens only. Unknown is null, never a plausible zero;
 * {@code models} is the one exception (an empty map). One AI credit is one US cent; the SDK reports nano-AI units.
 */
final class Usage {

    static final double NANO_PER_CREDIT = 1e9;
    static final double CREDITS_PER_USD = 100.0;

    private Usage() {
    }

    static Map<String, Object> empty() {
        Map<String, Object> u = new LinkedHashMap<>();
        u.put("input_tokens", null);
        u.put("output_tokens", null);
        u.put("cache_read_tokens", null);
        u.put("reasoning_tokens", null);
        u.put("requests", null);
        u.put("premium_requests", null);
        u.put("ai_credits", null);
        u.put("usd", null);
        u.put("api_seconds", null);
        u.put("models", new LinkedHashMap<String, Object>());
        u.put("source", "none");
        return u;
    }

    static Map<String, Object> fromMetrics(UsageMetrics m) {
        Map<String, Object> u = empty();
        long input = 0;
        long output = 0;
        long cache = 0;
        long reasoning = 0;
        Map<String, Object> models = new LinkedHashMap<>();
        for (Map.Entry<String, UsageMetrics.ModelMetric> e : m.models().entrySet()) {
            UsageMetrics.ModelMetric mm = e.getValue();
            input += mm.inputTokens();
            output += mm.outputTokens();
            cache += mm.cacheReadTokens();
            reasoning += mm.reasoningTokens() == null ? 0 : mm.reasoningTokens();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("requests", mm.requests());
            entry.put("input_tokens", mm.inputTokens());
            entry.put("output_tokens", mm.outputTokens());
            models.put(e.getKey(), entry);
        }
        Double credits = m.totalNanoAiu() == null ? null : m.totalNanoAiu() / NANO_PER_CREDIT;
        u.put("input_tokens", input);
        u.put("output_tokens", output);
        u.put("cache_read_tokens", cache);
        u.put("reasoning_tokens", reasoning);
        u.put("requests", m.totalUserRequests());
        u.put("premium_requests", m.totalPremiumRequestCost());
        u.put("ai_credits", credits);
        u.put("usd", credits == null ? null : credits / CREDITS_PER_USD);
        u.put("api_seconds", m.totalApiDurationMs() == null ? null : m.totalApiDurationMs() / 1000.0);
        u.put("models", models);
        u.put("source", "metrics");
        return u;
    }

    static Map<String, Object> fromEvents(List<UsageEvent> events) {
        Map<String, Object> u = empty();
        u.put("input_tokens", sum(events, UsageEvent::inputTokens));
        u.put("output_tokens", sum(events, UsageEvent::outputTokens));
        u.put("cache_read_tokens", sum(events, UsageEvent::cacheReadTokens));
        u.put("reasoning_tokens", sum(events, UsageEvent::reasoningTokens));
        u.put("requests", (long) events.size());
        Map<String, Object> models = new LinkedHashMap<>();
        for (UsageEvent e : events) {
            if (e.model() == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) models.computeIfAbsent(e.model(), k -> {
                Map<String, Object> fresh = new LinkedHashMap<>();
                fresh.put("requests", 0L);
                fresh.put("input_tokens", 0L);
                fresh.put("output_tokens", 0L);
                return fresh;
            });
            entry.put("requests", (Long) entry.get("requests") + 1);
            entry.put("input_tokens", (Long) entry.get("input_tokens") + (e.inputTokens() == null ? 0 : e.inputTokens()));
            entry.put("output_tokens", (Long) entry.get("output_tokens") + (e.outputTokens() == null ? 0 : e.outputTokens()));
        }
        u.put("models", models);
        u.put("source", "events");
        return u;
    }

    /** The sum of the events that carried the field, or null when none did. */
    private static Long sum(List<UsageEvent> events, Function<UsageEvent, Long> field) {
        Long total = null;
        for (UsageEvent e : events) {
            Long v = field.apply(e);
            if (v != null) {
                total = (total == null ? 0 : total) + v;
            }
        }
        return total;
    }

    static String toJson(Map<String, Object> usage) {
        return ExportFiles.mapper().writeValueAsString(usage);
    }
}
```

- [ ] **Step 4: Run the test**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=UsageTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/ai/Usage*.java server/forecast-core/src/test/java/com/workloadhub/forecast/ai/UsageTest.java
git commit -m "feat(server): one usage shape for a narration's cost, from metrics, events or nothing

Unknown stays null so a caller never shows a zero that means 'not known';
credits and dollars follow the Python conversion."
```

---

### Task 6: `ToolSpec` and `FactsTools`: the nine read-only tools over the facts

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/ToolSpec.java`, `.../ai/FactsTools.java`
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/testing/SeededFacts.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/FactsToolsTest.java`

**Interfaces:**
- Consumes: `FactsBuilder.build`, `ForecastRunner.prepare/forTeam`, `SeededData` (existing).
- Produces: `public record ToolSpec(String name, String description, boolean memberScoped, Function<String, Object> handler)`; `FactsTools(JsonNode facts)`, `FactsTools.NAMES` (`List<String>`, the nine names in order), `FactsTools.specs() -> List<ToolSpec>`, and the nine lookups returning `Map<String, Object>`: `runOverview()`, `memberHistory(id)`, `memberForecast(id)`, `memberPatterns(id)`, `memberOpenTasks(id)`, `memberCapacity(id)`, `projectTimelines()`, `rebalancingCandidates()`, `plannedWork()`; `SeededFacts.facts() -> JsonNode` and `SeededFacts.factsJson() -> String` (a real run's facts on the seeded data, cached per JVM).

- [ ] **Step 1: The seeded-facts test helper**

`testing/SeededFacts.java`:

```java
package com.workloadhub.forecast.testing;

import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.facts.FactsBuilder;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.run.Prepared;
import com.workloadhub.forecast.run.TeamOutcome;
import java.time.LocalDateTime;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** The facts of one real forecast (seasonal-naive forced, planned work on) for the first team with members of {@link SeededData}. */
public final class SeededFacts {

    public static final UUID RUN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static String json;

    private SeededFacts() {
    }

    public static synchronized String factsJson() {
        if (json == null) {
            ForecastData data = SeededData.data();
            UUID team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
            ForecastRunner runner = new ForecastRunner(new CapacityRule(40), true);
            Prepared prepared = runner.prepare(data, SeededData.asOf(), "seasonal_naive", (phase, percent, message) -> { });
            TeamOutcome outcome = runner.forTeam(prepared, team, null);
            json = FactsBuilder.toJson(FactsBuilder.build(outcome, RUN_ID, LocalDateTime.of(2026, 9, 6, 12, 0)));
        }
        return json;
    }

    public static JsonNode facts() {
        return ExportFiles.mapper().readTree(factsJson());
    }
}
```

- [ ] **Step 2: Write the failing test**

`ai/FactsToolsTest.java`:

```java
package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.testing.SeededFacts;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class FactsToolsTest {

    static final JsonNode FACTS = SeededFacts.facts();
    static final FactsTools TOOLS = new FactsTools(FACTS);
    static final String FIRST = FACTS.path("members").get(0).path("id").asText();

    @Test
    void nineSpecsInTheDocumentedOrderWithTheirScope() {
        List<ToolSpec> specs = TOOLS.specs();
        assertEquals(FactsTools.NAMES, specs.stream().map(ToolSpec::name).toList());
        assertEquals(List.of("get_run_overview", "get_member_history", "get_member_forecast", "get_member_patterns", "get_member_open_tasks",
                "get_member_capacity", "get_project_timelines", "get_rebalancing_candidates", "get_planned_work"), FactsTools.NAMES);
        for (ToolSpec s : specs) {
            assertEquals(s.name().startsWith("get_member_"), s.memberScoped(), s.name());
            assertTrue(s.description().length() > 20, s.name());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyToolReturnsItsDocumentedKeys() {
        Map<String, Object> overview = TOOLS.runOverview();
        assertEquals(Set.of("run", "team", "members", "model", "rebalancing_candidates", "data_quality", "pending_holidays", "how_to_proceed"), overview.keySet());
        Map<String, Object> team = (Map<String, Object>) overview.get("team");
        assertTrue(!team.containsKey("planned_backlog"), "the backlog is get_planned_work's");
        List<Map<String, Object>> members = (List<Map<String, Object>>) overview.get("members");
        assertEquals(Set.of("id", "name", "role"), members.get(0).keySet());
        assertEquals(FACTS.path("members").size(), members.size());

        assertEquals(Set.of("member_id", "name", "history_13w", "logged_hours_4w", "unlogged_tasks", "reopened_tasks"), TOOLS.memberHistory(FIRST).keySet());
        assertEquals(Set.of("member_id", "name", "forecast"), TOOLS.memberForecast(FIRST).keySet());
        assertEquals(Set.of("member_id", "name", "patterns"), TOOLS.memberPatterns(FIRST).keySet());
        assertEquals(Set.of("member_id", "name", "open_tasks"), TOOLS.memberOpenTasks(FIRST).keySet());
        Map<String, Object> capacity = TOOLS.memberCapacity(FIRST);
        assertEquals(Set.of("member_id", "name", "weeks"), capacity.keySet());
        List<Map<String, Object>> weeks = (List<Map<String, Object>>) capacity.get("weeks");
        assertEquals(2, weeks.size());
        assertEquals(Set.of("week", "capacity", "demand", "overload", "working_days", "absence_hours"), weeks.get(0).keySet());
        assertEquals(Set.of("weeks", "projects"), TOOLS.projectTimelines().keySet());
        assertEquals(Set.of("overloaded", "underloaded"), TOOLS.rebalancingCandidates().keySet());
        Map<String, Object> planned = TOOLS.plannedWork();
        assertEquals(Set.of("planned_backlog", "members"), planned.keySet());
        List<Map<String, Object>> plannedMembers = (List<Map<String, Object>>) planned.get("members");
        assertEquals(Set.of("id", "name", "likely_work"), plannedMembers.get(0).keySet());
    }

    @Test
    @SuppressWarnings("unchecked")
    void forecastNumbersAreTheFactsNumbers() {
        Map<String, Object> forecast = TOOLS.memberForecast(FIRST);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) forecast.get("forecast");
        JsonNode factRows = FACTS.path("members").get(0).path("forecast");
        assertEquals(factRows.size(), rows.size());
        assertEquals(factRows.get(0).path("demand").asDouble(), ((Number) rows.get(0).get("demand")).doubleValue(), 0.0);
        assertEquals(factRows.get(0).path("week").asText(), rows.get(0).get("week"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anUnknownMemberIsAnErrorNamingTheKnownIds() {
        Map<String, Object> r = TOOLS.memberForecast("nobody");
        assertEquals("unknown member_id nobody", r.get("error"));
        List<String> known = (List<String>) r.get("known_member_ids");
        assertTrue(known.contains(FIRST));
        assertEquals(known.stream().sorted().toList(), known, "sorted");
    }

    @Test
    void specsDispatchToTheLookups() {
        ToolSpec forecast = TOOLS.specs().stream().filter(s -> s.name().equals("get_member_forecast")).findFirst().orElseThrow();
        assertEquals(TOOLS.memberForecast(FIRST), forecast.handler().apply(FIRST));
        ToolSpec overview = TOOLS.specs().get(0);
        assertEquals(TOOLS.runOverview(), overview.handler().apply(null));
    }
}
```

- [ ] **Step 3: Run it to see it fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=FactsToolsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 4: Implement**

`ai/ToolSpec.java`:

```java
package com.workloadhub.forecast.ai;

import java.util.function.Function;

/**
 * One read-only tool over a run's facts. A member-scoped tool takes {@code member_id} (a string) and the handler
 * receives it; a plain tool's handler receives null. The result is a JSON-shaped map or list.
 */
public record ToolSpec(String name, String description, boolean memberScoped, Function<String, Object> handler) {
}
```

`ai/FactsTools.java`:

```java
package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.data.ExportFiles;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/** The nine tools Copilot may call, reading one run's stored facts and never the database. */
final class FactsTools {

    static final List<String> NAMES = List.of("get_run_overview", "get_member_history", "get_member_forecast", "get_member_patterns",
            "get_member_open_tasks", "get_member_capacity", "get_project_timelines", "get_rebalancing_candidates", "get_planned_work");

    private final JsonNode facts;
    private final Map<String, JsonNode> members = new TreeMap<>();

    FactsTools(JsonNode facts) {
        this.facts = facts;
        for (JsonNode m : facts.path("members")) {
            members.put(m.path("id").asText(), m);
        }
    }

    List<ToolSpec> specs() {
        return List.of(
                new ToolSpec("get_run_overview", "Run, team, member list, model quality, rebalancing candidates and data quality. Call this first.", false,
                        id -> runOverview()),
                new ToolSpec("get_member_history", "Last 13 weeks of task arrivals (hours and counts), logged hours of the last 4 weeks, unlogged and reopened tasks for one member.",
                        true, this::memberHistory),
                new ToolSpec("get_member_forecast", "Two-week forecast rows (demand, low, high, capacity, overload, open, new and planned hours, due hours) for one member.",
                        true, this::memberForecast),
                new ToolSpec("get_member_patterns", "Deterministic pattern statistics for one member (assignment style, weekday rhythm, trend, estimate bias, cycle time, lateness, cluster, backlog).",
                        true, this::memberPatterns),
                new ToolSpec("get_member_open_tasks", "Open tasks of one member with keys, estimates, due dates, overdue flags and project keys.", true,
                        this::memberOpenTasks),
                new ToolSpec("get_member_capacity", "Capacity, demand and overload per forecast week for one member, with working days and absence hours.", true,
                        this::memberCapacity),
                new ToolSpec("get_project_timelines", "Projects of the team with status, open and backlog task counts and the first due date, plus the forecast weeks.",
                        false, id -> projectTimelines()),
                new ToolSpec("get_rebalancing_candidates", "Members with overload and members with spare capacity over the two weeks.", false,
                        id -> rebalancingCandidates()),
                new ToolSpec("get_planned_work", "The team's planned backlog per project and, per member, the planned tasks, project roles and recent mix (likely work).",
                        false, id -> plannedWork()));
    }

    private static Object plain(JsonNode node) {
        return ExportFiles.mapper().treeToValue(node, Object.class);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private Map<String, Object> unknown(String id) {
        return map("error", "unknown member_id " + id, "known_member_ids", new ArrayList<>(members.keySet()));
    }

    Map<String, Object> runOverview() {
        List<Object> list = new ArrayList<>();
        for (JsonNode m : facts.path("members")) {
            list.add(map("id", m.path("id").asText(), "name", m.path("name").asText(), "role", m.path("role").asText(null)));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> team = (Map<String, Object>) plain(facts.path("team"));
        team.remove("planned_backlog");
        return map("run", plain(facts.path("run")), "team", team, "members", list, "model", plain(facts.path("model")),
                "rebalancing_candidates", plain(facts.path("rebalancing_candidates")), "data_quality", plain(facts.path("data_quality")),
                "pending_holidays", plain(facts.path("pending_holidays")),
                "how_to_proceed", "Call get_member_forecast, get_member_capacity, get_member_patterns, get_member_history and get_member_open_tasks"
                        + " for every member id listed here, then get_project_timelines, get_planned_work and get_rebalancing_candidates, then answer with the JSON document.");
    }

    Map<String, Object> memberHistory(String id) {
        JsonNode m = members.get(id);
        if (m == null) {
            return unknown(id);
        }
        return map("member_id", id, "name", m.path("name").asText(), "history_13w", plain(m.path("history_13w")), "logged_hours_4w", plain(m.path("logged_hours_4w")),
                "unlogged_tasks", plain(m.path("unlogged_tasks")), "reopened_tasks", plain(m.path("reopened_tasks")));
    }

    Map<String, Object> memberForecast(String id) {
        JsonNode m = members.get(id);
        return m == null ? unknown(id) : map("member_id", id, "name", m.path("name").asText(), "forecast", plain(m.path("forecast")));
    }

    Map<String, Object> memberPatterns(String id) {
        JsonNode m = members.get(id);
        return m == null ? unknown(id) : map("member_id", id, "name", m.path("name").asText(), "patterns", plain(m.path("patterns")));
    }

    Map<String, Object> memberOpenTasks(String id) {
        JsonNode m = members.get(id);
        return m == null ? unknown(id) : map("member_id", id, "name", m.path("name").asText(), "open_tasks", plain(m.path("open_tasks")));
    }

    Map<String, Object> memberCapacity(String id) {
        JsonNode m = members.get(id);
        if (m == null) {
            return unknown(id);
        }
        List<Object> weeks = new ArrayList<>();
        for (JsonNode row : m.path("forecast")) {
            weeks.add(map("week", row.path("week").asText(), "capacity", plain(row.path("capacity")), "demand", plain(row.path("demand")),
                    "overload", plain(row.path("overload")), "working_days", plain(row.path("working_days")), "absence_hours", plain(row.path("absence_hours"))));
        }
        return map("member_id", id, "name", m.path("name").asText(), "weeks", weeks);
    }

    Map<String, Object> projectTimelines() {
        return map("weeks", plain(facts.path("run").path("weeks")), "projects", plain(facts.path("projects")));
    }

    Map<String, Object> rebalancingCandidates() {
        return map("overloaded", plain(facts.path("rebalancing_candidates").path("overloaded")),
                "underloaded", plain(facts.path("rebalancing_candidates").path("underloaded")));
    }

    Map<String, Object> plannedWork() {
        List<Object> list = new ArrayList<>();
        for (JsonNode m : facts.path("members")) {
            list.add(map("id", m.path("id").asText(), "name", m.path("name").asText(), "likely_work", plain(m.path("likely_work"))));
        }
        return map("planned_backlog", plain(facts.path("team").path("planned_backlog")), "members", list);
    }
}
```

`plain` of a missing node (`MissingNode`) returns null through `treeToValue`; if it throws instead on the Jackson 3 version in use, guard it: `node.isMissingNode() ? null : ExportFiles.mapper().treeToValue(node, Object.class)`.

- [ ] **Step 5: Run the test**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=FactsToolsTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/ai/ToolSpec.java server/forecast-core/src/main/java/com/workloadhub/forecast/ai/FactsTools.java server/forecast-core/src/test/java/com/workloadhub/forecast/testing/SeededFacts.java server/forecast-core/src/test/java/com/workloadhub/forecast/ai/FactsToolsTest.java
git commit -m "feat(server): the nine facts tools Copilot may call, as plain specs over the facts tree

Results are maps converted from the stored facts so every number a tool
returns is a number the verifier will find."
```

---

### Task 7: The gateway seam, narration progress, and `Narrator`

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/CopilotGateway.java`, `.../ai/CopilotConnection.java`, `.../ai/NarrationSession.java`, `.../ai/SessionSpec.java`, `.../ai/AuthStatus.java`, `.../ai/RuntimeInfo.java`, `.../ai/NarrationEvent.java`, `.../ai/NarrationProgress.java`, `.../ai/Narrator.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/service/RunProgressTracker.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/UsageMetrics.java` and `UsageEvent.java` (make them `public`: the seam exposes them)
- Create: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/FakeGateway.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/NarratorTest.java`, modify `.../service/RunProgressTrackerTest.java`

**Interfaces:**
- Consumes: `Prompts` (Task 2), `NarrativeContract` (3), `NumberVerifier` (4), `Usage`, `UsageMetrics`, `UsageEvent` (5), `ToolSpec`, `FactsTools` (6), `NarrationOutcome` (1).
- Produces:
  - `public interface CopilotGateway { CopilotConnection open(String token); RuntimeInfo runtime(); }`
  - `public record RuntimeInfo(boolean available, String path, String version, String message)`
  - `public interface CopilotConnection extends AutoCloseable { AuthStatus authStatus(); NarrationSession createSession(SessionSpec spec, Consumer<NarrationEvent> events); Optional<Map<String, Object>> quota(Duration timeout); void close(); }`
  - `public record AuthStatus(boolean authenticated, String login, String message)`
  - `public record SessionSpec(String model, String systemMessage, List<ToolSpec> tools)` (`model` null means the account default)
  - `public interface NarrationSession extends AutoCloseable { String ask(String prompt, Duration timeout) throws TimeoutException; Optional<UsageMetrics> usage(Duration timeout); void close(); }` (`ask` returns the final message's content or null when the turn ended without one; any other failure is a `RuntimeException`)
  - `public record NarrationEvent(Kind kind, String text, String id, String toolName, String model, UsageEvent usage)` with `enum Kind { INTENT, THINKING_DELTA, THINKING_FULL, ANSWER_DELTA, MESSAGE, TOOL_START, TOOL_DONE, USAGE, ERROR }` and static factories `intent(text)`, `thinkingDelta(id, text)`, `thinkingFull(id, text)`, `answerDelta(text)`, `message(text, model)`, `toolStart(id, name)`, `toolDone(id)`, `usage(UsageEvent)`, `error(text)`
  - `public interface NarrationProgress { enum Step { STARTING, SESSION, ASKING, TOOL, TOOL_DONE, CHECKING } void step(Step step, String detail); void thinking(String text); void answer(String text); void resetAnswer(); static NarrationProgress none() }`
  - `public final class Narrator { Narrator(CopilotGateway gateway, Prompts prompts, Duration timeout, String defaultModel); NarrationOutcome narrate(JsonNode facts, String language, String modelOverride, String token, NarrationProgress progress); static final Duration METRICS_TIMEOUT; static final int MAX_ATTEMPTS = 2 }` (throws `ForecastException` `COPILOT_UNAVAILABLE` or `TOKEN_REJECTED` before a session exists; returns an outcome afterwards)
  - `RunProgressTracker`: `narration(UUID, NarrationProgress.Step, String)`, `thinking(UUID, String)`, `answer(UUID, String)`, `resetAnswer(UUID)`, `narrated(UUID)`, `narrationFailed(UUID, String)`, `NarrationProgress narrationProgress(UUID)`, `static final int TAIL_CHARS = 16_000`.

- [ ] **Step 1: The seam types**

`ai/CopilotGateway.java`:

```java
package com.workloadhub.forecast.ai;

/** The module's seam around the Copilot SDK: one connection per token. Tests use a scripted fake. */
public interface CopilotGateway {

    /** Starts a client for this token; throws {@code ForecastException} COPILOT_UNAVAILABLE when the runtime cannot start. */
    CopilotConnection open(String token);

    /** Where the runtime is and which SDK version, without starting anything. */
    RuntimeInfo runtime();
}
```

`ai/RuntimeInfo.java`:

```java
package com.workloadhub.forecast.ai;

public record RuntimeInfo(boolean available, String path, String version, String message) {
}
```

`ai/CopilotConnection.java`:

```java
package com.workloadhub.forecast.ai;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** A started client for one token. */
public interface CopilotConnection extends AutoCloseable {

    /** Whether Copilot accepts the token; a RuntimeException when the status cannot be read. */
    AuthStatus authStatus();

    /** Creates one streaming session; events arrive on the consumer from the SDK's threads. */
    NarrationSession createSession(SessionSpec spec, Consumer<NarrationEvent> events);

    /** The account's quota snapshots as JSON-shaped maps keyed by quota type, or empty when they cannot be read in time. */
    Optional<Map<String, Object>> quota(Duration timeout);

    @Override
    void close();
}
```

`ai/AuthStatus.java`:

```java
package com.workloadhub.forecast.ai;

public record AuthStatus(boolean authenticated, String login, String message) {
}
```

`ai/SessionSpec.java`:

```java
package com.workloadhub.forecast.ai;

import java.util.List;

/** What one narration session is made of; {@code model} null means the account's default. */
public record SessionSpec(String model, String systemMessage, List<ToolSpec> tools) {
}
```

`ai/NarrationSession.java`:

```java
package com.workloadhub.forecast.ai;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/** One session: send a prompt, wait for the answer, read what it cost, close. */
public interface NarrationSession extends AutoCloseable {

    /** The final assistant message's content, or null when the turn ended without one (the deltas then hold the answer). */
    String ask(String prompt, Duration timeout) throws TimeoutException;

    /** The session's billed usage, or empty when the RPC is unavailable or too slow. Read it before closing. */
    Optional<UsageMetrics> usage(Duration timeout);

    @Override
    void close();
}
```

`ai/NarrationEvent.java`:

```java
package com.workloadhub.forecast.ai;

/** A session event reduced to what the narrator needs, so tests never build SDK types. */
public record NarrationEvent(Kind kind, String text, String id, String toolName, String model, UsageEvent usage) {

    public enum Kind {
        INTENT, THINKING_DELTA, THINKING_FULL, ANSWER_DELTA, MESSAGE, TOOL_START, TOOL_DONE, USAGE, ERROR
    }

    public static NarrationEvent intent(String text) {
        return new NarrationEvent(Kind.INTENT, text, null, null, null, null);
    }

    public static NarrationEvent thinkingDelta(String id, String text) {
        return new NarrationEvent(Kind.THINKING_DELTA, text, id, null, null, null);
    }

    public static NarrationEvent thinkingFull(String id, String text) {
        return new NarrationEvent(Kind.THINKING_FULL, text, id, null, null, null);
    }

    public static NarrationEvent answerDelta(String text) {
        return new NarrationEvent(Kind.ANSWER_DELTA, text, null, null, null, null);
    }

    public static NarrationEvent message(String text, String model) {
        return new NarrationEvent(Kind.MESSAGE, text, null, null, model, null);
    }

    public static NarrationEvent toolStart(String id, String name) {
        return new NarrationEvent(Kind.TOOL_START, null, id, name, null, null);
    }

    public static NarrationEvent toolDone(String id) {
        return new NarrationEvent(Kind.TOOL_DONE, null, id, null, null, null);
    }

    public static NarrationEvent usage(UsageEvent usage) {
        return new NarrationEvent(Kind.USAGE, null, null, null, usage.model(), usage);
    }

    public static NarrationEvent error(String text) {
        return new NarrationEvent(Kind.ERROR, text, null, null, null, null);
    }
}
```

`ai/NarrationProgress.java`:

```java
package com.workloadhub.forecast.ai;

/** Where a narration reports what it is doing: coded steps, and the live thinking and answer text. */
public interface NarrationProgress {

    enum Step {
        STARTING, SESSION, ASKING, TOOL, TOOL_DONE, CHECKING
    }

    void step(Step step, String detail);

    void thinking(String text);

    void answer(String text);

    /** A new attempt writes a new answer; the rejected one is dropped, the thinking is kept. */
    void resetAnswer();

    static NarrationProgress none() {
        return new NarrationProgress() {
            @Override
            public void step(Step step, String detail) {
            }

            @Override
            public void thinking(String text) {
            }

            @Override
            public void answer(String text) {
            }

            @Override
            public void resetAnswer() {
            }
        };
    }
}
```

Make `UsageMetrics`, `UsageMetrics.ModelMetric` and `UsageEvent` `public` (they appear in the public seam).

- [ ] **Step 2: Write the failing narrator tests, with the fake**

`ai/FakeGateway.java` (test sources):

```java
package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.api.ForecastException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;

/** A scripted stand-in for the SDK: replies in order, streamed events before each reply, recorded calls. */
final class FakeGateway implements CopilotGateway {

    /** Put this in {@link #replies} to make the next ask time out. */
    static final Object TIMEOUT = new Object();

    final List<Object> replies;
    boolean authenticated = true;
    RuntimeException openError;
    RuntimeException authError;
    RuntimeException sessionError;
    boolean finalMessage = true;
    boolean replyNull;
    String sessionErrorText;
    boolean closeThrows;
    UsageMetrics metrics = metrics(1.5e9);
    RuntimeException metricsError;
    List<String> intents = new ArrayList<>();
    List<String[]> reasoningDeltas = new ArrayList<>();
    List<String[]> reasoningFull = new ArrayList<>();
    List<String> messageDeltas = new ArrayList<>();
    String unmatchedToolDoneId;
    Map<String, Object> quota;
    RuntimeInfo runtimeInfo = new RuntimeInfo(true, "/tmp/runtime.node", "1.0.13-preview.6", "in-process runtime");

    boolean opened;
    boolean closed;
    String tokenSeen;
    FakeSession session;
    SessionSpec spec;

    FakeGateway(Object... replies) {
        this.replies = new ArrayList<>(List.of(replies));
    }

    static UsageMetrics metrics(Double nanoAiu) {
        Map<String, UsageMetrics.ModelMetric> models = new LinkedHashMap<>();
        models.put("gpt-5", new UsageMetrics.ModelMetric(1, 100, 50, 0, 0L));
        return new UsageMetrics(nanoAiu, 1L, 1.0, 2500L, models);
    }

    /** A narrative that cites, for every member, the demand and capacity of their first forecast row. */
    static String goodNarrative(JsonNode facts) {
        StringBuilder members = new StringBuilder();
        for (JsonNode m : facts.path("members")) {
            JsonNode row = m.path("forecast").get(0);
            if (members.length() > 0) {
                members.append(',');
            }
            members.append("{\"member_id\": \"").append(m.path("id").asText()).append("\", \"name\": \"").append(m.path("name").asText().replace("\"", ""))
                    .append("\", \"risk_level\": \"low\", \"summary\": \"Demand ").append(row.path("demand").asDouble()).append(" h against ")
                    .append(row.path("capacity").asDouble()).append(" h capacity in the week of ").append(row.path("week").asText())
                    .append(".\", \"patterns\": [], \"warnings\": []}");
        }
        return "{\"run_summary\": \"All members within capacity.\", \"members\": [" + members + "], \"team_risks\": [], \"rebalancing\": [],"
                + " \"suggested_adjustments\": [], \"model_notes\": \"\"}";
    }

    @Override
    public CopilotConnection open(String token) {
        if (openError != null) {
            throw ForecastException.of("COPILOT_UNAVAILABLE", openError.getMessage());
        }
        opened = true;
        tokenSeen = token;
        return new FakeConnection();
    }

    @Override
    public RuntimeInfo runtime() {
        return runtimeInfo;
    }

    final class FakeConnection implements CopilotConnection {
        @Override
        public AuthStatus authStatus() {
            if (authError != null) {
                throw authError;
            }
            return new AuthStatus(authenticated, authenticated ? "sara" : null, authenticated ? null : "not signed in");
        }

        @Override
        public NarrationSession createSession(SessionSpec s, Consumer<NarrationEvent> events) {
            if (sessionError != null) {
                throw sessionError;
            }
            spec = s;
            session = new FakeSession(events);
            return session;
        }

        @Override
        public Optional<Map<String, Object>> quota(Duration timeout) {
            return Optional.ofNullable(quota);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    final class FakeSession implements NarrationSession {
        final Consumer<NarrationEvent> events;
        final List<String> prompts = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        boolean closed;

        FakeSession(Consumer<NarrationEvent> events) {
            this.events = events;
        }

        @Override
        public String ask(String prompt, Duration timeout) throws TimeoutException {
            prompts.add(prompt);
            List<ToolSpec> tools = spec.tools();
            for (int i = 0; i < Math.min(2, tools.size()); i++) {
                events.accept(NarrationEvent.toolStart("c" + i, tools.get(i).name()));
                tools.get(i).handler().apply(tools.get(i).memberScoped() ? "nobody" : null);
                events.accept(NarrationEvent.toolDone("c" + i));
            }
            if (unmatchedToolDoneId != null) {
                events.accept(NarrationEvent.toolDone(unmatchedToolDoneId));
            }
            for (String intent : intents) {
                events.accept(NarrationEvent.intent(intent));
            }
            for (String[] d : reasoningDeltas) {
                events.accept(NarrationEvent.thinkingDelta(d[0], d[1]));
            }
            for (String[] f : reasoningFull) {
                events.accept(NarrationEvent.thinkingFull(f[0], f[1]));
            }
            for (String chunk : messageDeltas) {
                events.accept(NarrationEvent.answerDelta(chunk));
            }
            if (replyNull) {
                return null;
            }
            if (sessionErrorText != null) {
                events.accept(NarrationEvent.error(sessionErrorText));
            }
            Object reply = replies.remove(0);
            if (reply == TIMEOUT) {
                throw new TimeoutException("no answer within " + timeout.toSeconds() + " s");
            }
            if (reply instanceof RuntimeException e) {
                throw e;
            }
            events.accept(NarrationEvent.usage(new UsageEvent("gpt-5", 100L, 50L, 20L, 0L)));
            String text = (String) reply;
            if (!finalMessage) {
                int half = text.length() / 2;
                events.accept(NarrationEvent.answerDelta(text.substring(0, half)));
                events.accept(NarrationEvent.answerDelta(text.substring(half)));
                return null;
            }
            events.accept(NarrationEvent.message(text, "gpt-5"));
            return text;
        }

        @Override
        public Optional<UsageMetrics> usage(Duration timeout) {
            calls.add("usage");
            if (metricsError != null) {
                throw metricsError;
            }
            return Optional.of(metrics);
        }

        @Override
        public void close() {
            calls.add("close");
            closed = true;
            if (closeThrows) {
                throw new IllegalStateException("close failed: connection already closed");
            }
        }
    }
}
```

`ai/NarratorTest.java`:

```java
package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.NarrationProgress.Step;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.testing.SeededFacts;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class NarratorTest {

    static final JsonNode FACTS = SeededFacts.facts();
    static final String GOOD = FakeGateway.goodNarrative(FACTS);
    static final Prompts PROMPTS = Prompts.load();

    /** Records every step and live chunk; "reset" marks an answer reset. */
    static final class Recorder implements NarrationProgress {
        final List<String> steps = new ArrayList<>();
        final List<String> thinking = new ArrayList<>();
        final List<String> answer = new ArrayList<>();

        @Override
        public void step(Step step, String detail) {
            steps.add(step + (detail == null ? "" : ":" + detail));
        }

        @Override
        public void thinking(String text) {
            thinking.add(text);
        }

        @Override
        public void answer(String text) {
            answer.add(text);
        }

        @Override
        public void resetAnswer() {
            answer.add("reset");
        }
    }

    static Narrator narrator(FakeGateway g) {
        return new Narrator(g, PROMPTS, Duration.ofSeconds(30), "");
    }

    static NarrationOutcome narrate(FakeGateway g) {
        return narrator(g).narrate(FACTS, "en", null, "gho_secret", NarrationProgress.none());
    }

    static JsonNode usage(NarrationOutcome o) {
        return ExportFiles.mapper().readTree(o.usageJson());
    }

    @Test
    void happyPathReturnsOkAndCleansUp() {
        FakeGateway g = new FakeGateway(GOOD);
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.OK, o.status());
        assertNull(o.reason());
        assertNull(o.error());
        assertEquals("gpt-5", o.model());
        assertEquals(1, o.attempts());
        assertEquals("metrics", usage(o).path("source").asText());
        assertEquals(100, usage(o).path("input_tokens").asInt());
        assertEquals(List.of("get_run_overview", "get_member_history"), o.toolCalls());
        assertTrue(o.narrativeJson().contains("All members within capacity."));
        assertNull(o.rawText());
        assertTrue(o.verificationJson().contains("\"unverified\""));
        assertEquals("gho_secret", g.tokenSeen);
        assertTrue(g.opened && g.closed && g.session.closed);
        assertNull(g.spec.model(), "blank model means the account default");
        assertTrue(g.spec.systemMessage().contains("## Skill: whf-domain"));
        assertEquals(FactsTools.NAMES, g.spec.tools().stream().map(ToolSpec::name).toList());
        assertTrue(g.session.prompts.get(0).contains("Return only the JSON document."));
    }

    @Test
    void theModelOverrideWinsOverTheDefault() {
        FakeGateway g = new FakeGateway(GOOD);
        new Narrator(g, PROMPTS, Duration.ofSeconds(30), "gpt-5-mini").narrate(FACTS, "fr", "claude-sonnet-4", "gho_x", NarrationProgress.none());
        assertEquals("claude-sonnet-4", g.spec.model());
        assertTrue(g.session.prompts.get(0).contains("Language: fr"));
        FakeGateway h = new FakeGateway(GOOD);
        new Narrator(h, PROMPTS, Duration.ofSeconds(30), "gpt-5-mini").narrate(FACTS, "en", " ", "gho_x", NarrationProgress.none());
        assertEquals("gpt-5-mini", h.spec.model());
    }

    @Test
    void invalidJsonIsRetriedOnceThenAccepted() {
        FakeGateway g = new FakeGateway("Sure! Here it is: {", GOOD);
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.OK, o.status());
        assertEquals(2, o.attempts());
        assertTrue(g.session.prompts.get(1).contains("not valid JSON"));
        assertTrue(g.session.prompts.get(1).contains("only the JSON"));
    }

    @Test
    void persistentInvalidOutputFailsWithTheProblems() {
        FakeGateway g = new FakeGateway("nope", "still nope");
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.FAILED, o.status());
        assertEquals("invalid_output", o.reason());
        assertTrue(o.error().startsWith("invalid_output: "));
        assertEquals("still nope", o.rawText());
        assertNull(o.narrativeJson());
        assertEquals("{}", o.verificationJson());
        assertEquals(2, o.attempts());
        assertTrue(g.closed);
    }

    @Test
    void unverifiedNumbersDowngradeTheStatus() {
        String text = GOOD.replace("All members within capacity.", "Demand will hit 999.5 h.");
        NarrationOutcome o = narrate(new FakeGateway(text));
        assertEquals(NarrativeStatus.UNVERIFIED, o.status());
        assertTrue(o.verificationJson().contains("999.5"));
        assertNotNull(o.narrativeJson());
    }

    @Test
    void aRejectedTokenIsThrownBeforeAnySession() {
        FakeGateway g = new FakeGateway(GOOD);
        g.authenticated = false;
        ForecastException e = assertThrows(ForecastException.class, () -> narrate(g));
        assertEquals("TOKEN_REJECTED", e.code());
        assertTrue(e.getMessage().contains("not signed in"));
        assertNull(g.session);
        assertTrue(g.closed);
    }

    @Test
    void anUnreadableAuthStatusIsARejectedToken() {
        FakeGateway g = new FakeGateway(GOOD);
        g.authError = new IllegalStateException("auth server unreachable");
        ForecastException e = assertThrows(ForecastException.class, () -> narrate(g));
        assertEquals("TOKEN_REJECTED", e.code());
        assertTrue(e.getMessage().contains("auth server unreachable"));
        assertTrue(g.closed);
    }

    @Test
    void aRuntimeThatCannotStartIsCopilotUnavailable() {
        FakeGateway g = new FakeGateway(GOOD);
        g.openError = new IllegalStateException("runtime.node not found");
        ForecastException e = assertThrows(ForecastException.class, () -> narrate(g));
        assertEquals("COPILOT_UNAVAILABLE", e.code());
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void aSessionThatCannotBeCreatedIsCopilotUnavailable() {
        FakeGateway g = new FakeGateway(GOOD);
        g.sessionError = new IllegalStateException("bad model");
        ForecastException e = assertThrows(ForecastException.class, () -> narrate(g));
        assertEquals("COPILOT_UNAVAILABLE", e.code());
        assertTrue(e.getMessage().contains("bad model"));
        assertTrue(g.closed);
    }

    @Test
    void aTimeoutIsReported() {
        FakeGateway g = new FakeGateway(FakeGateway.TIMEOUT);
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.FAILED, o.status());
        assertEquals("timeout", o.reason());
        assertTrue(o.error().startsWith("timeout: no answer within"));
        assertTrue(g.closed && g.session.closed);
    }

    @Test
    void aModelErrorIsReportedWithTheSessionError() {
        FakeGateway g = new FakeGateway(new IllegalStateException("model call failed"));
        g.sessionErrorText = "rate limited";
        NarrationOutcome o = narrate(g);
        assertEquals("model_error", o.reason());
        assertTrue(o.error().contains("model call failed") && o.error().contains("rate limited"));
    }

    @Test
    void aNullReplyIsInvalidOutput() {
        FakeGateway g = new FakeGateway();
        g.replyNull = true;
        NarrationOutcome o = narrate(g);
        assertEquals("invalid_output", o.reason());
        assertEquals("", o.rawText());
        assertEquals(2, o.attempts());
    }

    @Test
    void aCloseFailureDoesNotMaskASuccessfulNarrative() {
        FakeGateway g = new FakeGateway(GOOD);
        g.closeThrows = true;
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.OK, o.status());
        assertTrue(g.session.closed && g.closed);
    }

    @Test
    void intentAndReasoningDeltasAreForwardedAsThinking() {
        FakeGateway g = new FakeGateway(GOOD);
        g.intents = List.of("Reading the capacity of each member");
        g.reasoningDeltas = List.of(new String[] {"r1", "Yara is "}, new String[] {"r1", "over capacity"});
        Recorder r = new Recorder();
        narrator(g).narrate(FACTS, "en", null, "gho_x", r);
        assertEquals("Reading the capacity of each member\nYara is over capacity", String.join("", r.thinking));
    }

    @Test
    void aFullReasoningAlreadyStreamedIsNotRepeated() {
        FakeGateway g = new FakeGateway(GOOD);
        g.reasoningDeltas = List.of(new String[] {"r1", "Yara is over capacity"});
        g.reasoningFull = List.of(new String[] {"r1", "Yara is over capacity"});
        Recorder r = new Recorder();
        narrator(g).narrate(FACTS, "en", null, "gho_x", r);
        assertEquals("Yara is over capacity", String.join("", r.thinking));
    }

    @Test
    void aFullReasoningNeverStreamedIsShown() {
        FakeGateway g = new FakeGateway(GOOD);
        g.reasoningFull = List.of(new String[] {"r2", "Checking the weeks"});
        Recorder r = new Recorder();
        narrator(g).narrate(FACTS, "en", null, "gho_x", r);
        assertEquals("Checking the weeks\n", String.join("", r.thinking));
    }

    @Test
    void messageDeltasAreTheAnswerAndEveryAttemptStartsANewOne() {
        FakeGateway g = new FakeGateway("not JSON", GOOD);
        g.messageDeltas = List.of("{");
        Recorder r = new Recorder();
        narrator(g).narrate(FACTS, "en", null, "gho_x", r);
        assertEquals(List.of("reset", "{", "reset", "{"), r.answer);
    }

    @Test
    void aTurnWithoutAFinalMessageIsReadFromTheDeltas() {
        FakeGateway g = new FakeGateway(GOOD);
        g.finalMessage = false;
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.OK, o.status());
        assertEquals(1, o.attempts());
        assertTrue(o.narrativeJson().contains("All members within capacity."));
    }

    @Test
    void aDeltasOnlyRetryForgetsTheRejectedAttempt() {
        FakeGateway g = new FakeGateway("not JSON", GOOD);
        g.finalMessage = false;
        NarrationOutcome o = narrate(g);
        assertEquals(NarrativeStatus.OK, o.status());
        assertEquals(2, o.attempts());
    }

    @Test
    void toolCallsAreStepsNamingTheTool() {
        FakeGateway g = new FakeGateway(GOOD);
        g.unmatchedToolDoneId = "never-started";
        Recorder r = new Recorder();
        narrator(g).narrate(FACTS, "en", null, "gho_x", r);
        assertTrue(r.steps.contains("TOOL:get_run_overview"), r.steps.toString());
        assertTrue(r.steps.indexOf("TOOL_DONE:get_run_overview") > r.steps.indexOf("TOOL:get_run_overview"));
        assertTrue(r.steps.contains("TOOL_DONE"), "a completion whose start was never seen is still a step, naming no tool");
        assertEquals(List.of("STARTING", "SESSION", "ASKING:1"), r.steps.subList(0, 3));
        assertTrue(r.steps.contains("CHECKING"));
    }

    @Test
    void theSessionMetricsSayWhatTheNarrationCost() {
        FakeGateway g = new FakeGateway(GOOD);
        g.metrics = FakeGateway.metrics(2.5e9);
        JsonNode u = usage(narrate(g));
        assertEquals("metrics", u.path("source").asText());
        assertEquals(2.5, u.path("ai_credits").asDouble(), 1e-9);
        assertEquals(0.025, u.path("usd").asDouble(), 1e-9);
        assertEquals(100, u.path("input_tokens").asInt());
        assertEquals(1.0, u.path("premium_requests").asDouble(), 1e-9);
        assertEquals(2.5, u.path("api_seconds").asDouble(), 1e-9);
        assertEquals(List.of("usage", "close"), g.session.calls, "read while the session is still open");
    }

    @Test
    void aFailedNarrationStillReportsWhatItCost() {
        NarrationOutcome o = narrate(new FakeGateway("nope", "still nope"));
        assertEquals(NarrativeStatus.FAILED, o.status());
        assertEquals("metrics", usage(o).path("source").asText());
        assertEquals(1.5, usage(o).path("ai_credits").asDouble(), 1e-9);
    }

    @Test
    void unavailableMetricsFallBackToTheStreamedEvents() {
        FakeGateway g = new FakeGateway(GOOD);
        g.metricsError = new IllegalStateException("usage rpc unavailable");
        JsonNode u = usage(narrate(g));
        assertEquals("events", u.path("source").asText());
        assertEquals(100, u.path("input_tokens").asInt());
        assertEquals(50, u.path("output_tokens").asInt());
        assertEquals(20, u.path("cache_read_tokens").asInt());
        assertEquals(1, u.path("requests").asInt());
        assertTrue(u.path("ai_credits").isNull() && u.path("usd").isNull() && u.path("premium_requests").isNull());
    }
}
```

Add to `service/RunProgressTrackerTest.java` (keep the existing tests):

```java
    @Test
    void narrationStepsPercentAndTailsAreTracked() {
        RunProgressTracker t = new RunProgressTracker();
        UUID id = UUID.randomUUID();
        t.done(id);
        com.workloadhub.forecast.ai.NarrationProgress p = t.narrationProgress(id);
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.STARTING, null);
        assertEquals("NARRATING", t.get(id).orElseThrow().phase());
        assertEquals(5, t.get(id).orElseThrow().percent());
        assertEquals("starting Copilot", t.get(id).orElseThrow().message());
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.SESSION, null);
        assertEquals(10, t.get(id).orElseThrow().percent());
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.ASKING, "2");
        assertEquals(20, t.get(id).orElseThrow().percent());
        assertEquals("asking Copilot (attempt 2)", t.get(id).orElseThrow().message());
        for (int i = 0; i < 20; i++) {
            p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.TOOL, "get_member_forecast");
        }
        assertEquals(80, t.get(id).orElseThrow().percent(), "tool calls add five points up to eighty");
        assertEquals("tool get_member_forecast", t.get(id).orElseThrow().message());
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.TOOL_DONE, null);
        assertEquals("tool done", t.get(id).orElseThrow().message());
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.TOOL_DONE, "get_member_forecast");
        assertEquals("tool get_member_forecast done", t.get(id).orElseThrow().message());
        p.thinking("Reading ");
        p.thinking("the facts");
        p.answer("{\"run_summary\"");
        assertEquals("Reading the facts", t.get(id).orElseThrow().thinking());
        assertEquals("{\"run_summary\"", t.get(id).orElseThrow().answer());
        p.resetAnswer();
        assertEquals("", t.get(id).orElseThrow().answer());
        assertEquals("Reading the facts", t.get(id).orElseThrow().thinking(), "a reset keeps the thinking");
        p.answer("x".repeat(RunProgressTracker.TAIL_CHARS + 10));
        assertEquals(RunProgressTracker.TAIL_CHARS, t.get(id).orElseThrow().answer().length());
        p.step(com.workloadhub.forecast.ai.NarrationProgress.Step.CHECKING, null);
        assertEquals(90, t.get(id).orElseThrow().percent());
        t.narrated(id);
        assertEquals("NARRATED", t.get(id).orElseThrow().phase());
        assertEquals(100, t.get(id).orElseThrow().percent());
        assertEquals("Reading the facts", t.get(id).orElseThrow().thinking(), "the tails stay readable after the end");
        t.narrationFailed(id, "timeout: no answer within 300 s");
        assertEquals("NARRATION_FAILED", t.get(id).orElseThrow().phase());
        assertEquals("timeout: no answer within 300 s", t.get(id).orElseThrow().message());
    }

    @Test
    void runProgressHasNoTailsOutsideNarration() {
        RunProgressTracker t = new RunProgressTracker();
        UUID id = UUID.randomUUID();
        t.start(id);
        t.update(id, "LOADING", 2, "reading");
        assertNull(t.get(id).orElseThrow().thinking());
        assertNull(t.get(id).orElseThrow().answer());
    }
```

Add the imports the test file needs (`assertNull`, `UUID`) if missing.

- [ ] **Step 3: Run them to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='NarratorTest,RunProgressTrackerTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 4: Extend `RunProgressTracker`**

Replace `service/RunProgressTracker.java` with:

```java
package com.workloadhub.forecast.service;

import com.workloadhub.forecast.ai.NarrationProgress;
import com.workloadhub.forecast.api.RunProgress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Live progress per run, in memory, for the JVM that runs it: the run's phases, then the narration's steps and the
 * tail of what Copilot is thinking and answering. Bounded to the {@link #MAX_TRACKED} most recently started runs
 * (insertion order, oldest evicted) and to {@link #TAIL_CHARS} characters per live text. Every access is
 * synchronised on this instance.
 */
public final class RunProgressTracker {

    static final int MAX_TRACKED = 256;
    public static final int TAIL_CHARS = 16_000;

    private static final class Entry {
        String phase;
        int percent;
        String message;
        StringBuilder thinking; // null outside narration
        StringBuilder answer;

        Entry(String phase, int percent, String message) {
            this.phase = phase;
            this.percent = percent;
            this.message = message;
        }

        RunProgress view(UUID runId) {
            return new RunProgress(runId, phase, percent, message, thinking == null ? null : thinking.toString(), answer == null ? null : answer.toString());
        }
    }

    private final Map<UUID, Entry> progress = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, Entry> eldest) {
            return size() > MAX_TRACKED;
        }
    };

    public synchronized void start(UUID runId) {
        progress.put(runId, new Entry("QUEUED", 0, "queued"));
    }

    public synchronized void update(UUID runId, String phase, int percent, String message) {
        progress.put(runId, new Entry(phase, percent, message));
    }

    public synchronized void done(UUID runId) {
        progress.put(runId, new Entry("DONE", 100, "done"));
    }

    public synchronized void failed(UUID runId, String message) {
        progress.put(runId, new Entry("FAILED", 100, message));
    }

    public synchronized Optional<RunProgress> get(UUID runId) {
        Entry e = progress.get(runId);
        return e == null ? Optional.empty() : Optional.of(e.view(runId));
    }

    // ----- narration ---------------------------------------------------------------------------

    private Entry narrating(UUID runId) {
        Entry e = progress.get(runId);
        if (e == null || e.thinking == null) {
            e = new Entry("NARRATING", 0, "starting Copilot");
            e.thinking = new StringBuilder();
            e.answer = new StringBuilder();
            progress.put(runId, e);
        }
        e.phase = "NARRATING";
        return e;
    }

    public synchronized void narration(UUID runId, NarrationProgress.Step step, String detail) {
        Entry e = narrating(runId);
        switch (step) {
            case STARTING -> {
                e.percent = 5;
                e.message = "starting Copilot";
            }
            case SESSION -> {
                e.percent = 10;
                e.message = "creating session";
            }
            case ASKING -> {
                e.percent = 20;
                e.message = detail == null ? "asking Copilot" : "asking Copilot (attempt " + detail + ")";
            }
            case TOOL -> {
                e.percent = Math.min(80, Math.max(20, e.percent) + 5);
                e.message = detail == null ? "tool" : "tool " + detail;
            }
            case TOOL_DONE -> e.message = detail == null ? "tool done" : "tool " + detail + " done";
            case CHECKING -> {
                e.percent = 90;
                e.message = "checking the answer against the facts";
            }
        }
    }

    public synchronized void thinking(UUID runId, String text) {
        append(narrating(runId).thinking, text);
    }

    public synchronized void answer(UUID runId, String text) {
        append(narrating(runId).answer, text);
    }

    public synchronized void resetAnswer(UUID runId) {
        Entry e = progress.get(runId);
        if (e != null && e.answer != null) {
            e.answer.setLength(0);
        }
    }

    public synchronized void narrated(UUID runId) {
        Entry e = narrating(runId);
        e.phase = "NARRATED";
        e.percent = 100;
        e.message = "narrated";
    }

    public synchronized void narrationFailed(UUID runId, String message) {
        Entry e = narrating(runId);
        e.phase = "NARRATION_FAILED";
        e.percent = 100;
        e.message = message;
    }

    private static void append(StringBuilder sb, String text) {
        sb.append(text);
        if (sb.length() > TAIL_CHARS) {
            sb.delete(0, sb.length() - TAIL_CHARS);
        }
    }

    /** The narrator's view of this tracker for one run. */
    public NarrationProgress narrationProgress(UUID runId) {
        RunProgressTracker t = this;
        return new NarrationProgress() {
            @Override
            public void step(Step step, String detail) {
                t.narration(runId, step, detail);
            }

            @Override
            public void thinking(String text) {
                t.thinking(runId, text);
            }

            @Override
            public void answer(String text) {
                t.answer(runId, text);
            }

            @Override
            public void resetAnswer() {
                t.resetAnswer(runId);
            }
        };
    }
}
```

- [ ] **Step 5: Implement `Narrator`**

`ai/Narrator.java`:

```java
package com.workloadhub.forecast.ai;

import com.workloadhub.forecast.ai.NarrationProgress.Step;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.NarrativeStatus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * One narration: open a connection for the user's token, check the token, create a session over the facts tools,
 * ask (twice at most), validate, verify, read the cost, close. Failures before a session exists are thrown as
 * {@link ForecastException}; once a session exists the outcome is returned whatever happened, with its cost.
 */
public final class Narrator {

    private static final Logger LOG = LoggerFactory.getLogger(Narrator.class);
    public static final Duration METRICS_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_ATTEMPTS = 2;

    private final CopilotGateway gateway;
    private final Prompts prompts;
    private final Duration timeout;
    private final String defaultModel;

    public Narrator(CopilotGateway gateway, Prompts prompts, Duration timeout, String defaultModel) {
        this.gateway = gateway;
        this.prompts = prompts;
        this.timeout = timeout;
        this.defaultModel = defaultModel == null ? "" : defaultModel.trim();
    }

    /** What the streamed events left behind: the final messages, the model, the usage events, the tools, this attempt's deltas. */
    private static final class State {
        final List<String> messages = new ArrayList<>();
        String model;
        final List<UsageEvent> usageEvents = new ArrayList<>();
        final List<String> tools = new ArrayList<>();
        final StringBuilder deltas = new StringBuilder();
        final Set<String> streamedReasoning = new HashSet<>();
        final Map<String, String> toolNames = new HashMap<>();
        String lastError;
    }

    public NarrationOutcome narrate(JsonNode facts, String language, String modelOverride, String token, NarrationProgress progress) {
        progress.step(Step.STARTING, null);
        CopilotConnection connection = gateway.open(token);
        try {
            AuthStatus auth;
            try {
                auth = connection.authStatus();
            } catch (RuntimeException e) {
                throw ForecastException.of("TOKEN_REJECTED", "could not read Copilot sign-in status: " + e.getMessage());
            }
            if (!auth.authenticated()) {
                throw ForecastException.of("TOKEN_REJECTED", auth.message() != null ? auth.message() : "the token is not accepted by Copilot");
            }
            State state = new State();
            FactsTools tools = new FactsTools(facts);
            String model = modelOverride != null && !modelOverride.isBlank() ? modelOverride.trim() : defaultModel.isEmpty() ? null : defaultModel;
            progress.step(Step.SESSION, null);
            NarrationSession session;
            try {
                session = connection.createSession(new SessionSpec(model, prompts.systemMessage(), tools.specs()), e -> onEvent(e, state, progress));
            } catch (RuntimeException e) {
                throw ForecastException.of("COPILOT_UNAVAILABLE", "could not create Copilot session: " + e.getMessage());
            }
            try {
                return converse(session, facts, language, state, progress);
            } finally {
                try {
                    session.close();
                } catch (RuntimeException e) {
                    LOG.warn("copilot session close failed: {}", e.getMessage());
                }
            }
        } finally {
            try {
                connection.close();
            } catch (RuntimeException e) {
                LOG.warn("copilot client close failed: {}", e.getMessage());
            }
        }
    }

    private NarrationOutcome converse(NarrationSession session, JsonNode facts, String language, State state, NarrationProgress progress) {
        String prompt = prompts.userPrompt(facts, language);
        String raw = "";
        List<String> problems = List.of();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            progress.step(Step.ASKING, String.valueOf(attempt));
            progress.resetAnswer();
            state.deltas.setLength(0);
            String content;
            try {
                content = session.ask(prompt, timeout);
            } catch (TimeoutException e) {
                return failed(session, state, attempt, "timeout", "no answer within " + timeout.toSeconds() + " s", null);
            } catch (RuntimeException e) {
                String error = e.getMessage();
                if (state.lastError != null) {
                    error += "; session error: " + state.lastError;
                }
                return failed(session, state, attempt, "model_error", error, null);
            }
            synchronized (state) {
                raw = contentOf(content, state);
            }
            progress.step(Step.CHECKING, null);
            try {
                NarrativeContract.Narrative narrative = NarrativeContract.parse(raw);
                problems = NarrativeContract.validateAgainstFacts(narrative, facts);
                if (problems.isEmpty()) {
                    NumberVerifier.Report report = NumberVerifier.verify(narrative, facts);
                    String usage = usageOf(session, state);
                    synchronized (state) {
                        return new NarrationOutcome(report.ok() ? NarrativeStatus.OK : NarrativeStatus.UNVERIFIED, null, narrative.toJson(), null,
                                report.toJson(), usage, state.model, attempt, state.tools, null);
                    }
                }
            } catch (NarrativeContract.ContractException e) {
                problems = e.problems();
            }
            LOG.info("narrative rejected on attempt {}: {}", attempt, problems);
            prompt = prompts.retryPrompt(problems);
        }
        return failed(session, state, MAX_ATTEMPTS, "invalid_output", String.join("; ", problems), raw);
    }

    private NarrationOutcome failed(NarrationSession session, State state, int attempts, String reason, String detail, String rawText) {
        String usage = usageOf(session, state);
        synchronized (state) {
            return new NarrationOutcome(NarrativeStatus.FAILED, reason, null, rawText, "{}", usage, state.model, attempts, state.tools, reason + ": " + detail);
        }
    }

    /** The cost, read while the session is still open: its own metrics, else the streamed events. */
    private static String usageOf(NarrationSession session, State state) {
        try {
            Optional<UsageMetrics> metrics = session.usage(METRICS_TIMEOUT);
            if (metrics.isPresent()) {
                return Usage.toJson(Usage.fromMetrics(metrics.get()));
            }
        } catch (RuntimeException e) {
            LOG.warn("copilot usage metrics unavailable: {}", e.getMessage());
        }
        synchronized (state) {
            return Usage.toJson(Usage.fromEvents(List.copyOf(state.usageEvents)));
        }
    }

    private static String contentOf(String content, State state) {
        if (content != null) {
            return content;
        }
        if (!state.messages.isEmpty()) {
            return state.messages.get(state.messages.size() - 1);
        }
        return state.deltas.toString();
    }

    /** Events arrive on the SDK's threads while {@code ask} blocks the caller: every touch of the state is under its lock. */
    private static void onEvent(NarrationEvent e, State state, NarrationProgress progress) {
        synchronized (state) {
            handle(e, state, progress);
        }
    }

    private static void handle(NarrationEvent e, State state, NarrationProgress progress) {
        switch (e.kind()) {
            case MESSAGE -> {
                state.messages.add(e.text());
                if (e.model() != null) {
                    state.model = e.model();
                }
            }
            case ANSWER_DELTA -> {
                state.deltas.append(e.text());
                progress.answer(e.text());
            }
            case INTENT -> progress.thinking(e.text() + "\n");
            case THINKING_DELTA -> {
                state.streamedReasoning.add(e.id());
                progress.thinking(e.text());
            }
            case THINKING_FULL -> {
                if (!state.streamedReasoning.contains(e.id())) {
                    progress.thinking(e.text() + "\n");
                }
            }
            case TOOL_START -> {
                state.tools.add(e.toolName());
                state.toolNames.put(e.id(), e.toolName());
                progress.step(Step.TOOL, e.toolName());
            }
            case TOOL_DONE -> progress.step(Step.TOOL_DONE, state.toolNames.get(e.id()));
            case USAGE -> state.usageEvents.add(e.usage());
            case ERROR -> state.lastError = e.text();
        }
    }
}
```

Note on threads: the SDK delivers events from its own threads while `ask` blocks the caller, so every read and write of `State` is under `synchronized (state)`; `progress` (the tracker) is synchronised on its own.

- [ ] **Step 6: Run the tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='NarratorTest,RunProgressTrackerTest,DefaultForecastServiceTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS (the service test still passes because the tracker's public run methods kept their behaviour).

- [ ] **Step 7: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/ai server/forecast-core/src/main/java/com/workloadhub/forecast/service/RunProgressTracker.java server/forecast-core/src/test/java/com/workloadhub/forecast/ai server/forecast-core/src/test/java/com/workloadhub/forecast/service/RunProgressTrackerTest.java
git commit -m "feat(server): the narrator over a gateway seam, with live progress and a scripted fake

Pre-session failures throw (nothing billed); once a session exists the
outcome is returned with its cost whatever happened. The tracker gains
narration steps and bounded thinking and answer tails."
```

---

### Task 8: `SdkCopilotGateway`: the real gateway over `copilot-sdk-java`

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/ai/SdkCopilotGateway.java`
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/SdkCopilotGatewayTest.java`

**Interfaces:**
- Consumes: the seam types of Task 7, `ToolSpec` (6), `UsageMetrics`, `UsageEvent` (5), `SkillTexts.resource` (2).
- Produces: `public final class SdkCopilotGateway implements CopilotGateway { SdkCopilotGateway(Path copilotHome, String cliPath) }` plus package-private statics for the tests: `options(String token, Path copilotHome, String cliPath) -> CopilotClientOptions`, `sessionConfig(SessionSpec, Consumer<NarrationEvent>) -> SessionConfig`, `toolDefinitions(List<ToolSpec>) -> List<ToolDefinition>`, `permissionHandler(Set<String> toolNames) -> PermissionHandler`, `mapEvent(SessionEvent) -> NarrationEvent` (null for events the narrator ignores), `sdkVersion() -> String`.

- [ ] **Step 1: Write the failing test**

`ai/SdkCopilotGatewayTest.java`:

```java
package com.workloadhub.forecast.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.copilot.SystemMessageMode;
import com.github.copilot.generated.AssistantIntentEvent;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantReasoningDeltaEvent;
import com.github.copilot.generated.AssistantReasoningEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PermissionRequestResultKind;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.ToolDefinition;
import com.workloadhub.forecast.testing.SeededFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdkCopilotGatewayTest {

    @Test
    void optionsCarryTheTokenTheHomeAndNoLoggedInUser(@TempDir Path dir) {
        CopilotClientOptions o = SdkCopilotGateway.options("gho_abc", dir.resolve("copilot"), "");
        assertEquals("gho_abc", o.getGitHubToken());
        assertEquals(Optional.of(false), o.getUseLoggedInUser());
        assertEquals(dir.resolve("copilot").toAbsolutePath().toString(), o.getCopilotHome());
        assertEquals("error", o.getLogLevel());
        assertNull(o.getCliPath(), "blank cli-path means the in-process runtime");
        assertEquals("workloadhub-forecast", o.getClientInfo().getApplicationName());
        CopilotClientOptions sub = SdkCopilotGateway.options("gho_abc", dir, " /opt/copilot/copilot ");
        assertEquals("/opt/copilot/copilot", sub.getCliPath());
    }

    @Test
    void theSessionConfigIsTheSpecMappedOntoTheSdk() {
        FactsTools tools = new FactsTools(SeededFacts.facts());
        List<NarrationEvent> seen = new ArrayList<>();
        SessionConfig cfg = SdkCopilotGateway.sessionConfig(new SessionSpec(null, "SYSTEM", tools.specs()), seen::add);
        assertNull(cfg.getModel());
        assertEquals(SystemMessageMode.REPLACE, cfg.getSystemMessage().getMode());
        assertEquals("SYSTEM", cfg.getSystemMessage().getContent());
        assertEquals(FactsTools.NAMES, cfg.getTools().stream().map(ToolDefinition::name).toList());
        assertTrue(cfg.getTools().stream().allMatch(t -> Boolean.TRUE.equals(t.skipPermission())));
        assertEquals(List.of("custom:*"), cfg.getAvailableTools());
        assertEquals(Optional.of(false), cfg.getEnableSkills());
        assertEquals(Optional.of(false), cfg.getEnableConfigDiscovery());
        assertEquals(Optional.of(false), cfg.getEnableSessionStore());
        assertEquals(Optional.of(true), cfg.getSkipCustomInstructions());
        assertEquals(Optional.of(false), cfg.getInfiniteSessions().getEnabled());
        assertNotNull(cfg.getOnPermissionRequest());
        assertNotNull(cfg.getOnEvent());
        assertEquals("gpt-5-mini", SdkCopilotGateway.sessionConfig(new SessionSpec("gpt-5-mini", "S", List.of()), seen::add).getModel());

        AssistantMessageDeltaEvent delta = new AssistantMessageDeltaEvent();
        delta.setData(new AssistantMessageDeltaEvent.AssistantMessageDeltaEventData("m1", "{\"run", null));
        cfg.getOnEvent().accept(delta);
        assertEquals(NarrationEvent.Kind.ANSWER_DELTA, seen.get(0).kind());
        assertEquals("{\"run", seen.get(0).text());
    }

    @Test
    void toolDefinitionsCallTheHandlersWithTheMemberId() throws Exception {
        List<ToolSpec> specs = List.of(
                new ToolSpec("get_run_overview", "overview", false, id -> Map.of("got", String.valueOf(id))),
                new ToolSpec("get_member_forecast", "forecast", true, id -> Map.of("got", id)));
        List<ToolDefinition> defs = SdkCopilotGateway.toolDefinitions(specs);
        assertEquals(2, defs.size());
        assertEquals("get_run_overview", defs.get(0).name());
        assertEquals("overview", defs.get(0).description());
        assertTrue(defs.get(0).skipPermission());
        assertNotNull(defs.get(1).parameters(), "the member tool declares its member_id parameter");
        assertTrue(defs.get(1).parameters().toString().contains("member_id"));
    }

    @Test
    void thePermissionHandlerApprovesOnlyTheModulesTools() throws Exception {
        var handler = SdkCopilotGateway.permissionHandler(Set.of("get_run_overview"));
        PermissionRequest ours = new PermissionRequest();
        ours.setKind("custom-tool");
        ours.setExtensionData(Map.of("toolName", "get_run_overview"));
        assertEquals(PermissionRequestResultKind.APPROVED.getValue(), handler.handle(ours, null).get().getKind());
        PermissionRequest other = new PermissionRequest();
        other.setKind("custom-tool");
        other.setExtensionData(Map.of("toolName", "write_file"));
        assertEquals(PermissionRequestResultKind.REJECTED.getValue(), handler.handle(other, null).get().getKind());
        PermissionRequest shell = new PermissionRequest();
        shell.setKind("commands");
        assertEquals(PermissionRequestResultKind.REJECTED.getValue(), handler.handle(shell, null).get().getKind());
    }

    @Test
    void eventsAreMappedToTheNarratorsKinds() {
        AssistantIntentEvent intent = new AssistantIntentEvent();
        intent.setData(new AssistantIntentEvent.AssistantIntentEventData("Reading the facts"));
        assertEquals(NarrationEvent.intent("Reading the facts"), SdkCopilotGateway.mapEvent(intent));
        AssistantReasoningDeltaEvent rd = new AssistantReasoningDeltaEvent();
        rd.setData(new AssistantReasoningDeltaEvent.AssistantReasoningDeltaEventData("r1", "Yara "));
        assertEquals(NarrationEvent.thinkingDelta("r1", "Yara "), SdkCopilotGateway.mapEvent(rd));
        AssistantReasoningEvent rf = new AssistantReasoningEvent();
        rf.setData(new AssistantReasoningEvent.AssistantReasoningEventData("r1", "Yara is over", null));
        assertEquals(NarrationEvent.thinkingFull("r1", "Yara is over"), SdkCopilotGateway.mapEvent(rf));
        SessionErrorEvent err = new SessionErrorEvent();
        err.setData(new SessionErrorEvent.SessionErrorEventData("model", "429", null, "rate limited", null, null, null, null, null, null));
        assertEquals(NarrationEvent.error("rate limited"), SdkCopilotGateway.mapEvent(err));
        assertNull(SdkCopilotGateway.mapEvent(new com.github.copilot.generated.SessionIdleEvent()), "ignored kinds map to null");
    }

    @Test
    void runtimeInfoReportsTheConfiguredCliWithoutStartingAnything(@TempDir Path dir) throws Exception {
        Path cli = dir.resolve("copilot");
        Files.writeString(cli, "#!/bin/sh\n");
        cli.toFile().setExecutable(true);
        RuntimeInfo info = new SdkCopilotGateway(dir.resolve("home"), cli.toString()).runtime();
        assertTrue(info.available());
        assertEquals(cli.toString(), info.path());
        assertEquals("1.0.13-preview.6", info.version());
        RuntimeInfo missing = new SdkCopilotGateway(dir.resolve("home"), dir.resolve("nope").toString()).runtime();
        assertFalse(missing.available());
        assertTrue(missing.message().contains("nope"));
        assertEquals("1.0.13-preview.6", SdkCopilotGateway.sdkVersion());
    }
}
```

The `SessionErrorEventData` constructor takes ten arguments in the order `errorType, errorCode, eligibleForAutoSwitch, message, remediation, stack, statusCode, providerCallId, serviceRequestId, url` (from `javap`); if the compiler disagrees, run `javap -cp <sdk jar> -public 'com.github.copilot.generated.SessionErrorEvent$SessionErrorEventData'` and match its canonical constructor. Same for the reasoning event (`reasoningId, content, rte`).

- [ ] **Step 2: Run it to see it fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=SdkCopilotGatewayTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 3: Implement**

`ai/SdkCopilotGateway.java`:

```java
package com.workloadhub.forecast.ai;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.ffi.NativeRuntimeLoader;
import com.github.copilot.generated.AssistantIntentEvent;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.AssistantReasoningDeltaEvent;
import com.github.copilot.generated.AssistantReasoningEvent;
import com.github.copilot.generated.AssistantUsageEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.ToolExecutionCompleteEvent;
import com.github.copilot.generated.ToolExecutionStartEvent;
import com.github.copilot.generated.rpc.AccountGetQuotaParams;
import com.github.copilot.generated.rpc.AccountGetQuotaResult;
import com.github.copilot.generated.rpc.AccountQuotaSnapshot;
import com.github.copilot.generated.rpc.SessionUsageGetMetricsResult;
import com.github.copilot.generated.rpc.UsageMetricsModelMetric;
import com.github.copilot.rpc.ClientInfo;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.InfiniteSessionConfig;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.PermissionHandler;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.ToolDefinition;
import com.github.copilot.rpc.ToolSet;
import com.github.copilot.tool.Param;
import com.workloadhub.forecast.api.ForecastException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway over copilot-sdk-java. One client per token, started with the token as the only credential and
 * COPILOT_HOME under the module's work directory; the in-process runtime by default, a CLI subprocess when a
 * path is configured. Nothing here is reachable from tests except the pure mappings.
 */
public final class SdkCopilotGateway implements CopilotGateway {

    private static final Logger LOG = LoggerFactory.getLogger(SdkCopilotGateway.class);
    static final Duration START_TIMEOUT = Duration.ofSeconds(90);
    static final Duration RPC_TIMEOUT = Duration.ofSeconds(30);
    static final String APPLICATION = "workloadhub-forecast";
    static final String REJECTION = "the narrator may only read the run facts";

    private final Path copilotHome;
    private final String cliPath;

    public SdkCopilotGateway(Path copilotHome, String cliPath) {
        this.copilotHome = copilotHome;
        this.cliPath = cliPath == null ? "" : cliPath.trim();
    }

    // ----- pure mappings ----------------------------------------------------------------------

    static CopilotClientOptions options(String token, Path copilotHome, String cliPath) {
        CopilotClientOptions o = new CopilotClientOptions();
        o.setGitHubToken(token);
        o.setUseLoggedInUser(false);
        o.setCopilotHome(copilotHome.toAbsolutePath().toString());
        o.setLogLevel("error");
        o.setClientInfo(new ClientInfo().setApplicationName(APPLICATION).setApplicationVersion(sdkVersion()));
        if (cliPath != null && !cliPath.isBlank()) {
            o.setCliPath(cliPath.trim());
        }
        return o;
    }

    static SessionConfig sessionConfig(SessionSpec spec, Consumer<NarrationEvent> events) {
        SessionConfig cfg = new SessionConfig();
        if (spec.model() != null && !spec.model().isBlank()) {
            cfg.setModel(spec.model());
        }
        cfg.setSystemMessage(new SystemMessageConfig().setMode(SystemMessageMode.REPLACE).setContent(spec.systemMessage()));
        cfg.setTools(toolDefinitions(spec.tools()));
        cfg.setAvailableTools(new ToolSet().addCustom("*"));
        cfg.setStreaming(true);
        cfg.setEnableSkills(false);
        cfg.setEnableConfigDiscovery(false);
        cfg.setEnableSessionStore(false);
        cfg.setSkipCustomInstructions(true);
        cfg.setInfiniteSessions(new InfiniteSessionConfig().setEnabled(false));
        cfg.setOnPermissionRequest(permissionHandler(spec.tools().stream().map(ToolSpec::name).collect(Collectors.toSet())));
        cfg.setOnEvent(ev -> {
            NarrationEvent mapped = mapEvent(ev);
            if (mapped != null) {
                events.accept(mapped);
            }
        });
        return cfg;
    }

    static List<ToolDefinition> toolDefinitions(List<ToolSpec> specs) {
        List<ToolDefinition> out = new ArrayList<>();
        for (ToolSpec s : specs) {
            ToolDefinition def = s.memberScoped()
                    ? ToolDefinition.<String, Object>from(s.name(), s.description(),
                            Param.of(String.class, "member_id", "The member id from get_run_overview, copied exactly"), s.handler()::apply)
                    : ToolDefinition.<Object>from(s.name(), s.description(), () -> s.handler().apply(null));
            out.add(def.skipPermission(true));
        }
        return out;
    }

    static PermissionHandler permissionHandler(Set<String> toolNames) {
        return (request, invocation) -> {
            Map<String, Object> ext = request.getExtensionData();
            boolean ours = "custom-tool".equals(request.getKind()) && ext != null && toolNames.contains(String.valueOf(ext.get("toolName")));
            return CompletableFuture.completedFuture(ours ? PermissionRequestResult.approveOnce() : PermissionRequestResult.reject(REJECTION));
        };
    }

    static NarrationEvent mapEvent(SessionEvent ev) {
        if (ev instanceof AssistantMessageEvent e && e.getData() != null) {
            return NarrationEvent.message(e.getData().content(), e.getData().model());
        }
        if (ev instanceof AssistantMessageDeltaEvent e && e.getData() != null) {
            return NarrationEvent.answerDelta(e.getData().deltaContent());
        }
        if (ev instanceof AssistantIntentEvent e && e.getData() != null) {
            return NarrationEvent.intent(e.getData().intent());
        }
        if (ev instanceof AssistantReasoningDeltaEvent e && e.getData() != null) {
            return NarrationEvent.thinkingDelta(e.getData().reasoningId(), e.getData().deltaContent());
        }
        if (ev instanceof AssistantReasoningEvent e && e.getData() != null) {
            return NarrationEvent.thinkingFull(e.getData().reasoningId(), e.getData().content());
        }
        if (ev instanceof ToolExecutionStartEvent e && e.getData() != null) {
            return NarrationEvent.toolStart(e.getData().toolCallId(), e.getData().toolName());
        }
        if (ev instanceof ToolExecutionCompleteEvent e && e.getData() != null) {
            return NarrationEvent.toolDone(e.getData().toolCallId());
        }
        if (ev instanceof AssistantUsageEvent e && e.getData() != null) {
            var d = e.getData();
            return NarrationEvent.usage(new UsageEvent(d.model(), d.inputTokens(), d.outputTokens(), d.cacheReadTokens(), d.reasoningTokens()));
        }
        if (ev instanceof SessionErrorEvent e && e.getData() != null) {
            return NarrationEvent.error(e.getData().message());
        }
        return null;
    }

    static String sdkVersion() {
        for (String line : SkillTexts.resource("copilot-runtime.properties").lines().toList()) {
            if (line.startsWith("version=")) {
                return line.substring("version=".length()).trim();
            }
        }
        return "unknown";
    }

    static UsageMetrics toMetrics(SessionUsageGetMetricsResult r) {
        Map<String, UsageMetrics.ModelMetric> models = new LinkedHashMap<>();
        if (r.modelMetrics() != null) {
            for (Map.Entry<String, UsageMetricsModelMetric> e : r.modelMetrics().entrySet()) {
                UsageMetricsModelMetric m = e.getValue();
                long requests = m.requests() == null || m.requests().count() == null ? 0 : m.requests().count();
                var u = m.usage();
                models.put(e.getKey(), new UsageMetrics.ModelMetric(requests, zero(u == null ? null : u.inputTokens()), zero(u == null ? null : u.outputTokens()),
                        zero(u == null ? null : u.cacheReadTokens()), u == null ? null : u.reasoningTokens()));
            }
        }
        return new UsageMetrics(r.totalNanoAiu(), r.totalUserRequests(), r.totalPremiumRequestCost(), r.totalApiDurationMs(), models);
    }

    private static long zero(Long v) {
        return v == null ? 0 : v;
    }

    static Map<String, Object> toQuota(AccountGetQuotaResult r) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.quotaSnapshots() != null) {
            for (Map.Entry<String, AccountQuotaSnapshot> e : r.quotaSnapshots().entrySet()) {
                AccountQuotaSnapshot s = e.getValue();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("used", s.usedRequests());
                m.put("entitlement", s.entitlementRequests());
                m.put("unlimited", s.isUnlimitedEntitlement());
                m.put("remaining_percentage", s.remainingPercentage());
                m.put("overage", s.overage());
                m.put("reset_date", s.resetDate() == null ? null : s.resetDate().toString());
                out.put(e.getKey(), m);
            }
        }
        return out;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    // ----- the live side ----------------------------------------------------------------------

    @Override
    public RuntimeInfo runtime() {
        String version = sdkVersion();
        if (!cliPath.isEmpty()) {
            Path p = Path.of(cliPath);
            boolean ok = Files.isExecutable(p);
            return new RuntimeInfo(ok, cliPath, version, ok ? "CLI subprocess" : "configured CLI is not an executable file: " + cliPath);
        }
        try {
            Path p = NativeRuntimeLoader.resolve();
            return new RuntimeInfo(true, p.toString(), version, "in-process runtime");
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            return new RuntimeInfo(false, null, version, "runtime unavailable: " + rootMessage(e));
        }
    }

    @Override
    public CopilotConnection open(String token) {
        try {
            Files.createDirectories(copilotHome);
        } catch (IOException e) {
            throw ForecastException.of("COPILOT_UNAVAILABLE", "cannot create the Copilot home " + copilotHome + ": " + e.getMessage());
        }
        CopilotClient client = new CopilotClient(options(token, copilotHome, cliPath));
        try {
            client.start().get(START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            try {
                client.forceStop().get(RPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // the start already failed; the message below is the one that matters
            }
            throw ForecastException.of("COPILOT_UNAVAILABLE", "Copilot runtime could not start: " + rootMessage(e));
        }
        return new Connection(client, token);
    }

    private static <T> T await(CompletableFuture<T> future, Duration timeout, String what) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + " interrupted");
        } catch (ExecutionException e) {
            throw new IllegalStateException(what + " failed: " + rootMessage(e));
        } catch (TimeoutException e) {
            throw new IllegalStateException(what + " timed out after " + timeout.toSeconds() + " s");
        }
    }

    private static final class Connection implements CopilotConnection {
        private final CopilotClient client;
        private final String token;

        Connection(CopilotClient client, String token) {
            this.client = client;
            this.token = token;
        }

        @Override
        public AuthStatus authStatus() {
            GetAuthStatusResponse r = await(client.getAuthStatus(), RPC_TIMEOUT, "auth status");
            return new AuthStatus(r.isAuthenticated(), r.getLogin(), r.getStatusMessage());
        }

        @Override
        public NarrationSession createSession(SessionSpec spec, Consumer<NarrationEvent> events) {
            CopilotSession s = await(client.createSession(sessionConfig(spec, events)), START_TIMEOUT, "session creation");
            return new Session(s);
        }

        @Override
        public Optional<Map<String, Object>> quota(Duration timeout) {
            try {
                AccountGetQuotaResult r = client.getRpc().account.getQuota(new AccountGetQuotaParams(null, token)).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return Optional.of(toQuota(r));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.warn("copilot quota unavailable: {}", rootMessage(e));
                return Optional.empty();
            }
        }

        @Override
        public void close() {
            try {
                client.stop().get(RPC_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                client.forceStop();
                throw new IllegalStateException("copilot client stop failed: " + rootMessage(e));
            }
        }
    }

    private static final class Session implements NarrationSession {
        private final CopilotSession session;

        Session(CopilotSession session) {
            this.session = session;
        }

        @Override
        public String ask(String prompt, Duration timeout) throws TimeoutException {
            try {
                AssistantMessageEvent ev = session.sendAndWait(new MessageOptions().setPrompt(prompt), timeout.toMillis())
                        .get(timeout.toMillis() + 5_000, TimeUnit.MILLISECONDS);
                return ev == null || ev.getData() == null ? null : ev.getData().content();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for Copilot");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    throw new TimeoutException(rootMessage(e));
                }
                throw new IllegalStateException(rootMessage(e));
            }
        }

        @Override
        public Optional<UsageMetrics> usage(Duration timeout) {
            try {
                return Optional.of(toMetrics(session.getRpc().usage.getMetrics().get(timeout.toMillis(), TimeUnit.MILLISECONDS)));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.warn("copilot usage metrics unavailable: {}", rootMessage(e));
                return Optional.empty();
            }
        }

        @Override
        public void close() {
            session.close();
        }
    }
}
```

If `ToolDefinition.<String, Object>from(...)` does not infer with the method reference, replace `s.handler()::apply` with `(String id) -> s.handler().apply(id)`. If `PermissionRequestResult.getKind()` returns the enum's string, `PermissionRequestResultKind.APPROVED.getValue()` is what to compare with (as the test does).

- [ ] **Step 4: Run the tests**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest=SdkCopilotGatewayTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. No test starts a client.

- [ ] **Step 5: Commit**

```bash
git add server/forecast-core/src/main/java/com/workloadhub/forecast/ai/SdkCopilotGateway.java server/forecast-core/src/test/java/com/workloadhub/forecast/ai/SdkCopilotGatewayTest.java
git commit -m "feat(server): the real Copilot gateway over copilot-sdk-java

Token on the client options and no logged-in user, REPLACE system message,
custom tools only, config discovery and session store off, a permission
handler that approves the nine facts tools and rejects everything else."
```

---

### Task 9: `DefaultForecastService.narrate`, `narrative`, `copilotStatus`, the auto-configuration beans and the CLI services

**Files:**
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/service/DefaultForecastService.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/ForecastAutoConfiguration.java`
- Modify: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/Services.java`
- Modify: `server/forecast-core/src/test/java/com/workloadhub/forecast/ai/FakeGateway.java` (make it `public` with public fields and statics, so the service test in another package can script it)
- Test: modify `server/forecast-core/src/test/java/com/workloadhub/forecast/service/DefaultForecastServiceTest.java`, `.../ForecastAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `Narrator`, `CopilotGateway`, `NarrationProgress`, `RuntimeInfo`, `AuthStatus` (7), `SdkCopilotGateway` (8), `JdbcNarrativeStore`, `NarrationOutcome`, `NarrativeResult`, `CopilotStatus` (1), `Prompts` (2), `GitHubTokenStore` (existing).
- Produces: `DefaultForecastService(DataSource, Dialect, ForecastRunner, JdbcRunStore, RunProgressTracker, int threads, boolean plannedWorkDefault, GitHubTokenStore tokens, JdbcNarrativeStore narratives, Narrator narrator, CopilotGateway gateway)`; `narrate(NarrativeRequest) -> NarrativeResult`; `narrative(UUID, String) -> Optional<NarrativeResult>`; `copilotStatus(UUID) -> CopilotStatus`; `DefaultForecastService.QUOTA_TIMEOUT`; beans `CopilotGateway`, `JdbcNarrativeStore`, `Narrator`; CLI `Services(service, dialect, jdbc, runner, tokens)` with `Services.open(DataSource)` reading `WHF_TOKEN_KEY`, `WHF_COPILOT_CLI_PATH` and `WHF_COPILOT_MODEL` from the environment and `Services.tokenKeyConfigured()`.

- [ ] **Step 1: Open the fake to other packages**

In `FakeGateway.java` (test sources) change `final class FakeGateway` to `public final class FakeGateway`, the constructor to `public FakeGateway(Object... replies)`, every field to `public`, and `TIMEOUT`, `metrics(Double)` and `goodNarrative(JsonNode)` to `public static`. Nothing else changes.

- [ ] **Step 2: Write the failing service tests**

In `DefaultForecastServiceTest.java` replace the `boot()` method and the `errorsCarryTheSpecCodes` test, and add the narration tests. The whole file becomes:

```java
package com.workloadhub.forecast.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.ai.Narrator;
import com.workloadhub.forecast.ai.Prompts;
import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.store.AesGcmCipher;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcGitHubTokenStore;
import com.workloadhub.forecast.store.JdbcNarrativeStore;
import com.workloadhub.forecast.store.JdbcRunStore;
import com.workloadhub.forecast.testing.SeededData;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class DefaultForecastServiceTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    static DefaultForecastService service;
    static FakeGateway gateway;
    static JdbcGitHubTokenStore tokens;
    static UUID team;
    static UUID member;

    static DefaultForecastService build(DataSource ds, FakeGateway g, RunProgressTracker tracker, JdbcRunStore runs) {
        Dialect dialect = Dialect.of(ds);
        JdbcGitHubTokenStore t = new JdbcGitHubTokenStore(JdbcClient.create(ds), dialect, AesGcmCipher.fromBase64Key(KEY));
        return new DefaultForecastService(ds, dialect, new ForecastRunner(new CapacityRule(40), true), runs, tracker, 1, true, t,
                new JdbcNarrativeStore(ds, dialect), new Narrator(g, Prompts.load(), Duration.ofSeconds(5), ""), g);
    }

    @BeforeAll
    static void boot() {
        DataSource ds = SeededData.dataSource();
        ForecastMigrations.run(ds);
        Dialect dialect = Dialect.of(ds);
        gateway = new FakeGateway();
        tokens = new JdbcGitHubTokenStore(JdbcClient.create(ds), dialect, AesGcmCipher.fromBase64Key(KEY));
        service = build(ds, gateway, new RunProgressTracker(), new JdbcRunStore(ds, dialect));
        ForecastData data = SeededData.data();
        team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
        member = data.membersOfTeam(team).get(0).id();
    }

    @AfterAll
    static void stop() throws Exception {
        service.close();
    }

    @Test
    void runNowPersistsAndReturnsTheWholeResult() {
        RunResult r = service.runNow(new RunRequest(team, null, SeededData.asOf(), null, null));
        assertEquals(RunStatus.DONE, r.run().status());
        assertFalse(r.memberWeeks().isEmpty());
        assertTrue(r.factsJson().startsWith("{"));
        assertTrue(r.maseByModel().containsKey("seasonal_naive"));
        assertFalse(r.scores().isEmpty());
        RunResult again = service.getRun(r.run().id());
        assertEquals(r.memberWeeks(), again.memberWeeks());
        assertEquals(r.factsJson(), again.factsJson());
        assertEquals(r.scores(), again.scores());
        assertEquals(100, service.progress(r.run().id()).percent());
        assertEquals(r.run().id(), service.listRuns(team, 5).get(0).id());
    }

    @Test
    void startRunReturnsImmediatelyAndFinishesInTheBackground() throws Exception {
        UUID id = service.startRun(new RunRequest(team, null, SeededData.asOf(), "seasonal_naive", false));
        RunProgress first = service.progress(id);
        assertTrue(List.of("QUEUED", "LOADING", "FEATURES", "BACKTEST", "FORECAST", "FACTS", "PERSIST", "DONE").contains(first.phase()));
        long deadline = System.currentTimeMillis() + 120_000;
        while (service.progress(id).percent() < 100 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertEquals("DONE", service.progress(id).phase());
        RunResult r = service.getRun(id);
        assertEquals("seasonal_naive", r.run().championModel());
        assertTrue(r.memberWeeks().stream().allMatch(w -> w.plannedHrs() == 0.0));
    }

    @Test
    void errorsCarryTheSpecCodes() {
        assertEquals("TEAM_NOT_FOUND", assertThrows(ForecastException.class,
                () -> service.startRun(new RunRequest(UUID.randomUUID(), null, SeededData.asOf(), null, null))).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ForecastException.class, () -> service.getRun(UUID.randomUUID())).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ForecastException.class, () -> service.progress(UUID.randomUUID())).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ForecastException.class, () -> service.narrative(UUID.randomUUID(), "en")).code());
        assertEquals("RUN_NOT_FOUND", assertThrows(ForecastException.class,
                () -> service.narrate(new NarrativeRequest(UUID.randomUUID(), member, "en", null))).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class,
                () -> service.narrate(new NarrativeRequest(UUID.randomUUID(), member, "de", null))).code());
        assertEquals("INVALID_REQUEST", assertThrows(ForecastException.class,
                () -> service.narrate(new NarrativeRequest(UUID.randomUUID(), null, "en", null))).code());
        assertFalse(service.copilotStatus(UUID.randomUUID()).hasToken(), "an unknown user has no token");
    }

    @Test
    void narrateStoresAndReturnsTheOutcomeAndTracksProgress() {
        RunResult r = service.runNow(new RunRequest(team, member, SeededData.asOf(), "seasonal_naive", null));
        tokens.save(member, "gho_test_token");
        gateway.replies.clear();
        gateway.replies.add(FakeGateway.goodNarrative(ExportFiles.mapper().readTree(r.factsJson())));
        NarrativeResult result = service.narrate(new NarrativeRequest(r.run().id(), member, "EN", null));
        assertEquals(NarrativeStatus.OK, result.status());
        assertEquals("en", result.language());
        assertEquals(r.run().id(), result.runId());
        assertEquals("gpt-5", result.model());
        assertEquals("gho_test_token", gateway.tokenSeen);
        assertTrue(result.narrativeJson().contains("All members within capacity."));
        assertEquals(result, service.narrative(r.run().id(), "en").orElseThrow());
        assertTrue(service.narrative(r.run().id(), "fr").isEmpty());
        RunProgress p = service.progress(r.run().id());
        assertEquals("NARRATED", p.phase());
        assertEquals(100, p.percent());
        assertNotNull(p.answer());
    }

    @Test
    void aFailedNarrationIsStoredAndReturnedNotThrown() {
        RunResult r = service.runNow(new RunRequest(team, member, SeededData.asOf(), "seasonal_naive", null));
        tokens.save(member, "gho_test_token");
        gateway.replies.clear();
        gateway.replies.addAll(List.of("nope", "still nope"));
        NarrativeResult result = service.narrate(new NarrativeRequest(r.run().id(), member, "fr", "gpt-5-mini"));
        assertEquals(NarrativeStatus.FAILED, result.status());
        assertTrue(result.error().startsWith("invalid_output:"));
        assertEquals("still nope", result.rawText());
        assertNull(result.narrativeJson());
        assertEquals(result, service.narrative(r.run().id(), "fr").orElseThrow());
        assertEquals("NARRATION_FAILED", service.progress(r.run().id()).phase());
        assertEquals("gpt-5-mini", gateway.spec.model());
    }

    @Test
    void narrateWithoutATokenThrowsTokenMissingAndStoresNothing() {
        RunResult r = service.runNow(new RunRequest(team, member, SeededData.asOf(), "seasonal_naive", null));
        tokens.clear(member);
        gateway.replies.clear();
        ForecastException e = assertThrows(ForecastException.class, () -> service.narrate(new NarrativeRequest(r.run().id(), member, "en", null)));
        assertEquals("TOKEN_MISSING", e.code());
        assertTrue(service.narrative(r.run().id(), "en").isEmpty());
        assertFalse(gateway.opened, "no client is started without a token");
    }

    @Test
    void narrateRefusesARunThatIsNotDone() {
        DataSource ds = SeededData.dataSource();
        JdbcRunStore raw = new JdbcRunStore(ds, Dialect.of(ds));
        UUID queued = raw.create(new RunRequest(team, member, SeededData.asOf(), null, null), LocalDateTime.now());
        tokens.save(member, "gho_test_token");
        assertEquals("RUN_NOT_DONE", assertThrows(ForecastException.class, () -> service.narrate(new NarrativeRequest(queued, member, "en", null))).code());
    }

    @Test
    void aRejectedTokenIsThrownAndProgressSaysSo() {
        RunResult r = service.runNow(new RunRequest(team, member, SeededData.asOf(), "seasonal_naive", null));
        tokens.save(member, "gho_test_token");
        FakeGateway g = new FakeGateway();
        g.authenticated = false;
        RunProgressTracker tracker = new RunProgressTracker();
        DataSource ds = SeededData.dataSource();
        DefaultForecastService svc = build(ds, g, tracker, new JdbcRunStore(ds, Dialect.of(ds)));
        try {
            assertEquals("TOKEN_REJECTED", assertThrows(ForecastException.class, () -> svc.narrate(new NarrativeRequest(r.run().id(), member, "en", null))).code());
            assertEquals("NARRATION_FAILED", tracker.get(r.run().id()).orElseThrow().phase());
            assertTrue(tracker.get(r.run().id()).orElseThrow().message().startsWith("TOKEN_REJECTED"));
        } finally {
            try {
                svc.close();
            } catch (Exception ignored) {
                // test teardown
            }
        }
    }

    @Test
    void copilotStatusWithoutATokenDoesNotStartAClient() {
        tokens.clear(member);
        gateway.opened = false;
        CopilotStatus s = service.copilotStatus(member);
        assertFalse(s.hasToken());
        assertTrue(s.runtimeAvailable());
        assertEquals("1.0.13-preview.6", s.runtimeVersion());
        assertNull(s.authenticated());
        assertTrue(s.message().contains("token"));
        assertFalse(gateway.opened);
    }

    @Test
    void copilotStatusWithATokenReportsTheLoginAndTheQuota() {
        tokens.save(member, "gho_test_token");
        gateway.quota = Map.of("premium_interactions", Map.of("used", 12, "entitlement", 300));
        CopilotStatus s = service.copilotStatus(member);
        assertTrue(s.hasToken() && s.runtimeAvailable());
        assertEquals(Boolean.TRUE, s.authenticated());
        assertEquals("sara", s.login());
        assertTrue(s.quotaJson().contains("premium_interactions"));
        assertTrue(s.message().contains("sara"));
        assertTrue(gateway.closed);
        gateway.authenticated = false;
        CopilotStatus rejected = service.copilotStatus(member);
        assertEquals(Boolean.FALSE, rejected.authenticated());
        assertNull(rejected.quotaJson());
        gateway.authenticated = true;
        gateway.quota = null;
    }

    @Test
    void getRunPreservesAStoredNullMaseAsNullNotNaN() {
        Dialect dialect = Dialect.of(SeededData.dataSource());
        JdbcRunStore raw = new JdbcRunStore(SeededData.dataSource(), dialect);
        UUID id = raw.create(new RunRequest(team, null, SeededData.asOf(), null, null), LocalDateTime.now());
        String backtest = "{\"scores\":[{\"model\":\"xgboost\",\"origin\":\"2026-08-24\",\"horizon\":1,\"mae\":null,\"mase\":null}],"
                + "\"mase_by_model\":{\"xgboost\":null},\"unavailable\":{}}";
        raw.finish(id, "seasonal_naive", Double.NaN, backtest, List.of(), "{}", LocalDateTime.now());
        RunResult r = service.getRun(id);
        assertNull(r.scores().get(0).mase(), "stored null mase stays null, not NaN");
        assertNull(r.scores().get(0).mae());
        assertTrue(r.maseByModel().containsKey("xgboost"));
        assertNull(r.maseByModel().get("xgboost"), "stored null mase_by_model entry stays null, not NaN");
    }

    @Test
    void progressOfARunEvictedFromTheTrackerFallsBackToTheStoredRow() throws Exception {
        Dialect dialect = Dialect.of(SeededData.dataSource());
        JdbcRunStore raw = new JdbcRunStore(SeededData.dataSource(), dialect);
        RunProgressTracker tracker = new RunProgressTracker();
        DefaultForecastService svc = build(SeededData.dataSource(), new FakeGateway(), tracker, raw);
        try {
            UUID id = raw.create(new RunRequest(team, null, SeededData.asOf(), null, null), LocalDateTime.now());
            raw.finish(id, "seasonal_naive", 0.9, "{}", List.of(), "{}", LocalDateTime.now());
            tracker.start(id);
            for (int i = 0; i < RunProgressTracker.MAX_TRACKED; i++) {
                tracker.start(UUID.randomUUID());
            }
            assertTrue(tracker.get(id).isEmpty(), "the run's tracker entry was evicted by the later starts");
            RunProgress progress = svc.progress(id);
            assertEquals("DONE", progress.phase());
            assertEquals(100, progress.percent());

            UUID failedId = raw.create(new RunRequest(team, null, SeededData.asOf(), null, null), LocalDateTime.now());
            raw.fail(failedId, "boom", LocalDateTime.now());
            tracker.start(failedId);
            for (int i = 0; i < RunProgressTracker.MAX_TRACKED; i++) {
                tracker.start(UUID.randomUUID());
            }
            assertTrue(tracker.get(failedId).isEmpty());
            RunProgress failedProgress = svc.progress(failedId);
            assertEquals("FAILED", failedProgress.phase());
            assertEquals(100, failedProgress.percent());
            assertEquals("boom", failedProgress.message());
        } finally {
            svc.close();
        }
    }
}
```

Keep any other existing test method of the file that is not listed above (read the current file first; the ones above replace `boot`, `errorsCarryTheSpecCodes` and add the narration ones; the rest is copied as it is).

Add to `ForecastAutoConfigurationTest.registersTheServiceOnTopOfTheHostDataSource`'s lambda:

```java
                    assertNotNull(context.getBean(com.workloadhub.forecast.ai.CopilotGateway.class));
                    assertNotNull(context.getBean(com.workloadhub.forecast.ai.Narrator.class));
                    assertNotNull(context.getBean(com.workloadhub.forecast.store.JdbcNarrativeStore.class));
                    assertTrue(context.getBean(com.workloadhub.forecast.ai.CopilotGateway.class) instanceof com.workloadhub.forecast.ai.SdkCopilotGateway);
```

and a new test:

```java
    @Test
    void aHostSuppliedGatewayReplacesTheSdkOne() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        com.workloadhub.forecast.ai.FakeGateway fake = new com.workloadhub.forecast.ai.FakeGateway();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ForecastAutoConfiguration.class))
                .withBean(DataSource.class, () -> ds)
                .withBean(com.workloadhub.forecast.ai.CopilotGateway.class, () -> fake)
                .run(context -> assertTrue(context.getBean(com.workloadhub.forecast.ai.CopilotGateway.class) == fake));
    }
```

- [ ] **Step 3: Run them to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='DefaultForecastServiceTest,ForecastAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (constructor arity, new beans).

- [ ] **Step 4: The service**

In `DefaultForecastService.java`:

Add imports:

```java
import com.workloadhub.forecast.ai.AuthStatus;
import com.workloadhub.forecast.ai.CopilotConnection;
import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.NarrationOutcome;
import com.workloadhub.forecast.ai.NarrationProgress;
import com.workloadhub.forecast.ai.Narrator;
import com.workloadhub.forecast.ai.Prompts;
import com.workloadhub.forecast.ai.RuntimeInfo;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.store.JdbcNarrativeStore;
import java.time.Duration;
import java.util.Locale;
```

Add the fields and replace the constructor:

```java
    public static final Duration QUOTA_TIMEOUT = Duration.ofSeconds(10);

    private final GitHubTokenStore tokens;
    private final JdbcNarrativeStore narratives;
    private final Narrator narrator;
    private final CopilotGateway gateway;

    public DefaultForecastService(DataSource dataSource, Dialect dialect, ForecastRunner runner, JdbcRunStore store, RunProgressTracker progress,
            int threads, boolean plannedWorkDefault, GitHubTokenStore tokens, JdbcNarrativeStore narratives, Narrator narrator, CopilotGateway gateway) {
        this.dataSource = dataSource;
        this.dialect = dialect;
        this.runner = runner;
        this.store = store;
        this.progress = progress;
        this.tokens = tokens;
        this.narratives = narratives;
        this.narrator = narrator;
        this.gateway = gateway;
        this.executor = Executors.newFixedThreadPool(Math.max(1, threads), r -> {
            Thread t = new Thread(r, "forecast-run");
            t.setDaemon(true);
            return t;
        });
    }
```

Replace the three throwing methods:

```java
    @Override
    public NarrativeResult narrate(NarrativeRequest request) {
        if (request == null || request.runId() == null) {
            throw ForecastException.invalidRequest("runId is required");
        }
        if (request.requestedBy() == null) {
            throw ForecastException.invalidRequest("requestedBy is required");
        }
        String language = request.language() == null ? "" : request.language().trim().toLowerCase(Locale.ROOT);
        if (!Prompts.SUPPORTED_LANGUAGES.contains(language)) {
            throw ForecastException.invalidRequest("language must be en or fr");
        }
        UUID runId = request.runId();
        RunSummary run = store.find(runId).orElseThrow(() -> ForecastException.of("RUN_NOT_FOUND", "run " + runId + " not found"));
        if (run.status() != RunStatus.DONE) {
            throw ForecastException.of("RUN_NOT_DONE", "run " + runId + " is " + run.status());
        }
        // The one place the module reads a user's token.
        String token = tokens.load(request.requestedBy())
                .orElseThrow(() -> ForecastException.of("TOKEN_MISSING", "no GitHub token stored for user " + request.requestedBy()));
        JsonNode facts = ExportFiles.mapper().readTree(store.facts(runId).orElse("{}"));
        NarrationProgress live = progress.narrationProgress(runId);
        NarrationOutcome outcome;
        try {
            outcome = narrator.narrate(facts, language, request.model(), token, live);
        } catch (ForecastException e) {
            progress.narrationFailed(runId, e.code() + ": " + e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            LOG.error("narration of run {} failed", runId, e);
            progress.narrationFailed(runId, "COPILOT_UNAVAILABLE: " + e.getMessage());
            throw ForecastException.of("COPILOT_UNAVAILABLE", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        NarrativeResult result = narratives.save(runId, language, outcome, LocalDateTime.now());
        if (outcome.status() == NarrativeStatus.FAILED) {
            progress.narrationFailed(runId, outcome.error());
        } else {
            progress.narrated(runId);
        }
        return result;
    }

    @Override
    public Optional<NarrativeResult> narrative(UUID runId, String language) {
        if (store.find(runId).isEmpty()) {
            throw ForecastException.of("RUN_NOT_FOUND", "run " + runId + " not found");
        }
        return narratives.latest(runId, language == null ? "" : language.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public CopilotStatus copilotStatus(UUID userId) {
        if (userId == null) {
            throw ForecastException.invalidRequest("userId is required");
        }
        boolean hasToken = tokens.has(userId);
        RuntimeInfo rt = gateway.runtime();
        if (!hasToken) {
            return new CopilotStatus(userId, false, rt.available(), rt.path(), rt.version(), null, null, null, "no GitHub token stored for this user");
        }
        if (!rt.available()) {
            return new CopilotStatus(userId, true, false, rt.path(), rt.version(), null, null, null, rt.message());
        }
        String token = tokens.load(userId).orElseThrow(() -> ForecastException.of("TOKEN_MISSING", "no GitHub token stored for user " + userId));
        CopilotConnection connection;
        try {
            connection = gateway.open(token);
        } catch (ForecastException e) {
            return new CopilotStatus(userId, true, false, rt.path(), rt.version(), null, null, null, e.getMessage());
        }
        try (connection) {
            AuthStatus auth;
            try {
                auth = connection.authStatus();
            } catch (RuntimeException e) {
                return new CopilotStatus(userId, true, true, rt.path(), rt.version(), false, null, null, "could not read Copilot sign-in status: " + e.getMessage());
            }
            if (!auth.authenticated()) {
                return new CopilotStatus(userId, true, true, rt.path(), rt.version(), false, null, null,
                        auth.message() != null ? auth.message() : "the token is not accepted by Copilot");
            }
            Optional<Map<String, Object>> quota = connection.quota(QUOTA_TIMEOUT);
            String quotaJson = quota.map(q -> ExportFiles.mapper().writeValueAsString(q)).orElse(null);
            String message = "signed in as " + auth.login() + (quota.isPresent() ? "" : "; quota unavailable");
            return new CopilotStatus(userId, true, true, rt.path(), rt.version(), true, auth.login(), quotaJson, message);
        }
    }
```

- [ ] **Step 5: The beans and the CLI services**

In `ForecastAutoConfiguration.java` add imports for `CopilotGateway`, `SdkCopilotGateway`, `Narrator`, `Prompts`, `JdbcNarrativeStore`, `GitHubTokenStore` (already), `java.nio.file.Path`, `java.time.Duration`, and these beans before the service bean:

```java
    @Bean
    @ConditionalOnMissingBean
    CopilotGateway copilotGateway(ForecastProperties properties) {
        return new SdkCopilotGateway(Path.of(properties.getWorkDir(), "copilot"), properties.getCopilot().getCliPath());
    }

    @Bean
    @ConditionalOnMissingBean
    JdbcNarrativeStore jdbcNarrativeStore(DataSource dataSource, Dialect dialect, ForecastMigrationsRunner migrated) {
        return new JdbcNarrativeStore(dataSource, dialect);
    }

    @Bean
    @ConditionalOnMissingBean
    Narrator narrator(CopilotGateway gateway, ForecastProperties properties) {
        return new Narrator(gateway, Prompts.load(), Duration.ofSeconds(properties.getCopilot().getTimeoutSeconds()), properties.getCopilot().getModel());
    }
```

and change the service bean to:

```java
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    ForecastService forecastService(DataSource dataSource, Dialect dialect, ForecastRunner runner, JdbcRunStore store, RunProgressTracker progress,
            ForecastProperties properties, GitHubTokenStore tokens, JdbcNarrativeStore narratives, Narrator narrator, CopilotGateway gateway) {
        return new DefaultForecastService(dataSource, dialect, runner, store, progress, properties.getRunThreads(),
                properties.getPlannedWork().isEnabled(), tokens, narratives, narrator, gateway);
    }
```

Replace `forecast-cli/.../Services.java` with:

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.ai.Narrator;
import com.workloadhub.forecast.ai.Prompts;
import com.workloadhub.forecast.ai.SdkCopilotGateway;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.capacity.CapacityRule;
import com.workloadhub.forecast.run.ForecastRunner;
import com.workloadhub.forecast.service.DefaultForecastService;
import com.workloadhub.forecast.service.RunProgressTracker;
import com.workloadhub.forecast.store.AesGcmCipher;
import com.workloadhub.forecast.store.Dialect;
import com.workloadhub.forecast.store.ForecastMigrations;
import com.workloadhub.forecast.store.JdbcGitHubTokenStore;
import com.workloadhub.forecast.store.JdbcNarrativeStore;
import com.workloadhub.forecast.store.JdbcRunStore;
import java.nio.file.Path;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The module's services on a CLI-owned SQLite file: one thread, planned work on, default capacity 40. Narration
 * reads its settings from the environment: WHF_TOKEN_KEY (base64, 32 bytes; required to store or read a token),
 * WHF_COPILOT_CLI_PATH (blank: the in-process runtime) and WHF_COPILOT_MODEL (blank: the account default).
 */
record Services(DefaultForecastService service, Dialect dialect, JdbcClient jdbc, ForecastRunner runner, GitHubTokenStore tokens, RunProgressTracker progress)
        implements AutoCloseable {

    static final String TOKEN_KEY_ENV = "WHF_TOKEN_KEY";
    static final String CLI_PATH_ENV = "WHF_COPILOT_CLI_PATH";
    static final String MODEL_ENV = "WHF_COPILOT_MODEL";
    static final Duration NARRATION_TIMEOUT = Duration.ofSeconds(300);

    static boolean tokenKeyConfigured() {
        String key = System.getenv(TOKEN_KEY_ENV);
        return key != null && !key.isBlank();
    }

    static Services open(DataSource ds) {
        ForecastMigrations.run(ds);
        Dialect dialect = Dialect.of(ds);
        JdbcClient jdbc = JdbcClient.create(ds);
        ForecastRunner runner = new ForecastRunner(new CapacityRule(40), true);
        String key = System.getenv(TOKEN_KEY_ENV);
        JdbcGitHubTokenStore tokens = new JdbcGitHubTokenStore(jdbc, dialect, key == null || key.isBlank() ? null : AesGcmCipher.fromBase64Key(key.trim()));
        Path home = Path.of(System.getProperty("user.home"), ".workloadhub-forecast", "copilot");
        SdkCopilotGateway gateway = new SdkCopilotGateway(home, System.getenv(CLI_PATH_ENV));
        Narrator narrator = new Narrator(gateway, Prompts.load(), NARRATION_TIMEOUT, System.getenv(MODEL_ENV));
        RunProgressTracker progress = new RunProgressTracker();
        DefaultForecastService service = new DefaultForecastService(ds, dialect, runner, new JdbcRunStore(ds, dialect), progress, 1, true, tokens,
                new JdbcNarrativeStore(ds, dialect), narrator, gateway);
        return new Services(service, dialect, jdbc, runner, tokens, progress);
    }

    @Override
    public void close() throws Exception {
        service.close();
    }
}
```

- [ ] **Step 6: Run the module's tests**

Run: `cd server && mvn -B -q verify`
Expected: PASS for both modules (the CLI compiles against the new `Services`; its existing tests still pass).

- [ ] **Step 7: Commit**

```bash
git add server/forecast-core/src server/forecast-cli/src
git commit -m "feat(server): narrate, narrative and copilotStatus on the service, wired as beans and in the CLI

narrate reads the user's token in one place, runs the narrator on the
caller's thread with live progress, stores every outcome and returns it;
only pre-session failures throw. copilotStatus starts a client only when a
token is stored and the runtime resolves."
```

---

### Task 10: The REST controller, its error mapping, and the sample host

**Files:**
- Create: `server/forecast-core/src/main/java/com/workloadhub/forecast/web/ForecastController.java`, `.../web/ForecastExceptionHandler.java`, `.../web/ForecastWebConfiguration.java`
- Modify: `server/forecast-core/src/main/java/com/workloadhub/forecast/ForecastAutoConfiguration.java` (`@Import`)
- Test: `server/forecast-core/src/test/java/com/workloadhub/forecast/web/ForecastControllerTest.java`, `.../samplehost/SampleHostApplication.java`, `.../samplehost/SampleHostIntegrationTest.java`

**Interfaces:**
- Consumes: `ForecastService`, `GitHubTokenStore`, the `api` records, `FakeGateway` (public since Task 9), `SeededData`.
- Produces: the routes of the spec (section 12) under `whf.web.base-path`; `ForecastExceptionHandler.status(String code) -> HttpStatus` (404 for codes ending in `_NOT_FOUND`, 400 for `INVALID_REQUEST`, 409 otherwise); a `NARRATIVE_NOT_FOUND` code for `GET /runs/{id}/narratives/{lang}` without a row; request bodies `NarrateBody(UUID requestedBy, String language, String model)`, `TokenBody(String token)`, response `StartedRun(UUID id)`, error `ErrorBody(String code, String message)`.

- [ ] **Step 1: Write the failing controller test**

`web/ForecastControllerTest.java`:

```java
package com.workloadhub.forecast.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunStatus;
import com.workloadhub.forecast.api.RunSummary;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ForecastController.class, properties = "whf.web.base-path=/forecast-api")
class ForecastControllerTest {

    /** The scan root: this package only, so the controller and the advice are found and nothing else. */
    @SpringBootApplication
    static class Boot {
    }

    static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID TEAM = UUID.fromString("44444444-4444-4444-4444-444444444444");
    static final UUID USER = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000004");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ForecastService service;

    @MockitoBean
    GitHubTokenStore tokens;

    static RunSummary summary() {
        return new RunSummary(RUN, TEAM, USER, LocalDate.of(2026, 9, 6), RunStatus.DONE, null, "xgboost", 0.83, null, LocalDateTime.of(2026, 9, 6, 10, 0), null);
    }

    static NarrativeResult narrative(NarrativeStatus status) {
        return new NarrativeResult(UUID.randomUUID(), RUN, "en", status, "gpt-5", status == NarrativeStatus.FAILED ? null : "{\"run_summary\":\"ok\"}",
                null, "{}", "{\"source\":\"none\"}", null, 1, 3, LocalDateTime.of(2026, 9, 6, 11, 0));
    }

    @Test
    void startsARunAndAnswers202WithItsId() throws Exception {
        when(service.startRun(any(RunRequest.class))).thenReturn(RUN);
        mvc.perform(post("/forecast-api/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\": \"" + TEAM + "\", \"requestedBy\": \"" + USER + "\", \"asOf\": \"2026-09-06\", \"forcedModel\": \"xgboost\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value(RUN.toString()));
    }

    @Test
    void readsRunsProgressAndLists() throws Exception {
        when(service.getRun(RUN)).thenReturn(new RunResult(summary(), List.of(), Map.of(), Map.of(), List.of(), "{}"));
        when(service.progress(RUN)).thenReturn(new RunProgress(RUN, "NARRATING", 40, "tool get_member_forecast", "thinking", "{"));
        when(service.listRuns(TEAM, 5)).thenReturn(List.of(summary()));
        mvc.perform(get("/forecast-api/runs/" + RUN)).andExpect(status().isOk()).andExpect(jsonPath("$.run.championModel").value("xgboost"));
        mvc.perform(get("/forecast-api/runs/" + RUN + "/progress")).andExpect(status().isOk())
                .andExpect(jsonPath("$.phase").value("NARRATING")).andExpect(jsonPath("$.answer").value("{"));
        mvc.perform(get("/forecast-api/teams/" + TEAM + "/runs?limit=5")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(RUN.toString()));
    }

    @Test
    void narratesAndReadsNarratives() throws Exception {
        when(service.narrate(new NarrativeRequest(RUN, USER, "en", null))).thenReturn(narrative(NarrativeStatus.OK));
        when(service.narrative(RUN, "en")).thenReturn(Optional.of(narrative(NarrativeStatus.UNVERIFIED)));
        when(service.narrative(RUN, "fr")).thenReturn(Optional.empty());
        mvc.perform(post("/forecast-api/runs/" + RUN + "/narratives").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedBy\": \"" + USER + "\", \"language\": \"en\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK")).andExpect(jsonPath("$.model").value("gpt-5"));
        mvc.perform(get("/forecast-api/runs/" + RUN + "/narratives/en")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNVERIFIED"));
        mvc.perform(get("/forecast-api/runs/" + RUN + "/narratives/fr")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NARRATIVE_NOT_FOUND"));
    }

    @Test
    void tokensAndCopilotStatus() throws Exception {
        when(service.copilotStatus(USER)).thenReturn(new CopilotStatus(USER, true, true, "/x/runtime.node", "1.0.13-preview.6", true, "sara", null, "signed in as sara"));
        mvc.perform(put("/forecast-api/users/" + USER + "/github-token").contentType(MediaType.APPLICATION_JSON).content("{\"token\": \"gho_abc\"}"))
                .andExpect(status().isNoContent());
        verify(tokens).save(USER, "gho_abc");
        mvc.perform(delete("/forecast-api/users/" + USER + "/github-token")).andExpect(status().isNoContent());
        verify(tokens).clear(USER);
        mvc.perform(get("/forecast-api/copilot/status?userId=" + USER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("sara")).andExpect(jsonPath("$.hasToken").value(true));
    }

    @Test
    void errorsAreMappedByCode() throws Exception {
        when(service.getRun(RUN)).thenThrow(ForecastException.of("RUN_NOT_FOUND", "run " + RUN + " not found"));
        when(service.progress(RUN)).thenThrow(ForecastException.of("RUN_NOT_DONE", "run is QUEUED"));
        when(service.narrate(any(NarrativeRequest.class))).thenThrow(ForecastException.of("TOKEN_MISSING", "no token"));
        when(service.copilotStatus(USER)).thenThrow(ForecastException.invalidRequest("bad"));
        mvc.perform(get("/forecast-api/runs/" + RUN)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND")).andExpect(jsonPath("$.message").value("run " + RUN + " not found"));
        mvc.perform(get("/forecast-api/runs/" + RUN + "/progress")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RUN_NOT_DONE"));
        mvc.perform(post("/forecast-api/runs/" + RUN + "/narratives").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedBy\": \"" + USER + "\", \"language\": \"en\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOKEN_MISSING"));
        mvc.perform(get("/forecast-api/copilot/status?userId=" + USER)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/forecast-api/runs/not-a-uuid")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/forecast-api/runs").contentType(MediaType.APPLICATION_JSON).content("{\"teamId\": \"" + TEAM + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
```

The last request has no `asOf`, so `RunRequest`'s compact constructor throws inside Jackson; the advice maps the resulting `HttpMessageNotReadableException` to 400.

- [ ] **Step 2: Write the sample host and its integration test**

`samplehost/SampleHostApplication.java` (test sources):

```java
package com.workloadhub.forecast.samplehost;

import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.data.ExportImporter;
import com.workloadhub.forecast.store.DatabaseTestSupport;
import com.workloadhub.forecast.store.WorkloadHubSchema;
import com.workloadhub.forecast.testing.SeededData;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * What the WorkloadHub server looks like to this module: a Spring Boot application with its own DataSource, the
 * module on the classpath, and (here) a scripted Copilot gateway instead of the SDK. Nothing else is configured.
 */
@SpringBootApplication
public class SampleHostApplication {

    @Bean
    DataSource dataSource() {
        DataSource ds = DatabaseTestSupport.sqliteInMemory();
        WorkloadHubSchema.createSqlite(ds);
        new ExportImporter(ds).importAll(SeededData.envelope(), true);
        return ds;
    }

    @Bean
    CopilotGateway copilotGateway() {
        return new FakeGateway();
    }
}
```

`samplehost/SampleHostIntegrationTest.java`:

```java
package com.workloadhub.forecast.samplehost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.workloadhub.forecast.ai.CopilotGateway;
import com.workloadhub.forecast.ai.FakeGateway;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.data.ExportFiles;
import com.workloadhub.forecast.data.ForecastData;
import com.workloadhub.forecast.data.rows.TeamRow;
import com.workloadhub.forecast.testing.SeededData;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

@SpringBootTest(classes = SampleHostApplication.class, properties = {"whf.web.enabled=true", "whf.run-threads=1",
        "whf.token-key=" + SampleHostIntegrationTest.KEY})
@AutoConfigureMockMvc
class SampleHostIntegrationTest {

    static final String KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Autowired
    MockMvc mvc;

    @Autowired
    ForecastService service;

    @Autowired
    CopilotGateway gateway;

    static JsonNode json(MvcResult r) throws Exception {
        return ExportFiles.mapper().readTree(r.getResponse().getContentAsString());
    }

    @Test
    void theHostRunsNarratesAndReadsThroughTheRestSurface() throws Exception {
        assertEquals(Base64.getEncoder().encodeToString(new byte[32]), KEY);
        ForecastData data = SeededData.data();
        UUID team = data.teams().stream().filter(t -> !data.membersOfTeam(t.id()).isEmpty()).map(TeamRow::id).findFirst().orElseThrow();
        UUID member = data.membersOfTeam(team).get(0).id();
        FakeGateway fake = (FakeGateway) gateway;

        MvcResult started = mvc.perform(post("/api/forecast/runs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\": \"" + team + "\", \"requestedBy\": \"" + member + "\", \"asOf\": \"" + SeededData.asOf() + "\", \"forcedModel\": \"seasonal_naive\"}"))
                .andExpect(status().isAccepted()).andReturn();
        UUID run = UUID.fromString(json(started).path("id").asText());
        long deadline = System.currentTimeMillis() + 120_000;
        String phase = "";
        while (!phase.equals("DONE") && !phase.equals("FAILED") && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            phase = json(mvc.perform(get("/api/forecast/runs/" + run + "/progress")).andExpect(status().isOk()).andReturn()).path("phase").asText();
        }
        assertEquals("DONE", phase);
        JsonNode result = json(mvc.perform(get("/api/forecast/runs/" + run)).andExpect(status().isOk()).andReturn());
        assertTrue(result.path("memberWeeks").size() > 0);
        assertEquals("seasonal_naive", result.path("run").path("championModel").asText());

        mvc.perform(get("/api/forecast/copilot/status?userId=" + member)).andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(false));
        mvc.perform(put("/api/forecast/users/" + member + "/github-token").contentType(MediaType.APPLICATION_JSON).content("{\"token\": \"gho_sample\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/forecast/copilot/status?userId=" + member)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasToken").value(true)).andExpect(jsonPath("$.authenticated").value(true)).andExpect(jsonPath("$.login").value("sara"));

        fake.replies.clear();
        fake.replies.add(FakeGateway.goodNarrative(ExportFiles.mapper().readTree(result.path("factsJson").asText())));
        mvc.perform(post("/api/forecast/runs/" + run + "/narratives").contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestedBy\": \"" + member + "\", \"language\": \"en\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK")).andExpect(jsonPath("$.language").value("en"));
        mvc.perform(get("/api/forecast/runs/" + run + "/narratives/en")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("OK"));
        mvc.perform(get("/api/forecast/runs/" + run + "/narratives/fr")).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NARRATIVE_NOT_FOUND"));
        mvc.perform(get("/api/forecast/runs/" + run + "/progress")).andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("NARRATED"));
        mvc.perform(get("/api/forecast/runs/" + UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
        mvc.perform(delete("/api/forecast/users/" + member + "/github-token")).andExpect(status().isNoContent());
        mvc.perform(get("/api/forecast/copilot/status?userId=" + member)).andExpect(status().isOk()).andExpect(jsonPath("$.hasToken").value(false));
        assertEquals("gho_sample", fake.tokenSeen, "the stored token reached the gateway");
    }
}
```

- [ ] **Step 3: Run them to see them fail**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='ForecastControllerTest,SampleHostIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure.

- [ ] **Step 4: Implement the web package**

`web/ForecastController.java`:

```java
package com.workloadhub.forecast.web;

import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.api.RunRequest;
import com.workloadhub.forecast.api.RunResult;
import com.workloadhub.forecast.api.RunSummary;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The service, one to one, under whf.web.base-path. Authorisation is the host's: requestedBy is trusted. */
@RestController
@RequestMapping("${whf.web.base-path:/api/forecast}")
public class ForecastController {

    public record StartedRun(UUID id) {
    }

    public record NarrateBody(UUID requestedBy, String language, String model) {
    }

    public record TokenBody(String token) {
    }

    private final ForecastService service;
    private final GitHubTokenStore tokens;

    public ForecastController(ForecastService service, GitHubTokenStore tokens) {
        this.service = service;
        this.tokens = tokens;
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StartedRun start(@RequestBody RunRequest request) {
        return new StartedRun(service.startRun(request));
    }

    @GetMapping("/runs/{id}")
    public RunResult run(@PathVariable UUID id) {
        return service.getRun(id);
    }

    @GetMapping("/teams/{teamId}/runs")
    public List<RunSummary> runs(@PathVariable UUID teamId, @RequestParam(defaultValue = "20") int limit) {
        return service.listRuns(teamId, limit);
    }

    @GetMapping("/runs/{id}/progress")
    public RunProgress progress(@PathVariable UUID id) {
        return service.progress(id);
    }

    @PostMapping("/runs/{id}/narratives")
    public NarrativeResult narrate(@PathVariable UUID id, @RequestBody NarrateBody body) {
        return service.narrate(new NarrativeRequest(id, body.requestedBy(), body.language(), body.model()));
    }

    @GetMapping("/runs/{id}/narratives/{lang}")
    public NarrativeResult narrative(@PathVariable UUID id, @PathVariable String lang) {
        return service.narrative(id, lang).orElseThrow(() -> ForecastException.of("NARRATIVE_NOT_FOUND", "no " + lang + " narrative for run " + id));
    }

    @GetMapping("/copilot/status")
    public CopilotStatus copilotStatus(@RequestParam UUID userId) {
        return service.copilotStatus(userId);
    }

    @PutMapping("/users/{id}/github-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void putToken(@PathVariable UUID id, @RequestBody TokenBody body) {
        tokens.save(id, body == null ? null : body.token());
    }

    @DeleteMapping("/users/{id}/github-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteToken(@PathVariable UUID id) {
        tokens.clear(id);
    }
}
```

`web/ForecastExceptionHandler.java`:

```java
package com.workloadhub.forecast.web;

import com.workloadhub.forecast.api.ForecastException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Every error is {code, message}: 404 for what is not found, 400 for a bad request, 409 for a state that forbids the action. */
@RestControllerAdvice(assignableTypes = ForecastController.class)
public class ForecastExceptionHandler {

    public record ErrorBody(String code, String message) {
    }

    static HttpStatus status(String code) {
        if (code.endsWith("_NOT_FOUND")) {
            return HttpStatus.NOT_FOUND;
        }
        if (code.equals("INVALID_REQUEST")) {
            return HttpStatus.BAD_REQUEST;
        }
        return HttpStatus.CONFLICT;
    }

    @ExceptionHandler(ForecastException.class)
    public ResponseEntity<ErrorBody> forecast(ForecastException e) {
        return ResponseEntity.status(status(e.code())).body(new ErrorBody(e.code(), e.getMessage()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorBody> unreadable(Exception e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root instanceof ForecastException fe ? fe.getMessage() : "malformed request: " + firstLine(root.getMessage());
        return ResponseEntity.badRequest().body(new ErrorBody("INVALID_REQUEST", message));
    }

    private static String firstLine(String s) {
        return s == null ? "" : s.lines().findFirst().orElse("");
    }
}
```

`web/ForecastWebConfiguration.java`:

```java
package com.workloadhub.forecast.web;

import com.workloadhub.forecast.api.ForecastService;
import com.workloadhub.forecast.api.GitHubTokenStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The REST surface, only when the host asks for it and runs Spring MVC. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "whf.web", name = "enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
public class ForecastWebConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ForecastController forecastController(ForecastService service, GitHubTokenStore tokens) {
        return new ForecastController(service, tokens);
    }

    @Bean
    @ConditionalOnMissingBean
    ForecastExceptionHandler forecastExceptionHandler() {
        return new ForecastExceptionHandler();
    }
}
```

In `ForecastAutoConfiguration.java` add `import org.springframework.context.annotation.Import;` and `@Import(ForecastWebConfiguration.class)` under the existing class annotations.

- [ ] **Step 5: Run the tests, then the whole module**

Run: `cd server && mvn -B -q -pl forecast-core test -Dtest='ForecastControllerTest,SampleHostIntegrationTest,ForecastAutoConfigurationTest' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If `@WebMvcTest` cannot find the controller, the nested `Boot` class is scanning the wrong package: it must sit in `com.workloadhub.forecast.web`, which it does when nested in this test; if the advice is not applied, check that `ForecastExceptionHandler` carries `@RestControllerAdvice` (component-scanned) and is not excluded by the property condition (it is not in `ForecastWebConfiguration`'s conditions, only its bean is).

Then `cd server && mvn -B -q verify` must be green in under three minutes without Docker.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-core/src
git commit -m "feat(server): the REST controller, its error mapping and a sample host test

The controller maps the service one to one under whf.web.base-path and
only exists when the host enables it and runs Spring MVC; the sample host
boots the auto-configuration on its own DataSource and drives a run and a
narration through MockMvc with a scripted gateway."
```

---

### Task 11: CLI `narrate` and `copilot status`, the README, the backlog and the status note

**Files:**
- Create: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/NarrateCommand.java`, `.../cli/CopilotCommand.java`
- Modify: `server/forecast-cli/src/main/java/com/workloadhub/forecast/cli/ForecastCli.java` (register the commands)
- Test: `server/forecast-cli/src/test/java/com/workloadhub/forecast/cli/NarrateCommandTest.java`
- Modify: `server/README.md`, `docs/backlog.md`, `CLAUDE.md`

**Interfaces:**
- Consumes: `Services` (Task 9: `service()`, `jdbc()`, `dialect()`, `tokens()`, `progress()`, `tokenKeyConfigured()`), `TeamArg.resolveUser`, `NarrativeRequest`, `NarrativeResult`, `CopilotStatus`, `RunProgress`.
- Produces: `forecast narrate --run <id> --user <name or id> [--lang en|fr] [--model m] [--token-env GITHUB_TOKEN] [--db f] [--json]` (exit 0 OK, 3 UNVERIFIED, 1 FAILED or error, 2 usage), `forecast copilot status --user <name or id> [--db f]` (exit 0, 2 usage).

- [ ] **Step 1: Write the failing CLI test**

`cli/NarrateCommandTest.java`:

```java
package com.workloadhub.forecast.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class NarrateCommandTest {

    static String captureErr(CommandLine cli, int expectedExit, String... args) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream old = System.err;
        System.setErr(new PrintStream(err, true));
        try {
            assertEquals(expectedExit, cli.execute(args), () -> "exit code for " + String.join(" ", args) + "\n" + err);
        } finally {
            System.setErr(old);
        }
        return err.toString();
    }

    static Path seeded(Path dir, CommandLine cli) {
        Path db = dir.resolve("n.db");
        Path seeded = dir.resolve("seeded.json");
        assertEquals(0, cli.execute("seed", "--synthetic", "--users", "12", "--weeks", "12", "--seed", "5", "--end", "2026-09-06", "--out", seeded.toString()));
        assertEquals(0, cli.execute("init-db", "--db", db.toString()));
        assertEquals(0, cli.execute("import", "--db", db.toString(), seeded.toString()));
        return db;
    }

    @Test
    void narrateRefusesToRunWithoutTheTokenKey(@TempDir Path dir) {
        Assumptions.assumeTrue(System.getenv(Services.TOKEN_KEY_ENV) == null, "WHF_TOKEN_KEY is set in this environment");
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        Path db = seeded(dir, cli);
        String err = captureErr(cli, 2, "narrate", "--db", db.toString(), "--run", "11111111-1111-1111-1111-111111111111", "--user", "nobody");
        assertTrue(err.contains(Services.TOKEN_KEY_ENV), err);
        assertTrue(err.contains("openssl rand -base64 32"), err);
    }

    @Test
    void narrateRejectsABadRunIdAndAnUnknownLanguage(@TempDir Path dir) {
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        Path db = seeded(dir, cli);
        assertEquals(2, cli.execute("narrate", "--db", db.toString(), "--run", "not-a-uuid", "--user", "x", "--lang", "en"));
        assertEquals(2, cli.execute("narrate", "--db", db.toString(), "--run", "11111111-1111-1111-1111-111111111111", "--user", "x", "--lang", "de"));
    }

    /**
     * `copilot status` calls the gateway's runtime(), which without a CLI path resolves the in-process runtime and
     * extracts 91 MB into ~/.copilot; the test points at a stub CLI through the system property the CLI reads
     * first, so the suite never extracts anything. The user id is read from the seeded database.
     */
    @Test
    void copilotStatusReportsAUserWithoutAToken(@TempDir Path dir) throws Exception {
        CommandLine cli = new CommandLine(new ForecastCli.Root());
        Path db = seeded(dir, cli);
        Path stub = dir.resolve("copilot");
        Files.writeString(stub, "#!/bin/sh\n");
        assertTrue(stub.toFile().setExecutable(true));
        System.setProperty("whf.copilot.cli-path", stub.toString());
        try {
            String user;
            DbOptions options = new DbOptions();
            options.db = db;
            try (Services s = Services.open(options.dataSource())) {
                user = s.jdbc().sql("SELECT id FROM users ORDER BY id LIMIT 1").query(String.class).single();
            }
            String out = RunCommandTest.capture(cli, 0, "copilot", "status", "--db", db.toString(), "--user", user);
            assertTrue(out.contains("hasToken: false"), out);
            assertTrue(out.contains("runtimeAvailable: true"), out);
            assertTrue(out.contains("runtimeVersion: 1.0.13-preview.6"), out);
            assertEquals(2, cli.execute("copilot", "status", "--db", db.toString(), "--user", "nobody-at-all"));
        } finally {
            System.clearProperty("whf.copilot.cli-path");
        }
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `cd server && mvn -B -q -pl forecast-cli -am test -Dtest=NarrateCommandTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL (unknown subcommands `narrate` and `copilot`: picocli exits 2 for the first test by accident, but the other two fail on their assertions).

- [ ] **Step 3: Implement the commands**

In `Services.open`, replace `System.getenv(CLI_PATH_ENV)` with `cliPath()` and add:

```java
    /** The system property wins over the environment, so tests can point at a stub without touching the environment. */
    static String cliPath() {
        String fromProperty = System.getProperty("whf.copilot.cli-path");
        return fromProperty != null && !fromProperty.isBlank() ? fromProperty : System.getenv(CLI_PATH_ENV);
    }
```

`cli/NarrateCommand.java`:

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.ForecastException;
import com.workloadhub.forecast.api.NarrativeRequest;
import com.workloadhub.forecast.api.NarrativeResult;
import com.workloadhub.forecast.api.NarrativeStatus;
import com.workloadhub.forecast.api.RunProgress;
import com.workloadhub.forecast.data.ExportFiles;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

@Command(name = "narrate", description = "Store the user's GitHub token, narrate a finished run through their Copilot seat, and print the verified narrative.")
public class NarrateCommand implements Callable<Integer> {

    private static final Set<String> USAGE_CODES = Set.of("RUN_NOT_FOUND", "INVALID_REQUEST", "USER_NOT_FOUND", "TOKEN_KEY_MISSING");
    static final long POLL_MILLIS = 300;

    @Mixin DbOptions db;

    @Option(names = "--run", required = true, description = "Run id")
    String run;

    @Option(names = "--user", required = true, description = "Requesting user, name or id; the token is stored for this user")
    String user;

    @Option(names = "--lang", defaultValue = "en", description = "en or fr (default: ${DEFAULT-VALUE})")
    String lang;

    @Option(names = "--model", description = "Copilot model (default: the account's default, or WHF_COPILOT_MODEL)")
    String model;

    @Option(names = "--token-env", defaultValue = "GITHUB_TOKEN", description = "Environment variable holding the GitHub token (default: ${DEFAULT-VALUE})")
    String tokenEnv;

    @Option(names = "--json", description = "Print the stored result as JSON")
    boolean json;

    @Override
    public Integer call() throws Exception {
        if (!Services.tokenKeyConfigured()) {
            System.err.println("error: " + Services.TOKEN_KEY_ENV + " is not set; it must hold a base64 AES-256 key (openssl rand -base64 32)");
            return 2;
        }
        UUID runId;
        try {
            runId = UUID.fromString(run.trim());
        } catch (IllegalArgumentException e) {
            System.err.println("error: --run must be a run id (UUID)");
            return 2;
        }
        String language = lang.trim().toLowerCase(java.util.Locale.ROOT);
        if (!language.equals("en") && !language.equals("fr")) {
            System.err.println("error: --lang must be en or fr");
            return 2;
        }
        String token = System.getenv(tokenEnv);
        if (token == null || token.isBlank()) {
            System.err.println("error: environment variable " + tokenEnv + " is empty; it must hold the user's GitHub token");
            return 2;
        }
        try (Services s = Services.open(db.dataSource())) {
            UUID userId;
            try {
                userId = TeamArg.resolveUser(s.jdbc(), s.dialect(), user);
            } catch (IllegalArgumentException e) {
                System.err.println("error: " + e.getMessage());
                return 2;
            }
            try {
                s.tokens().save(userId, token);
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return 2;
            }
            Thread printer = progressPrinter(s, runId);
            printer.start();
            NarrativeResult result;
            try {
                result = s.service().narrate(new NarrativeRequest(runId, userId, language, model));
            } catch (ForecastException e) {
                System.err.println("error: " + e.code() + ": " + e.getMessage());
                return USAGE_CODES.contains(e.code()) ? 2 : 1;
            } finally {
                printer.interrupt();
                printer.join(2_000);
            }
            if (json) {
                System.out.println(ExportFiles.mapper().writeValueAsString(result));
            } else {
                print(result);
            }
            return switch (result.status()) {
                case OK -> 0;
                case UNVERIFIED -> 3;
                case FAILED -> 1;
            };
        }
    }

    /** Polls the tracker and writes new steps, thinking and answer text to stderr as they arrive. */
    private static Thread progressPrinter(Services s, UUID runId) {
        Thread t = new Thread(() -> {
            String lastMessage = "";
            int thinkingShown = 0;
            int answerShown = 0;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Optional<RunProgress> p = s.progress().get(runId);
                    if (p.isPresent() && p.get().phase().startsWith("NARRAT")) {
                        RunProgress rp = p.get();
                        if (!rp.message().equals(lastMessage)) {
                            System.err.println("[" + rp.percent() + "%] " + rp.message());
                            lastMessage = rp.message();
                        }
                        thinkingShown = tail("thinking", rp.thinking(), thinkingShown);
                        answerShown = tail("answer", rp.answer(), answerShown);
                    }
                    Thread.sleep(POLL_MILLIS);
                }
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
        }, "narrate-progress");
        t.setDaemon(true);
        return t;
    }

    private static int tail(String label, String text, int shown) {
        if (text == null) {
            return shown;
        }
        if (text.length() < shown) {
            shown = 0; // the answer was reset for a new attempt
            System.err.println();
            System.err.println("[" + label + " restarts]");
        }
        if (text.length() > shown) {
            System.err.print(text.substring(shown));
            System.err.flush();
        }
        return text.length();
    }

    private static void print(NarrativeResult r) {
        System.out.println("Narrative " + r.id() + " for run " + r.runId() + " (" + r.language() + "): " + r.status()
                + (r.model() == null ? "" : ", model " + r.model()) + ", " + r.attempts() + " attempt(s), " + r.toolCalls() + " tool call(s)");
        JsonNode usage = ExportFiles.mapper().readTree(r.usageJson());
        System.out.println("Cost (" + usage.path("source").asText() + "): input " + usage.path("input_tokens").asText("?") + " tokens, output "
                + usage.path("output_tokens").asText("?") + " tokens, credits " + usage.path("ai_credits").asText("?") + ", usd " + usage.path("usd").asText("?"));
        if (r.status() == NarrativeStatus.FAILED) {
            System.out.println("Error: " + r.error());
            if (r.rawText() != null && !r.rawText().isBlank()) {
                System.out.println("Last answer:");
                System.out.println(r.rawText());
            }
            return;
        }
        JsonNode verification = ExportFiles.mapper().readTree(r.verificationJson());
        if (r.status() == NarrativeStatus.UNVERIFIED) {
            System.out.println("Unverified numbers:");
            verification.path("unverified").forEach(u -> System.out.println("  - " + u.asText()));
        } else {
            System.out.println("Verified: " + verification.path("checked").asInt() + " number(s) checked against the facts");
        }
        System.out.println();
        System.out.println(ExportFiles.mapper().writeValueAsString(ExportFiles.mapper().readTree(r.narrativeJson())));
    }
}
```

`cli/CopilotCommand.java`:

```java
package com.workloadhub.forecast.cli;

import com.workloadhub.forecast.api.CopilotStatus;
import com.workloadhub.forecast.api.ForecastException;
import java.util.UUID;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "copilot", description = "Copilot access: token presence, runtime, sign-in and quota.", subcommands = CopilotCommand.Status.class)
public class CopilotCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.err.println("usage: copilot status --user <name or id> [--db file]");
        return 2;
    }

    @Command(name = "status", description = "Whether this user can narrate: token, runtime, sign-in and quota.")
    public static class Status implements Callable<Integer> {

        @Mixin DbOptions db;

        @Option(names = "--user", required = true, description = "User, name or id")
        String user;

        @Override
        public Integer call() throws Exception {
            try (Services s = Services.open(db.dataSource())) {
                UUID userId;
                try {
                    userId = TeamArg.resolveUser(s.jdbc(), s.dialect(), user);
                } catch (IllegalArgumentException e) {
                    System.err.println("error: " + e.getMessage());
                    return 2;
                }
                CopilotStatus st;
                try {
                    st = s.service().copilotStatus(userId);
                } catch (ForecastException e) {
                    System.err.println("error: " + e.code() + ": " + e.getMessage());
                    return e.code().equals("TOKEN_KEY_MISSING") ? 2 : 1;
                }
                System.out.println("user: " + st.userId());
                System.out.println("hasToken: " + st.hasToken());
                System.out.println("runtimeAvailable: " + st.runtimeAvailable());
                System.out.println("runtimePath: " + (st.runtimePath() == null ? "-" : st.runtimePath()));
                System.out.println("runtimeVersion: " + st.runtimeVersion());
                System.out.println("authenticated: " + (st.authenticated() == null ? "-" : st.authenticated()));
                System.out.println("login: " + (st.login() == null ? "-" : st.login()));
                System.out.println("quota: " + (st.quotaJson() == null ? "-" : st.quotaJson()));
                System.out.println("message: " + st.message());
                return 0;
            }
        }
    }
}
```

In `ForecastCli.Root`, add `NarrateCommand.class, CopilotCommand.class` to `subcommands`.

- [ ] **Step 4: Run the CLI tests and the whole gate**

Run: `cd server && mvn -B -q verify`
Expected: PASS, both modules, under three minutes without Docker.

- [ ] **Step 5: Documentation**

`server/README.md`: add `narrate` and `copilot status` rows to the command table, and a section before "Parity check":

```markdown
## Narrating with Copilot

Narration uses the requesting user's own GitHub Copilot seat through `copilot-sdk-java`. The SDK runs an
**in-process runtime** (`runtime.node`, from the `copilot-sdk-java-runtime` artifact with classifier
`linux-x64`, 44 MB on the classpath); on first use it is unpacked into `~/.copilot/runtime-cache/<version>/`
(91 MB, once per SDK version, nothing downloaded). To use an installed Copilot CLI as a subprocess instead, set
`whf.copilot.cli-path` (server) or `WHF_COPILOT_CLI_PATH` (CLI).

Tokens are stored encrypted on `users.github_token` with the key in `whf.token-key` (server) or
`WHF_TOKEN_KEY` (CLI): a base64 AES-256 key, `openssl rand -base64 32`. Accepted tokens: `gho_`, `ghu_`,
`github_pat_`; classic `ghp_` tokens are refused.

```bash
export WHF_TOKEN_KEY="$(openssl rand -base64 32)"   # keep it: the stored tokens are unreadable without it
export GITHUB_TOKEN="gho_..."                      # the user's own token
$CLI copilot status --db ~/whf/workloadhub.db --user "Sara Tazi"
$CLI narrate --db ~/whf/workloadhub.db --run <run id> --user "Sara Tazi" --lang fr
```

`narrate` stores the token for the user, narrates, streams the steps, the thinking and the answer to stderr, and
prints the stored result on stdout: status (`OK`, `UNVERIFIED` with the numbers it could not find in the facts,
`FAILED` with the reason and the last answer), model, attempts, cost, then the narrative JSON. Exit codes: 0 OK,
3 UNVERIFIED, 1 FAILED or error, 2 usage. Every narration is a row in `forecast_narratives`, whatever its
status, so a failed one keeps its cost.

No automated test talks to Copilot. The live check is manual: run the two commands above on a seeded database
with a real token, and read `copilot status` first (it starts the runtime with the token and reports the login
and the quota). Sessions never resume; each narration is one client and one session, closed at the end.
```

`docs/backlog.md`, under "Java migration", append:

```markdown
- Rulings of the Copilot narration plan (2026-09-10), design
  `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md` section 15: the SDK runtime is in process
  (JNA, `~/.copilot/runtime-cache`), `whf.copilot.cli-path` switches to a subprocess; the six product skills are
  embedded in the system message although the SDK supports skill directories; tools use `ToolDefinition.from`,
  not `@CopilotTool`; `forecast_narratives` was recreated by V2 with `status`, `raw_text`, `error`, `attempts`,
  `tool_calls`; `narrate` returns a FAILED result (with its cost) instead of throwing `NARRATIVE_INVALID`;
  `DELETE /users/{id}/github-token` added; the rebalancing fit table of the 2026-09-07 design is not built
  (`task_keys` are checked against the source's open tasks only); new property `whf.work-dir`.
- Copilot narration residuals: the narration runs on the caller's thread (the host schedules it); the quota
  snapshot keys are whatever the account reports; no live SDK test in CI (manual procedure in `server/README.md`).
```

`CLAUDE.md`, section "Where the Java migration stands": add plan 4 to the "Done" list
(`docs/superpowers/plans/2026-09-10-java-copilot-narration.md`: Copilot narration through the SDK, tools,
contract, verification, usage, REST controller, sample host, CLI `narrate` and `copilot status`) and change
"Next" to the migration plan only (spec section 14 steps 1, 4 and 5). Also change the layout line for
`server/` to name the CLI commands `init-db, import, export, seed, run, runs, teams, eval, narrate, copilot status`.

- [ ] **Step 6: Commit**

```bash
git add server/forecast-cli/src server/README.md docs/backlog.md CLAUDE.md
git commit -m "feat(cli): narrate and copilot status, with the live procedure documented

narrate stores the token for the user, streams the narration to stderr
and prints the stored result; copilot status reports token, runtime,
sign-in and quota. The README carries the manual live check, the backlog
the rulings, CLAUDE.md the new state of the migration."
```

---

## Closing notes for the executor and the reviewer

**Rulings this plan takes, to report to the owner at the end** (all already in the design's section 15, restated here so the reviewer checks the code against them):

1. In-process SDK runtime by default; `whf.copilot.cli-path` (server) and `WHF_COPILOT_CLI_PATH` or the system property `whf.copilot.cli-path` (CLI) switch to a subprocess.
2. Skills embedded in the REPLACE system message; `enableSkills`, config discovery, session store and custom instructions off; `availableTools` is `custom:*` only.
3. Tools through `ToolDefinition.from`; the permission handler approves `custom-tool` requests for the nine names and rejects everything else.
4. `forecast_narratives` recreated by V2; every narration stored, including FAILED ones with their cost; `narrate` throws only before a session exists (`RUN_NOT_FOUND`, `RUN_NOT_DONE`, `INVALID_REQUEST`, `TOKEN_MISSING`, `TOKEN_KEY_MISSING`, `COPILOT_UNAVAILABLE`, `TOKEN_REJECTED`).
5. `NARRATIVE_NOT_FOUND` (404) for a missing narrative row; every `*_NOT_FOUND` code is 404, `INVALID_REQUEST` 400, the rest 409.
6. Progress phases `NARRATING`, `NARRATED`, `NARRATION_FAILED`; tails capped at 16 000 characters.
7. No fit table; `task_keys` validated against the source member's open tasks.

**Order and independence:** Tasks 1 to 6 are independent of each other except that 3 needs 2 (the schema text) and 4 needs 3 (the records); 7 needs 1 to 6; 8 needs 7; 9 needs 1, 7 and 8; 10 and 11 need 9. A reviewer can reject any task on its own.

**What the migration plan takes from here:** the Python service's narrator is now fully replaced (session, tools, contract, verifier, usage, progress, CLI); the archive branch keeps it as the reference for wording. `CLAUDE.md`'s "no WSL" rule and the Python and desktop layout lines are the migration plan's to rewrite.
