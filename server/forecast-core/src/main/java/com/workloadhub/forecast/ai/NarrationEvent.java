package com.workloadhub.forecast.ai;

/** A session event reduced to what the narrator needs, so tests never build SDK types. */
public record NarrationEvent(Kind kind, String text, String id, String toolName, String model, UsageEvent usage) {

    public enum Kind {
        INTENT, THINKING_DELTA, THINKING_FULL, ANSWER_DELTA, MESSAGE, TOOL_START, TOOL_DONE, USAGE, ERROR
    }

    public static NarrationEvent intent(String text) {
        return new NarrationEvent(Kind.INTENT, text, null, null, null, null);
    }

    public static NarrationEvent thinkingDelta(String id, String text) {
        return new NarrationEvent(Kind.THINKING_DELTA, text, id, null, null, null);
    }

    public static NarrationEvent thinkingFull(String id, String text) {
        return new NarrationEvent(Kind.THINKING_FULL, text, id, null, null, null);
    }

    public static NarrationEvent answerDelta(String text) {
        return new NarrationEvent(Kind.ANSWER_DELTA, text, null, null, null, null);
    }

    public static NarrationEvent message(String text, String model) {
        return new NarrationEvent(Kind.MESSAGE, text, null, null, model, null);
    }

    public static NarrationEvent toolStart(String id, String name) {
        return new NarrationEvent(Kind.TOOL_START, null, id, name, null, null);
    }

    public static NarrationEvent toolDone(String id) {
        return new NarrationEvent(Kind.TOOL_DONE, null, id, null, null, null);
    }

    public static NarrationEvent usage(UsageEvent usage) {
        return new NarrationEvent(Kind.USAGE, null, null, null, usage.model(), usage);
    }

    public static NarrationEvent error(String text) {
        return new NarrationEvent(Kind.ERROR, text, null, null, null, null);
    }
}
