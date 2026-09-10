package com.workloadhub.forecast.ai;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/** One session: send a prompt, wait for the answer, read what it cost, close. */
public interface NarrationSession extends AutoCloseable {

    /** The final assistant message's content, or null when the turn ended without one (the deltas then hold the answer). */
    String ask(String prompt, Duration timeout) throws TimeoutException;

    /** The session's billed usage, or empty when the RPC is unavailable or too slow. Read it before closing. */
    Optional<UsageMetrics> usage(Duration timeout);

    @Override
    void close();
}
