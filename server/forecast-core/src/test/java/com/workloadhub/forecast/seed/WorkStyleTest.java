package com.workloadhub.forecast.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import org.junit.jupiter.api.Test;

class WorkStyleTest {

    @Test
    void theWeekdayWeightsAverageToOneSoTheWeekKeepsItsSize() {
        WorkStyle s = WorkStyle.draw(new SeedRandom(1));
        double sum = 0;
        for (double w : s.weekdayWeights()) {
            sum += w;
        }
        assertEquals(5.0, sum, 1e-9, "five weekdays, mean weight 1, so a full week is still five day-lengths");
    }

    @Test
    void twoMembersGetDifferentShapes() {
        WorkStyle a = WorkStyle.draw(new SeedRandom(1));
        WorkStyle b = WorkStyle.draw(new SeedRandom(2));
        assertNotEquals(a.weekdayWeights()[0], b.weekdayWeights()[0]);
    }

    @Test
    void aDrawIsDeterministicForTheSameSeed() {
        assertEquals(WorkStyle.draw(new SeedRandom(7)).discipline(), WorkStyle.draw(new SeedRandom(7)).discipline(), 0.0);
    }

    @Test
    void disciplineNeverInventsHours() {
        WorkStyle s = WorkStyle.draw(new SeedRandom(3));
        assertTrue(s.logged(10.0) <= 10.0 + 1e-9, "a member logs at most what they worked");
        assertTrue(s.logged(10.0) > 0.0);
    }

    @Test
    void aNormalDayIsTheDayLengthTimesItsWeekdayWeight() {
        // overtimeChance 0 means the day is exactly weight x dayHours.
        WorkStyle s = new WorkStyle(new double[] {1.2, 1.1, 1.0, 0.9, 0.8}, 1.0, 0.0, 1.0);
        assertEquals(8.8 * 1.2, s.hoursOn(DayOfWeek.MONDAY, 8.8, new SeedRandom(1)), 1e-9);
        assertEquals(8.8 * 0.8, s.hoursOn(DayOfWeek.FRIDAY, 8.8, new SeedRandom(1)), 1e-9);
    }

    @Test
    void aCertainOvertimeDayRunsPastTheDayLength() {
        WorkStyle s = new WorkStyle(new double[] {1, 1, 1, 1, 1}, 1.0, 1.0, 1.5);
        assertTrue(s.hoursOn(DayOfWeek.MONDAY, 8.8, new SeedRandom(1)) > 8.8);
    }
}
