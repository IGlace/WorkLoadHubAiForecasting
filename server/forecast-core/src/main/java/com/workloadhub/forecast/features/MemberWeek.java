package com.workloadhub.forecast.features;

import com.workloadhub.forecast.data.Ids;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

/** The row key: one counted member in one Monday week. */
public record MemberWeek(UUID member, LocalDate week) implements Comparable<MemberWeek> {

    private static final Comparator<MemberWeek> ORDER =
            Comparator.comparing(MemberWeek::member, Ids.UUID_ORDER).thenComparing(MemberWeek::week);

    @Override
    public int compareTo(MemberWeek o) {
        return ORDER.compare(this, o);
    }
}
