package com.workloadhub.forecast.data.rows;

import java.util.UUID;

/** One row of `teams`. */
public record TeamRow(UUID id, String name, UUID managerId, UUID parentId) {
}
