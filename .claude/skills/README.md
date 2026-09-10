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
| property-based-testing | [trailofbits/skills](https://github.com/trailofbits/skills) `plugins/property-based-testing`, commit `d3323ce` | CC BY-SA 4.0 | jqwik properties for the forecast's arithmetic invariants |

Each directory carries its upstream LICENSE file. These were selected from the
[VoltAgent/awesome-agent-skills](https://github.com/VoltAgent/awesome-agent-skills)
index, which is a list of links rather than a collection of files.

## Product skills

Skills prefixed `whf-` live in `server/forecast-core/src/main/resources/skills/` (one source of truth).
The Java narrator embeds them in the Copilot session's system message (`Prompts`); they are shipped
inside `forecast-core`. See `docs/superpowers/specs/2026-09-10-java-copilot-narration-design.md`, section 4.2.
