# How a forecast run works — explanatory report

A plain-language report for team leaders and management, answering eight questions about
the Runs screens: the champion model, the end-to-end workflow, the `unverified` status,
what asking Copilot for the narrative means, the interval bar, the AI Summary, Rebalancing,
and the forecasting logic underneath.

| Path | What it is |
|---|---|
| `report.html` | The report source. Self-contained apart from `report.css` and `diagrams/`. |
| `report.css` | Print stylesheet (A4, no header/footer). |
| `diagrams/*.svg` | The ten figures, hand-authored SVG so the report renders with no toolchain. |
| `uml/` | The same six process diagrams as PlantUML and Mermaid source, for slides. See `uml/README.md`. |

## Rebuilding the PDF

The rendered PDF is deliberately not committed — it is a build artifact, and the pre-commit
hook blocks binaries. Regenerate it with Edge, which is already on every Windows machine:

```powershell
$edge = "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe"
$dir  = "$PWD\docs\reports\2026-09-05-how-a-forecast-run-works"
& $edge --headless=new --disable-gpu --no-pdf-header-footer `
  --print-to-pdf="$dir\How-a-forecast-run-works.pdf" "file:///$dir\report.html"
```

That produces 34 A4 pages, fully vector — the figures stay sharp at any zoom.

## Accuracy

Written against `service/src/whf/` as it stood on 2026-09-05: the ten pipeline stages, the
champion selection rule and its fallback, the two narrative outcomes, the six failure reasons
and the capacity arithmetic were all read out of the source rather than recalled. Section 12
lists the known limits of version 1. If the pipeline changes, this report and the UML sources
must be updated by hand.
