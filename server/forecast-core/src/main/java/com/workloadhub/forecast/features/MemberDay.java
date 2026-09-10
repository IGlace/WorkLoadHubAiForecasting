package com.workloadhub.forecast.features;

import com.workloadhub.forecast.data.Ids;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.UUID;

/** One counted member on one day: the key of the per-day placements. */
public record MemberDay(UUID member, LocalDate day) implements Comparable<MemberDay> {

    private static final Comparator<MemberDay> ORDER = Comparator.comparing(MemberDay::member, Ids.UUID_ORDER).thenComparing(MemberDay::day);

    @Override
    public int compareTo(MemberDay o) {
        return ORDER.compare(this, o);
    }
}
