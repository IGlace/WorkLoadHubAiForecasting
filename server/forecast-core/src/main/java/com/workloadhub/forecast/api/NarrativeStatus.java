package com.workloadhub.forecast.api;

/** How a narration ended: every number verified, some numbers not found in the facts, or no usable narrative. */
public enum NarrativeStatus {
    OK,
    UNVERIFIED,
    FAILED
}
