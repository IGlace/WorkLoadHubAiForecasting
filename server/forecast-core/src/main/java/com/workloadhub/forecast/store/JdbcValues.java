package com.workloadhub.forecast.store;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** The one thing every store still normalises by hand: a timestamp bound at the precision PostgreSQL keeps. */
final class JdbcValues {

    private JdbcValues() {
    }

    /** PostgreSQL's timestamp holds microseconds; truncate before binding so what is read back equals what was written. */
    static LocalDateTime micros(LocalDateTime t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.MICROS);
    }
}
