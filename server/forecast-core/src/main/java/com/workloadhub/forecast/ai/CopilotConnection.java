package com.workloadhub.forecast.ai;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** A started client for one token. */
public interface CopilotConnection extends AutoCloseable {

    /** Whether Copilot accepts the token; a RuntimeException when the status cannot be read. */
    AuthStatus authStatus();

    /** Creates one streaming session; events arrive on the consumer from the SDK's threads. */
    NarrationSession createSession(SessionSpec spec, Consumer<NarrationEvent> events);

    /** The account's quota snapshots as JSON-shaped maps keyed by quota type, or empty when they cannot be read in time. */
    Optional<Map<String, Object>> quota(Duration timeout);

    @Override
    void close();
}
