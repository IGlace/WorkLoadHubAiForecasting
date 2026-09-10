# WorkloadHub AI Forecasting

A Java 21 module for the WorkloadHub Spring Boot server that forecasts each team member's work hours for
the next two weeks from the team's task history, compares them with capacity, and uses each user's own
GitHub Copilot seat to explain patterns, warn about overload and suggest rebalancing. Deterministic code
computes every number; the language model only writes the narrative, and every figure it writes is checked
against the facts.

- `server/`: the module (`forecast-core`, the host's dependency; `forecast-cli`, a command line for
  experiments). Build, test and use: [`server/README.md`](server/README.md).
- `docs/`: requirements, research, design documents, plans, evaluation results and the backlog. Start with
  `docs/superpowers/specs/2026-09-09-java-forecast-module-design.md` and
  `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md`.
- `CLAUDE.md`: how work is done in this repository.

The first version, a Windows desktop application with a Python service, is archived on the branch
`archive/python-desktop-v1` and receives no new features.

```bash
cd server && mvn -B verify      # the gate: about three minutes without Docker
bash scripts/check.sh           # the same gate plus the parity tool's test
```
