package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;

class WorkStylePropertyTest {

    @Property(tries = 200)
    void everyDrawKeepsTheWeekTheSameSize(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        double[] w = WorkStyle.draw(new SeedRandom(seed)).weekdayWeights();
        double sum = 0;
        for (double v : w) {
            sum += v;
            assertTrue(v > 0.0, "a weekday weight is never zero or negative");
        }
        assertEquals(5.0, sum, 1e-9);
    }

    @Property(tries = 200)
    void disciplineIsAFractionOfWhatWasWorked(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        WorkStyle s = WorkStyle.draw(new SeedRandom(seed));
        assertTrue(s.discipline() > 0.0 && s.discipline() <= 1.0);
        assertTrue(s.logged(8.8) <= 8.8 + 1e-9);
    }

    @Property(tries = 200)
    void everyMemberCanRunLongAndNoneAlways(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        WorkStyle s = WorkStyle.draw(new SeedRandom(seed));
        assertTrue(s.overtimeChance() > 0.0, "overload must be reachable for every member");
        assertTrue(s.overtimeChance() < 1.0, "a member who always runs long is not a member, it is a constant");
        assertTrue(s.overtimeFactor() > 1.0);
    }
}
