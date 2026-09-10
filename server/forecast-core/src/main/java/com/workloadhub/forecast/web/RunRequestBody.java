package com.workloadhub.forecast.web;

import java.time.LocalDate;
import java.util.UUID;

/** The body of POST /runs. {@code asOf} is here only to be refused with a clear message: a run always starts from today. */
public record RunRequestBody(UUID teamId, UUID requestedBy, LocalDate asOf, String forcedModel, Boolean plannedWork) {
}
