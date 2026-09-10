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
| cli-developer | developer experience | the picocli commands of forecast-cli |
| data-scientist | data and AI | feature matrix, models, backtest |
| data-engineer | data and AI | schema mapping, seed generator, import and export |
| ai-engineer | data and AI | Copilot session, tools, contract and verification |
| prompt-engineer | data and AI | system prompt and product skills |
| test-automator | quality and security | JUnit, jqwik, CI |
| code-reviewer | quality and security | reviews before merge |
| architect-reviewer | quality and security | design and boundary reviews |
| security-auditor | quality and security | token storage, Copilot data flow |
| technical-writer | business and product | user guide, docs |

Not installed on purpose: debugger (the `systematic-debugging` skill covers it),
mcp-developer (no MCP server in version 1), build-engineer and dependency-manager
(scope too small), machine-learning-engineer (model serving at scale is not needed).
python-pro, typescript-pro, electron-pro and react-specialist left with the Python service and the desktop app (archive/python-desktop-v1).

To update, copy the same files again from the upstream `categories/` folder.
