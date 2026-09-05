# UML sources

Six diagrams, each written twice: once in **PlantUML** (`.puml`) and once in
**Mermaid** (`.mmd`). Both describe the same thing; pick whichever your team
already renders.

| File | UML kind | What it shows |
|---|---|---|
| `01-architecture` | component / deployment | Where each piece runs, and the single arrow that leaves the machine |
| `02-activity-forecast` | activity, 2 swimlanes | Click "Run forecast" → the ten deterministic stages → numbers on screen |
| `03-activity-narrative` | activity, 3 swimlanes | Asking Copilot for the words, and where `unverified` comes from |
| `04-sequence` | sequence | The same run as messages between the six participants, with `opt` and `loop` fragments |
| `05-state-machine` | state machine | Every value `run.status` and `run.ai_status` can hold, and the moves between them |
| `06-result-payload` | class | What a finished run actually contains — the shape behind every screen |

The rendered figures used in the report live in `../diagrams/*.svg`. Those are
hand-drawn SVG, not generated from these sources, so that the report renders
with no toolchain at all. If you change a `.puml` or `.mmd`, the matching
`../diagrams/*.svg` does not update itself.

## Rendering

**Nothing to install — Mermaid in the browser**

Open <https://mermaid.live>, paste the contents of any `.mmd` file, export SVG or PNG.

**PlantUML online**

Open <https://www.plantuml.com/plantuml/uml/> and paste the contents of any
`.puml` file. (This uploads the diagram text to a public server — use the local
option below if that is not acceptable for your organisation.)

**Locally, PlantUML** — needs Java:

```powershell
# once
winget install --id Oracle.JavaRuntimeEnvironment
# then, from this folder
java -jar plantuml.jar -tsvg *.puml
```

**Locally, Mermaid** — needs Node:

```powershell
npm install -g @mermaid-js/mermaid-cli
mmdc -i 04-sequence.mmd -o 04-sequence.svg
```

**In an IDE**

- VS Code: extensions *PlantUML* (jebbs) and *Markdown Preview Mermaid Support*.
- JetBrains: the bundled *Mermaid* plugin, or *PlantUML Integration*.
- GitHub and GitLab render Mermaid directly inside fenced ` ```mermaid ` blocks.

## Accuracy

These diagrams were written against the source, not from memory. The facts they
encode — the ten stages, the champion rule, the two `ai_status` outcomes, the
six failure reasons, the field names in the payload — match `service/src/whf/`
as of 2026-09-05. If the pipeline changes, these files must be updated by hand.
