package com.workloadhub.forecast.run;

import com.workloadhub.forecast.backtest.Backtest;
import com.workloadhub.forecast.model.ArrivalModel;
import com.workloadhub.forecast.model.SeasonalNaive;
import com.workloadhub.forecast.model.XgboostArrival;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** The arrival models a run may use, by name, and the tournament a forced model restricts to. */
public final class ModelRegistry {

    public static final List<String> NAMES = List.of(SeasonalNaive.NAME, XgboostArrival.NAME);

    private ModelRegistry() {
    }

    public static boolean isKnown(String name) {
        return name != null && NAMES.contains(name);
    }

    public static ArrivalModel create(String name) {
        if (SeasonalNaive.NAME.equals(name)) {
            return new SeasonalNaive();
        }
        if (XgboostArrival.NAME.equals(name)) {
            return new XgboostArrival();
        }
        throw new IllegalArgumentException("unknown model " + name + "; known: " + NAMES);
    }

    /** The floor first, then every other model; a forced model restricts the tournament to the floor and itself. */
    public static Map<String, Supplier<ArrivalModel>> factories(String forcedModel) {
        Map<String, Supplier<ArrivalModel>> out = new LinkedHashMap<>();
        out.put(Backtest.FLOOR, SeasonalNaive::new);
        for (String name : NAMES) {
            if (!name.equals(Backtest.FLOOR) && (forcedModel == null || forcedModel.equals(name))) {
                out.put(name, () -> create(name));
            }
        }
        return out;
    }
}
