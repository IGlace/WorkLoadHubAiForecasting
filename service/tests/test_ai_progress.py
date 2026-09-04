"""The narrative POST blocks for a minute or more, so the app polls these steps while it waits."""

from whf.ai.progress import ProgressEvent, ProgressStore


class TestProgressEvent:
    def test_a_static_code_has_an_english_message_for_the_cli(self) -> None:
        assert ProgressEvent("starting").message == "starting Copilot"

    def test_a_tool_call_names_the_tool(self) -> None:
        assert ProgressEvent("tool", "team_overview").message == "tool team_overview"

    def test_an_attempt_is_numbered(self) -> None:
        assert ProgressEvent("asking", "2").message == "asking Copilot (attempt 2)"


class TestProgressStore:
    def test_a_run_with_no_narration_has_no_steps(self) -> None:
        assert ProgressStore().steps(1) == []

    def test_records_code_detail_and_a_timestamp_in_order(self) -> None:
        store = ProgressStore()
        store.begin(7)
        store.record(7, ProgressEvent("starting"))
        store.record(7, ProgressEvent("tool", "team_overview"))
        steps = store.steps(7)
        assert [(s["code"], s["detail"]) for s in steps] == [("starting", None), ("tool", "team_overview")]
        assert all(s["at"] for s in steps)

    def test_begin_clears_the_steps_of_an_earlier_narration_of_the_same_run(self) -> None:
        """Narrating a run twice must not show the first attempt's steps under the second."""
        store = ProgressStore()
        store.begin(7)
        store.record(7, ProgressEvent("starting"))
        store.begin(7)
        assert store.steps(7) == []

    def test_keeps_only_the_most_recent_steps_of_a_run(self) -> None:
        store = ProgressStore(keep_steps=3)
        store.begin(7)
        for name in ("a", "b", "c", "d"):
            store.record(7, ProgressEvent("tool", name))
        assert [s["detail"] for s in store.steps(7)] == ["b", "c", "d"]

    def test_forgets_the_oldest_runs(self) -> None:
        """The service can run for weeks; the store must not grow one step list per run forever."""
        store = ProgressStore(keep_runs=2)
        for run_id in (1, 2, 3):
            store.begin(run_id)
            store.record(run_id, ProgressEvent("starting"))
        assert store.steps(1) == []
        assert [s["code"] for s in store.steps(3)] == ["starting"]

    def test_recording_without_begin_still_works(self) -> None:
        store = ProgressStore()
        store.record(7, ProgressEvent("starting"))
        assert [s["code"] for s in store.steps(7)] == ["starting"]

    def test_steps_are_a_copy_so_a_caller_cannot_mutate_the_store(self) -> None:
        store = ProgressStore()
        store.record(7, ProgressEvent("starting"))
        store.steps(7).clear()
        assert len(store.steps(7)) == 1
