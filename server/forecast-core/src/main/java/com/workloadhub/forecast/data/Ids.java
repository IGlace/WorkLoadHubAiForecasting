package com.workloadhub.forecast.data;

import java.util.Comparator;
import java.util.UUID;

/**
 * Orders {@link UUID}s exactly as their canonical {@code toString()} would (the hyphens fall at the same
 * position in every canonical string, so lexicographic order over the string is the same as unsigned order
 * over the 128 bits, most-significant half first), without allocating a string per comparison.
 */
public final class Ids {

    public static final Comparator<UUID> UUID_ORDER = (a, b) -> {
        int msb = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return msb != 0 ? msb : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    };

    private Ids() {
    }
}
