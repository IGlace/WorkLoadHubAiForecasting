package com.workloadhub.forecastweb.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.time.api.Dates;
import org.junit.jupiter.api.Test;

class DemoClockTest {

    @Test
    void pinnedDateIsTodayAndTimeKeepsTicking() throws InterruptedException {
        DemoClock clock = new DemoClock(ZoneOffset.UTC, LocalDate.of(2026, 9, 6));
        assertEquals(LocalDate.of(2026, 9, 6), clock.today());
        Instant a = clock.instant();
        Thread.sleep(5);
        Instant b = clock.instant();
        assertTrue(b.isAfter(a), "the time of day advances, so the progress labels rotate");
        clock.pin(LocalDate.of(2026, 8, 24));
        assertEquals(LocalDate.of(2026, 8, 24), clock.today());
        clock.pin(null);
        assertNull(clock.pinned());
        assertTrue(Duration.between(Instant.now(), clock.instant()).abs().toSeconds() < 5, "released: the system clock");
    }

    @Property
    void anyPinnedDateReadsBackAsTodayInAnyZone(@ForAll("dates") LocalDate day, @ForAll @IntRange(min = -12, max = 12) int offsetHours) {
        ZoneId zone = ZoneOffset.ofHours(offsetHours);
        DemoClock clock = new DemoClock(zone, day);
        assertEquals(day, clock.today());
        assertEquals(day, LocalDate.now(clock.withZone(zone)));
    }

    @Provide
    Arbitrary<LocalDate> dates() {
        return Dates.dates().between(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 12, 31));
    }
}
