package com.workloadhub.forecast.ai;

/** Where a narration reports what it is doing: coded steps, plus the thinking and answer text the tracker discards. */
public interface NarrationProgress {

    enum Step {
        STARTING, SESSION, ASKING, TOOL, TOOL_DONE, CHECKING
    }

    void step(Step step, String detail);

    void thinking(String text);

    void answer(String text);

    /** A new attempt writes a new answer; the rejected one is dropped, the thinking is kept. */
    void resetAnswer();

    static NarrationProgress none() {
        return new NarrationProgress() {
            @Override
            public void step(Step step, String detail) {
            }

            @Override
            public void thinking(String text) {
            }

            @Override
            public void answer(String text) {
            }

            @Override
            public void resetAnswer() {
            }
        };
    }
}
