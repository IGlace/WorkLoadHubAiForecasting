package com.workloadhub.forecast.model;

import com.workloadhub.forecast.features.FeatureMatrix;

/** A model of weekly fresh arrival hours per member, one prediction per row for a horizon. */
public interface ArrivalModel {

    String name();

    ArrivalModel fit(FeatureMatrix train, int[] horizons);

    /** Hours per row, never negative. */
    double[] predict(FeatureMatrix rows, int horizon);
}
