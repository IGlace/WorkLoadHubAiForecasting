#!/usr/bin/env bash
# SessionStart hook: injects the using-superpowers skill into context at
# startup, clear and compact — the same bootstrap the upstream superpowers
# plugin performs, adapted for skills vendored under .claude/skills/.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="${CLAUDE_PROJECT_DIR:-$(cd "${SCRIPT_DIR}/../.." && pwd)}"
SKILL_FILE="${PROJECT_ROOT}/.claude/skills/using-superpowers/SKILL.md"

content=$(cat "$SKILL_FILE" 2>&1 || echo "Error reading using-superpowers skill at $SKILL_FILE")

note='NOTE FOR THIS PROJECT: the superpowers skills are installed as project skills under .claude/skills/. Wherever a skill refers to "superpowers:<name>", invoke the project skill "<name>" with the Skill tool (e.g. superpowers:brainstorming -> brainstorming).'

context="<EXTREMELY_IMPORTANT>
You have superpowers.

**Below is the full content of your 'using-superpowers' skill - your introduction to using skills. For all other skills, use the 'Skill' tool:**

${content}

${note}
</EXTREMELY_IMPORTANT>"

jq -n --arg ctx "$context" '{hookSpecificOutput:{hookEventName:"SessionStart",additionalContext:$ctx}}'
exit 0
