"""The frozen smoke test is the only thing that checks the packaged service before it ships, so its
two parsers — the ones that decide whether a run and a handshake are believable — are worth testing.

`smoke_frozen.py` lives beside the PyInstaller spec rather than inside the `whf` package (it must run
with the standard library alone, on a machine that has only the frozen folder), so load it by path.
"""

import importlib.util
from pathlib import Path
from types import ModuleType

import pytest

SCRIPT = Path(__file__).resolve().parents[2] / "installer" / "pyinstaller" / "smoke_frozen.py"


def _load() -> ModuleType:
    spec = importlib.util.spec_from_file_location("smoke_frozen", SCRIPT)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


smoke = _load()


class TestLastJsonObject:
    def test_reads_the_payload_of_a_clean_run(self) -> None:
        assert smoke._last_json_object('{"run_id": 7}\n') == {"run_id": 7}

    def test_ignores_a_stray_line_before_the_payload(self) -> None:
        """The reason this parser exists: a dependency's warning on stdout must not fail the smoke test."""
        stdout = 'UserWarning: something\n{"run_id": 7}\n'
        assert smoke._last_json_object(stdout) == {"run_id": 7}

    def test_ignores_a_stray_line_after_the_payload(self) -> None:
        assert smoke._last_json_object('{"run_id": 7}\nGoodbye\n') == {"run_id": 7}

    def test_takes_the_last_object_when_there_are_several(self) -> None:
        assert smoke._last_json_object('{"run_id": 1}\n{"run_id": 2}\n') == {"run_id": 2}

    def test_tolerates_indentation(self) -> None:
        assert smoke._last_json_object('  {"run_id": 7}  ') == {"run_id": 7}

    def test_rejects_output_with_no_object_line(self) -> None:
        with pytest.raises(SystemExit, match="printed no JSON object line"):
            smoke._last_json_object("nothing to see here\n")

    def test_rejects_a_line_that_is_not_valid_json(self) -> None:
        with pytest.raises(SystemExit, match="did not print valid JSON"):
            smoke._last_json_object('{"run_id": }\n')

    def test_ignores_a_json_array_line(self) -> None:
        """Only `{` lines are candidates, so a JSON array on stdout is a stray line like any other."""
        assert smoke._last_json_object('{"run_id": 7}\n[1, 2]\n') == {"run_id": 7}


class TestValidHandshake:
    def test_accepts_a_port_and_a_token(self) -> None:
        assert smoke._valid_handshake('{"port": 51234, "token": "abc"}') == {"port": 51234, "token": "abc"}

    def test_rejects_an_empty_line(self) -> None:
        """serve exiting before it prints anything leaves readline() returning ''."""
        assert smoke._valid_handshake("") is None

    def test_rejects_a_non_json_line(self) -> None:
        assert smoke._valid_handshake("Traceback (most recent call last):") is None

    def test_rejects_json_that_is_not_an_object(self) -> None:
        assert smoke._valid_handshake("[1, 2]") is None

    def test_rejects_a_missing_port(self) -> None:
        assert smoke._valid_handshake('{"token": "abc"}') is None

    def test_rejects_a_port_that_is_not_a_number(self) -> None:
        assert smoke._valid_handshake('{"port": "51234", "token": "abc"}') is None

    def test_rejects_a_missing_token(self) -> None:
        assert smoke._valid_handshake('{"port": 51234}') is None

    def test_rejects_a_token_that_is_not_a_string(self) -> None:
        assert smoke._valid_handshake('{"port": 51234, "token": 5}') is None
