package com.workloadhub.forecast.seed;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The kinds of work in the directory, with the parameters of the design's section 4.2. */
public enum WorkFamily {
    // order = precedence: the first family with a matching whole word wins
    CALIBRATION(Set.of("calibration"), 0.55, 0.20, 0.20, 0.05, 12, 0.35, 42),
    DATA(Set.of("data", "ai", "dai"), 0.55, 0.20, 0.20, 0.05, 8, 0.55, 36),
    SUPPORT(Set.of("hr", "admin", "administration", "administrator", "finance", "purchasing", "it", "facility",
            "specialist", "generalist", "officer"), 0.50, 0.20, 0.30, 0.00, 4, 0.70, 26),
    SYSTEMS(Set.of("system", "systems"), 0.60, 0.15, 0.20, 0.05, 16, 0.30, 39),
    ELECTRONICS(Set.of("electric", "electronics", "ee", "software", "sw", "functions"), 0.50, 0.30, 0.15, 0.05, 10, 0.40, 39),
    VALIDATION(Set.of("validation", "verification", "homologation", "fleet", "test"), 0.45, 0.35, 0.15, 0.05, 12, 0.30, 39),
    DESIGN(Set.of("design", "simulation", "cfd", "dmu"), 0.60, 0.10, 0.25, 0.05, 20, 0.35, 39),
    COORDINATION(Set.of("project", "coordination", "leader", "manager", "workshop", "center"), 0.40, 0.10, 0.45, 0.05, 6, 0.60, 21),
    UNKNOWN(Set.of(), 0.60, 0.15, 0.20, 0.05, 16, 0.30, 39);

    public final Set<String> keywords;
    public final double delivery;
    public final double defect;
    public final double support;
    public final double container;
    public final double medianEstimate;
    public final double selfPicked;
    public final double weeklyHours;

    WorkFamily(Set<String> keywords, double delivery, double defect, double support, double container,
            double medianEstimate, double selfPicked, double weeklyHours) {
        this.keywords = keywords;
        this.delivery = delivery;
        this.defect = defect;
        this.support = support;
        this.container = container;
        this.medianEstimate = medianEstimate;
        this.selfPicked = selfPicked;
        this.weeklyHours = weeklyHours;
    }

    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    public static WorkFamily classify(String jobTitle) {
        if (jobTitle == null || jobTitle.isBlank()) {
            return UNKNOWN;
        }
        List<String> words = List.of(jobTitle.toLowerCase(Locale.ROOT).split("[^a-z0-9&]+"));
        for (WorkFamily f : values()) {
            for (String w : words) {
                if (f.keywords.contains(w)) {
                    return f;
                }
            }
        }
        return UNKNOWN;
    }
}
