# Research notes: workload forecasting CLI on a personal Copilot license

Date: 2026-09-03. Gathered during the initial brainstorming session. Every claim
below has a source link; verify before relying on details that may have changed.

## 1. Using the user's own Copilot license from a local CLI

### GitHub Copilot CLI (standalone `copilot`)
- Programmatic mode: `copilot -p "<prompt>"` runs non-interactively; `-s` prints only
  the agent's response; `--agent=<name>`, `--model=<model>`, `--allow-tool=<tool>`,
  `--deny-tool=<tool>`, `--allow-all-tools`, `--add-dir=<dir>`, `--no-ask-user`,
  `--share=<path>`. Env: `COPILOT_MODEL`, `COPILOT_HOME` (default `~/.copilot`),
  auth token precedence `COPILOT_GITHUB_TOKEN` > `GH_TOKEN` > `GITHUB_TOKEN`.
  https://docs.github.com/en/copilot/reference/copilot-cli-reference/cli-programmatic-reference
  https://docs.github.com/en/copilot/how-tos/copilot-cli/automate-copilot-cli/run-cli-programmatically
- Custom agents: `.github/agents/*.agent.md` (project) or `~/.copilot/agents/` (user);
  `name`, `description`, optional `tools` allowlist; invoke with `copilot --agent <name> -p ...`.
  https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/create-custom-agents-for-cli
- Agent Skills: loaded from `.github/skills`, `.claude/skills`, `.agents/skills`
  (project) and `~/.copilot/skills`, `~/.agents/skills` (user). SKILL.md frontmatter:
  `name`, `description`, optional `license`, `allowed-tools`. `/skills list|info|add|reload`.
  https://docs.github.com/en/copilot/how-tos/copilot-cli/customize-copilot/add-skills
  https://github.blog/changelog/2025-12-18-github-copilot-now-supports-agent-skills/
- Platforms: Linux, macOS, Windows (PowerShell or WSL).
  https://docs.github.com/en/copilot/concepts/agents/about-copilot-cli
- Billing: every interactive or programmatic prompt consumes the user's premium
  requests / AI credits; Business/Enterprise admins control overage policy.
  https://docs.github.com/en/enterprise-cloud@latest/billing/concepts/product-billing/github-copilot-premium-requests
  https://github.blog/changelog/2025-08-22-premium-request-overage-policy-is-generally-available-for-copilot-business-and-enterprise/

### GitHub Copilot SDK
- Generally available, semver. Packages: `@github/copilot-sdk` (Node),
  `github-copilot-sdk` (PyPI), Go, .NET (`GitHub.Copilot.SDK`), Java, Rust.
- Auth reuses the `copilot` CLI login (user's Copilot seat) or `COPILOT_GITHUB_TOKEN` /
  `GH_TOKEN`; BYOK (OpenAI/Azure/Anthropic keys) also possible.
- Supports custom tools (`defineTool` in TS, `@define_tool` + Pydantic in Python),
  custom agents, skills, MCP servers, streaming, sessions. Node/Python/.NET packages
  bundle the CLI binary. Runtime: Node 20+ or Python 3.11+.
  https://github.com/github/copilot-sdk
  https://github.com/github/copilot-sdk/blob/main/docs/getting-started.md
  https://github.blog/news-insights/company-news/build-an-agent-into-any-app-with-the-github-copilot-sdk/

### Microsoft 365 Copilot (different product)
- M365 Copilot Chat API (part of the Work IQ APIs, GA June 2026) lets an app chat
  with M365 Copilot via Microsoft Graph `/copilot/` with delegated Entra ID auth.
  Requires an M365 Copilot add-on license per user. Text-only responses; no custom
  tools / function calling, no code interpreter, no long-running tasks; grounded on
  enterprise + web search by default; can attach OneDrive/SharePoint files as context.
  https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/api/ai-services/chat/overview
  https://learn.microsoft.com/en-us/microsoft-365/copilot/extensibility/copilot-apis-overview
  https://www.microsoft.com/en-us/microsoft-365/blog/2026/06/02/announcing-the-new-work-iq-apis/

## 2. LLMs and time-series forecasting
- Tan et al., NeurIPS 2024, "Are Language Models Actually Useful for Time Series
  Forecasting?": removing the LLM from LLM-based forecasters does not hurt accuracy.
  https://arxiv.org/abs/2406.16964
- "Bridging the Last Mile of Time Series Forecasting with LLM Agents" (2026): keep a
  statistical/foundation model as the numeric backbone; the LLM only applies bounded,
  auditable revisions using external context (holidays, known events).
  https://arxiv.org/html/2606.02497
- Purpose-built foundation models (Chronos, TimesFM, Moirai, Lag-Llama, TimeGPT) are
  the alternative to classical models, not chat LLMs.
  https://arxiv.org/html/2504.04011v1
- Commercial capacity tools (Float, Runn, Forecast.app, Tempo, Atlassian velocity) use
  classical capacity math; AI is layered on for narrative and estimation.
  https://www.float.com/resources/capacity-planning-software
  https://www.atlassian.com/agile/project-management/velocity-scrum

## 3. Classical methods for sparse weekly per-person task counts
- Intermittent demand: Croston, SBA, TSB, ADIDA. Always compare against seasonal naive.
  Prophet needs multiple seasonal cycles and struggles on sparse low-volume series.
  https://www.nixtla.io/docs/use_cases/forecasting_intermittent_demand
  https://www.datasciencewithmarco.com/blog/forecasting-intermittent-time-series-in-python
  https://otexts.com/fpp3/prophet.html
- Hierarchical reconciliation (team = sum of members; bottom-up or MinT).
  https://arxiv.org/pdf/2006.02043
- Evaluate with MASE under rolling-origin backtesting.
- Libraries: Python `statsforecast` is the lightest full-featured option; `darts` and
  `sktime` are heavy. Node/TS ecosystem is thin (Nostradamus.js, zodiac-ts, unmaintained).
  https://unit8co.github.io/darts/
  https://github.com/wdamron/Nostradamus.js

## 4. LLM + deterministic analytics pattern
- Deterministic tools compute numbers; the LLM calls tools, reads structured results,
  writes narrative; typed output schemas; LLM never invents figures.
  https://dev.to/anna_danilec/deterministic-guardrails-for-non-deterministic-agents-127b
  https://www.arthur.ai/blog/best-practices-for-building-agents-guardrails

## 5. Synthetic data
- Per-person Poisson / negative-binomial arrival processes with multiplicative
  seasonality, holiday and project-burst factors (Nike `timeseries-generator` pattern);
  Faker for entity metadata; SDV PAR if learning from a real sample later.
  https://github.com/Nike-Inc/timeseries-generator
  https://github.com/TimeSynth/TimeSynth

## 6. VoltAgent libraries
- `awesome-agent-skills` is a curated LIST of links; skills live in their own repos
  (many on officialskills.sh). No SKILL.md files are in the repo itself.
- `awesome-claude-code-subagents` contains 168 subagent definitions in
  `categories/01..10`, each a Markdown file with frontmatter (`name`, `description`,
  `tools`, `model`), installable by copying into `.claude/agents/`.
