"""The narrative POST blocks for a minute or more, so the app polls these steps while it waits."""

from whf.ai.progress import ProgressEvent, ProgressStore


class TestProgressEvent:
    def test_a_static_code_has_an_english_message_for_the_cli(self) -> None:
        assert ProgressEvent("starting").message == "starting Copilot"

    def test_a_tool_call_names_the_tool(self) -> None:
        assert ProgressEvent("tool", "team_overview").message == "tool team_overview"

    def test_an_attempt_is_numbered(self) -> None:
        assert ProgressEvent("asking", "2").message == "asking Copilot (attempt 2)"

    def test_a_finished_tool_call_names_the_tool(self) -> None:
        assert ProgressEvent("tool_done", "team_overview").message == "tool team_overview done"

    def test_a_finished_tool_call_of_an_unknown_tool_still_reads(self) -> None:
        assert ProgressEvent("tool_done").message == "tool done"


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

    def test_record_appends_into_the_deque_begin_already_created(self, monkeypatch) -> None:
        """`record` must not treat the empty deque `begin` just made as missing and re-create it.

        `begin` followed by `record` must append into the very deque `begin` allocated: no second
        call into `_fresh`, which would make `begin`'s allocation pointless and, if `_fresh` ever did
        more than allocate, would silently drop steps.
        """
        store = ProgressStore()
        store.begin(7)

        def _fail(run_id: int) -> None:
            raise AssertionError("record must not re-enter _fresh after begin")

        monkeypatch.setattr(store, "_fresh", _fail)
        store.record(7, ProgressEvent("starting"))
        assert [s["code"] for s in store.steps(7)] == ["starting"]

    def test_recording_without_begin_still_works(self) -> None:
        store = ProgressStore()
        store.record(7, ProgressEvent("starting"))
        assert [s["code"] for s in store.steps(7)] == ["starting"]

    def test_steps_are_a_copy_so_a_caller_cannot_mutate_the_store(self) -> None:
        store = ProgressStore()
        store.record(7, ProgressEvent("starting"))
        store.steps(7).clear()
        assert len(store.steps(7)) == 1


class TestLiveText:
    """Beside the coded steps, the store keeps the tail of what Copilot is thinking and writing."""

    def test_an_unknown_run_has_no_live_text(self) -> None:
        assert ProgressStore().live(1) == {"thinking": "", "answer": ""}

    def test_appends_thinking_and_answer_separately(self) -> None:
        store = ProgressStore()
        store.begin(7)
        store.append(7, "thinking", "reading ")
        store.append(7, "thinking", "the facts")
        store.append(7, "answer", '{"run_summary"')
        assert store.live(7) == {"thinking": "reading the facts", "answer": '{"run_summary"'}

    def test_appending_without_begin_still_works(self) -> None:
        store = ProgressStore()
        store.append(7, "thinking", "hello")
        assert store.live(7)["thinking"] == "hello"

    def test_keeps_only_the_last_characters_of_a_long_narration(self) -> None:
        """Copilot can think for minutes; the store must not grow without bound."""
        store = ProgressStore(keep_chars=5)
        store.append(7, "answer", "abcd")
        store.append(7, "answer", "efgh")
        assert store.live(7)["answer"] == "defgh"

    def test_a_single_chunk_longer_than_the_bound_is_cut_to_its_tail(self) -> None:
        store = ProgressStore(keep_chars=3)
        store.append(7, "thinking", "abcdef")
        assert store.live(7)["thinking"] == "def"

    def test_reset_answer_starts_a_new_answer_and_keeps_the_thinking(self) -> None:
        """A retry attempt writes a second answer; the reasoning that led to it is still worth showing."""
        store = ProgressStore()
        store.append(7, "thinking", "reading the facts")
        store.append(7, "answer", "not JSON at all")
        store.reset_answer(7)
        assert store.live(7) == {"thinking": "reading the facts", "answer": ""}

    def test_reset_answer_of_an_unknown_run_does_nothing(self) -> None:
        """The app polls before the first step; an unknown run must not be created here."""
        store = ProgressStore()
        store.reset_answer(7)
        assert store.live(7) == {"thinking": "", "answer": ""}

    def test_begin_clears_the_live_text_of_an_earlier_narration(self) -> None:
        store = ProgressStore()
        store.append(7, "thinking", "first narration")
        store.append(7, "answer", "first answer")
        store.begin(7)
        assert store.live(7) == {"thinking": "", "answer": ""}

    def test_live_text_and_steps_share_the_bound_on_runs(self) -> None:
        store = ProgressStore(keep_runs=2)
        for run_id in (1, 2, 3):
            store.begin(run_id)
            store.append(run_id, "thinking", f"run {run_id}")
        assert store.live(1) == {"thinking": "", "answer": ""}
        assert store.live(3)["thinking"] == "run 3"


class TestTheNarratorReportsEvents:
    def test_a_narration_reports_coded_steps_not_prose(self) -> None:
        """The desktop app has to phrase each step in the user's language, so it needs codes."""
        from ai_fakes import FakeNarrator

        seen: list[ProgressEvent] = []
        FakeNarrator().narrate_sync({}, seen.append)
        assert [e.code for e in seen] == ["asking"]
