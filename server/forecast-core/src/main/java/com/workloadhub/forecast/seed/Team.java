package com.workloadhub.forecast.seed;

import java.util.List;
import java.util.UUID;

/** A team row plus its membership; department teams have no parent and hold the users without a manager. */
public record Team(UUID id, String name, UUID managerId, UUID parentId, List<UUID> memberIds, boolean department, String deptCode) {
}
