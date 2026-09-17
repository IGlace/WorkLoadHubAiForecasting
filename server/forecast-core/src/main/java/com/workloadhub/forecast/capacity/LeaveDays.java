package com.workloadhub.forecast.capacity;

import com.workloadhub.forecast.Numbers;
import com.workloadhub.forecast.calendar.WorkingCalendar;
import com.workloadhub.forecast.data.rows.LeaveRow;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Absence hours per member and day, from leaves (design 2026-09-17, section 2.2): a leave covers its working
 * days only; its {@code absence_hours} total is dealt one full day at a time from the first day forward, the
 * last day taking what is left — or from the last day backward when only {@code begin_time} is set, so the
 * partial day is the first one. A null or non-positive total means the whole period. A day dealt zero hours
 * is not an absent day.
 */
public final class LeaveDays {

	private LeaveDays() {
	}

	public static Map<UUID, NavigableMap<LocalDate, Double>> expand(List<LeaveRow> leaves, WorkingCalendar cal, double fullDayHours) {
		Map<UUID, NavigableMap<LocalDate, Double>> out = new HashMap<>();
		for (LeaveRow l : leaves) {
			List<LocalDate> days = new ArrayList<>();
			for (LocalDate d = l.start(); !d.isAfter(l.end()); d = d.plusDays(1)) {
				if (cal.isWorkingDay(d)) {
					days.add(d);
				}
			}
			if (days.isEmpty()) {
				continue;
			}
			double[] hours = dealOut(days.size(), l.absenceHours(), fullDayHours, l.beginTime() != null && l.endTime() == null);
			NavigableMap<LocalDate, Double> byDay = out.computeIfAbsent(l.employeeId(), k -> new TreeMap<>());
			for (int i = 0; i < days.size(); i++) {
				if (hours[i] > 0) {
					byDay.merge(days.get(i), hours[i], Double::sum);
				}
			}
		}
		out.replaceAll((k, v) -> {
			v.replaceAll((d, h) -> Numbers.round2(h));
			return v;
		});
		return out;
	}

	/** Hours per working day, in day order. */
	static double[] dealOut(int n, Double total, double fullDay, boolean partialFirstDay) {
		double[] out = new double[n];
		if (total == null || total <= 0) {
			java.util.Arrays.fill(out, fullDay);
			return out;
		}
		double left = Math.min(total, n * fullDay);
		for (int k = 0; k < n; k++) {
			int i = partialFirstDay ? n - 1 - k : k;
			double h = Math.min(fullDay, left);
			out[i] = h;
			left -= h;
		}
		return out;
	}
}
