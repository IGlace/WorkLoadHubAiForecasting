package com.workloadhub.forecast.model;

/** A model cannot run here (missing native library, no training rows); the backtest records the reason. */
public class ModelUnavailable extends RuntimeException {

    public ModelUnavailable(String message) {
        super(message);
    }

    public ModelUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
