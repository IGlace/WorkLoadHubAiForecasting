package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class SeedRandomTest {

    @Property
    boolean sameSeedSameSequence(@ForAll @LongRange(min = 0, max = 1_000_000) long seed) {
        SeedRandom a = new SeedRandom(seed);
        SeedRandom b = new SeedRandom(seed);
        return a.uuid().equals(b.uuid()) && a.poisson(3.0) == b.poisson(3.0)
                && a.lognormal(10, 0.6) == b.lognormal(10, 0.6) && a.between(1, 9) == b.between(1, 9);
    }

    @Property
    boolean uuidsAreVersion4(@ForAll @LongRange(min = 0, max = 1_000_000) long seed) {
        var u = new SeedRandom(seed).uuid();
        return u.version() == 4 && u.variant() == 2;
    }

    @Property
    boolean poissonMeanIsLambda(@ForAll @DoubleRange(min = 0.2, max = 40) double lambda) {
        SeedRandom r = new SeedRandom(1);
        double sum = 0;
        for (int i = 0; i < 4000; i++) {
            sum += r.poisson(lambda);
        }
        double mean = sum / 4000;
        return Math.abs(mean - lambda) < 0.15 * lambda + 0.1;
    }

    @Test
    void lognormalMedianIsTheMedian() {
        SeedRandom r = new SeedRandom(2);
        int below = 0;
        for (int i = 0; i < 4000; i++) {
            if (r.lognormal(12, 0.6) < 12) {
                below++;
            }
        }
        assertTrue(below > 1800 && below < 2200, "below median: " + below);
    }

    @Test
    void pickFollowsWeights() {
        SeedRandom r = new SeedRandom(3);
        int a = 0;
        for (int i = 0; i < 2000; i++) {
            if (r.pick(List.of("a", "b"), new double[] {0.8, 0.2}).equals("a")) {
                a++;
            }
        }
        assertTrue(a > 1500 && a < 1700, "a picked " + a);
    }

    @Test
    void timestampsFallInsideTheWindow() {
        SeedRandom r = new SeedRandom(4);
        var t = r.at(LocalDate.of(2026, 3, 4), 9, 11);
        assertEquals(LocalDate.of(2026, 3, 4), t.toLocalDate());
        assertTrue(t.getHour() >= 9 && t.getHour() < 11);
    }
}
