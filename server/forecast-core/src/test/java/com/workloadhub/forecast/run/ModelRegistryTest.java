package com.workloadhub.forecast.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.model.XgboostArrival;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModelRegistryTest {

    @Test
    void floorFirstThenXgboostByDefault() {
        assertEquals(List.of(Backtest.FLOOR, XgboostArrival.NAME), List.copyOf(ModelRegistry.factories(null).keySet()));
        assertEquals(List.of(Backtest.FLOOR, XgboostArrival.NAME), List.copyOf(ModelRegistry.factories(XgboostArrival.NAME).keySet()));
        assertEquals(List.of(Backtest.FLOOR), List.copyOf(ModelRegistry.factories(Backtest.FLOOR).keySet()));
        assertTrue(ModelRegistry.isKnown("xgboost"));
        assertFalse(ModelRegistry.isKnown("gbm"));
        assertEquals("xgboost", ModelRegistry.create("xgboost").name());
        assertThrows(IllegalArgumentException.class, () -> ModelRegistry.create("gbm"));
    }
}
