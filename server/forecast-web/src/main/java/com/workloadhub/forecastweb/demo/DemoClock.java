package com.workloadhub.forecastweb.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Demo only. A run starts from "today" by the module's {@code Clock} bean; this clock follows the system date
 * until an admin pins it from the UI, and keeps the system's time of day either way (the progress labels rotate
 * on it). Pin an earlier date, move forward, run again, and the accuracy page has weekdays that were forecast
 * before they arrived. Production deletes this bean and keeps the module's default.
 */
public final class DemoClock extends Clock {

    private final ZoneId zone;
    private volatile LocalDate pinned;

    public DemoClock(ZoneId zone, LocalDate pinned) {
        this.zone = zone;
        this.pinned = pinned;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new DemoClock(zone, pinned);
    }

    @Override
    public Instant instant() {
        LocalDate day = pinned;
        if (day == null) {
            return Instant.now();
        }
        return LocalDateTime.of(day, LocalTime.now(zone)).atZone(zone).toInstant();
    }

    public LocalDate today() {
        return LocalDate.now(this);
    }

    /** The pinned date, or null when the clock follows the system date. */
    public LocalDate pinned() {
        return pinned;
    }

    /** Pins the date, or releases it with null. */
    public void pin(LocalDate day) {
        this.pinned = day;
    }
}
