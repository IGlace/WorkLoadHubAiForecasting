import json
import re
from pathlib import Path

from whf.ai.prompt import (
    PRODUCT_SKILLS,
    SYSTEM_PROMPT,
    build_retry_prompt,
    build_user_prompt,
    skill_directories,
    skills_root,
)
from whf.ai.schema import Narrative

FACTS = {
    "run": {"id": 9, "as_of": "2026-09-03", "weeks": ["2026-09-07", "2026-09-14"], "language": "en"},
    "team": {"id": 2, "name": "Mobile Apps"},
    "members": [
        {"id": 4, "name": "Sara Tazi", "role": "team_leader"},
        {"id": 5, "name": "Omar Benali", "role": "member"},
    ],
    "model": {"champion": "gbm"},
}


def test_system_prompt_states_the_hard_rules() -> None:
    lower = SYSTEM_PROMPT.lower()
    assert "never invent" in lower and "tools" in lower and "json" in lower
    assert "one decimal" in lower


def test_user_prompt_names_run_members_weeks_and_schema() -> None:
    prompt = build_user_prompt(FACTS)
    assert "Mobile Apps" in prompt and "2026-09-07" in prompt and "2026-09-14" in prompt
    assert "member_id 4" in prompt and "member_id 5" in prompt
    assert "get_run_overview" in prompt
    schema = Narrative.model_json_schema()
    assert json.dumps(schema["required"]) in prompt or all(k in prompt for k in schema["required"])


def test_retry_prompt_lists_problems() -> None:
    text = build_retry_prompt(["member 5 is missing from members", "the answer is not valid JSON"])
    assert "member 5 is missing" in text and "only the JSON" in text


def test_skill_directories_resolve_from_package_path() -> None:
    """Every entry skill_directories() returns must exist on disk with a SKILL.md next to it.

    The Copilot SDK opens these paths directly (no importlib.resources indirection at call time), and
    that is exactly what a PyInstaller-frozen build depends on: skills_root() must resolve to a real,
    unpacked directory rather than something living only inside a wheel/zip.
    """
    dirs = skill_directories()
    assert dirs
    for d in dirs:
        path = Path(d)
        assert path.is_dir(), f"{d} does not exist on disk"
        assert (path / "SKILL.md").is_file(), f"{d} has no SKILL.md"


def test_skills_are_packaged_with_valid_frontmatter() -> None:
    root = skills_root()
    assert root.is_dir()
    dirs = skill_directories()
    assert [Path(d).name for d in dirs] == sorted(PRODUCT_SKILLS)
    for d in dirs:
        text = (Path(d) / "SKILL.md").read_text(encoding="utf-8")
        front = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
        assert front, d
        assert re.search(r"^name: " + re.escape(Path(d).name) + r"$", front.group(1), re.MULTILINE), d
        assert re.search(r"^description: .{20,}$", front.group(1), re.MULTILINE), d
        assert len(text) < 6000, f"{d} should stay short"
