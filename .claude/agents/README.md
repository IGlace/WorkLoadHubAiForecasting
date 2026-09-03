# Project subagents

Subagent definitions discovered automatically by Claude Code. Each file is one
subagent with frontmatter (`name`, `description`, `tools`, `model`). Invoke them
through the Agent tool or by name.

All files below are vendored verbatim from
[VoltAgent/awesome-claude-code-subagents](https://github.com/VoltAgent/awesome-claude-code-subagents)
(MIT, see `VOLTAGENT-LICENSE`), commit `009544a`. Only the subagents this project
needs were copied.

| Subagent | Category | Used for |
|----------|----------|----------|
| python-pro | language specialists | forecast service, CLI, models |
| typescript-pro | language specialists | Electron app code |
| electron-pro | core development | main process, tray, notifications, packaging |
| react-specialist | language specialists | renderer screens |
| cli-developer | developer experience | `whf` command design |
| data-scientist | data and AI | feature engineering, models, backtesting |
| data-engineer | data and AI | data model, generator, adapters |
| ai-engineer | data and AI | Copilot session, tools, output validation |
| prompt-engineer | data and AI | agent system prompt and product skills |
| test-automator | quality and security | pytest, Vitest, CI |
| code-reviewer | quality and security | reviews before merge |
| architect-reviewer | quality and security | design and boundary reviews |
| security-auditor | quality and security | local API token, data at rest, Copilot data flow |
| technical-writer | business and product | user guide, docs |

Not installed on purpose: debugger (the `systematic-debugging` skill covers it),
mcp-developer (no MCP server in version 1), build-engineer and dependency-manager
(scope too small), machine-learning-engineer (model serving at scale is not needed).

To update, copy the same files again from the upstream `categories/` folder.
