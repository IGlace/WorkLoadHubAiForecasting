package com.workloadhub.forecast.data.rows;

import java.time.LocalDate;
import java.util.UUID;

/** One row of `team_capacity`: the team's own weekly capacity plan, independent of the per-member rule. */
public record TeamCapacityRow(UUID teamId, LocalDate weekStart, double totalCapacity, double allocated) {
}
