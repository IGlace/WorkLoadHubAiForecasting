# Project skills

Skills in this directory are discovered automatically by Claude Code (and other
harnesses that read `.claude/skills/`). Each subdirectory holds one skill with a
`SKILL.md` entry point.

## Superpowers (vendored)

The following skills are vendored verbatim from
[obra/superpowers](https://github.com/obra/superpowers), MIT licensed
(see `SUPERPOWERS-LICENSE`):

| Skill | Purpose |
|-------|---------|
| using-superpowers | Bootstrap: how and when to invoke skills (injected at session start by `.claude/hooks/superpowers-session-start.sh`) |
| brainstorming | Refine a rough idea into a design before writing code |
| writing-plans | Turn a design into a step-by-step implementation plan |
| executing-plans | Execute a plan in batches with checkpoints |
| subagent-driven-development | Execute a plan by dispatching one subagent per task with review |
| dispatching-parallel-agents | Run independent investigations in parallel subagents |
| test-driven-development | Red / green / refactor discipline |
| systematic-debugging | Root-cause-first debugging process |
| verification-before-completion | Prove a change works before claiming it is done |
| requesting-code-review | Ask for a review of completed work |
| receiving-code-review | Evaluate review feedback before acting on it |
| using-git-worktrees | Isolate feature work in a git worktree |
| finishing-a-development-branch | Merge / PR / cleanup decision at the end of a branch |
| writing-skills | Author and test new skills |

Vendored from: superpowers v6.3.0 (commit b36e082), `main` branch.

Note: the upstream plugin namespaces its skills as `superpowers:<name>`. Here
they are plain project skills, so `superpowers:brainstorming` is invoked as
`brainstorming`.

### Updating

```bash
git clone --depth 1 https://github.com/obra/superpowers.git /tmp/superpowers
rm -rf .claude/skills/{brainstorming,dispatching-parallel-agents,executing-plans,finishing-a-development-branch,receiving-code-review,requesting-code-review,subagent-driven-development,systematic-debugging,test-driven-development,using-git-worktrees,using-superpowers,verification-before-completion,writing-plans,writing-skills}
cp -R /tmp/superpowers/skills/* .claude/skills/
cp /tmp/superpowers/LICENSE .claude/skills/SUPERPOWERS-LICENSE
```

Then update the version line above.

## External skills (vendored)

| Skill | Source | License | Used for |
|-------|--------|---------|----------|
| copilot-sdk | [microsoft/skills](https://github.com/microsoft/skills) `.github/skills/copilot-sdk`, commit `c6dec4a` | MIT | Copilot SDK sessions, custom tools, agents, skills |
| pydantic-models-py | [microsoft/skills](https://github.com/microsoft/skills) `.github/plugins/azure-sdk-python/skills/pydantic-models-py`, commit `c6dec4a` | MIT | API and data schemas in the service |
| modern-python | [trailofbits/skills](https://github.com/trailofbits/skills) `plugins/modern-python`, commit `d3323ce` | CC BY-SA 4.0 | uv, ruff, ty, pytest project setup |
| property-based-testing | [trailofbits/skills](https://github.com/trailofbits/skills) `plugins/property-based-testing`, commit `d3323ce` | CC BY-SA 4.0 | Hypothesis tests for capacity, placement and generator invariants |

Each directory carries its upstream LICENSE file. These were selected from the
[VoltAgent/awesome-agent-skills](https://github.com/VoltAgent/awesome-agent-skills)
index, which is a list of links rather than a collection of files.

## Product skills (to be written)

Skills prefixed `whf-` are shipped inside the product for the Copilot agent and
are also read by Claude Code during development: `whf-domain`,
`whf-pattern-discovery`, `whf-forecast-interpretation`, `whf-rebalancing-advice`,
`whf-report-style`. See the design spec, section 6.
