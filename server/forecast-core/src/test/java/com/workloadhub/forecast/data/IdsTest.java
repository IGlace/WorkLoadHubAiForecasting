package com.workloadhub.forecast.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;

class IdsTest {

    @Property(tries = 200)
    void ordersExactlyLikeTheCanonicalString(@ForAll long msbA, @ForAll long lsbA, @ForAll long msbB, @ForAll long lsbB) {
        UUID a = new UUID(msbA, lsbA);
        UUID b = new UUID(msbB, lsbB);
        int expected = Integer.signum(a.toString().compareTo(b.toString()));
        int actual = Integer.signum(Ids.UUID_ORDER.compare(a, b));
        assertEquals(expected, actual, () -> a + " vs " + b);
    }
}
