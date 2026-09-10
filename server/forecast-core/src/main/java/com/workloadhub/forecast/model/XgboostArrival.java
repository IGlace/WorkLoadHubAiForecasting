package com.workloadhub.forecast.model;

import com.workloadhub.forecast.features.FeatureMatrix;
import com.workloadhub.forecast.features.Features;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ml.dmlc.xgboost4j.java.Booster;
import ml.dmlc.xgboost4j.java.DMatrix;
import ml.dmlc.xgboost4j.java.XGBoost;
import ml.dmlc.xgboost4j.java.XGBoostError;

/** One Poisson booster per horizon over the feature columns, single-threaded and seeded. */
public final class XgboostArrival implements ArrivalModel, AutoCloseable {

    public static final String NAME = "xgboost";
    static final int ROUNDS = 300;
    private static volatile Boolean categoricalProbe;

    private final boolean categorical;
    private final Map<Integer, Booster> boosters = new HashMap<>();
    private final Map<Integer, List<String>> columns = new HashMap<>();

    public XgboostArrival() {
        this(categoricalSupported());
    }

    public XgboostArrival(boolean categorical) {
        this.categorical = categorical;
    }

    @Override
    public String name() {
        return NAME;
    }

    static Map<String, Object> params(boolean categorical) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("objective", "count:poisson");
        p.put("tree_method", "hist");
        p.put("max_bin", 255);
        p.put("eta", 0.05);
        p.put("max_leaves", 31);
        p.put("grow_policy", "lossguide");
        p.put("max_depth", 0);
        p.put("min_child_weight", 1);
        p.put("lambda", 1);
        p.put("max_delta_step", 0.7);
        p.put("seed", 0);
        p.put("nthread", 1);
        p.put("verbosity", 0);
        if (categorical) {
            p.put("max_cat_to_onehot", 4);
        }
        return p;
    }

    /** Whether this XGBoost build accepts categorical feature types on a dense DMatrix; probed once. */
    public static boolean categoricalSupported() {
        Boolean known = categoricalProbe;
        if (known != null) {
            return known;
        }
        boolean ok;
        try {
            DMatrix probe = new DMatrix(new float[] {0, 1, 1, 2, 0, 3, 1, 4}, 4, 2, Float.NaN);
            try {
                probe.setFeatureTypes(new String[] {"c", "q"});
                probe.setLabel(new float[] {1, 2, 1, 2});
                XGBoost.train(probe, params(true), 2, Map.of(), null, null).dispose();
                ok = true;
            } finally {
                probe.dispose();
            }
        } catch (XGBoostError | UnsatisfiedLinkError | RuntimeException e) {
            ok = false;
        }
        categoricalProbe = ok;
        return ok;
    }

    @Override
    public XgboostArrival fit(FeatureMatrix train, int[] horizons) {
        for (int h : horizons) {
            FeatureMatrix rows = train.rowsWithKnown(Features.target(h));
            if (rows.rowCount() == 0) {
                throw new ModelUnavailable("no training rows with a known target at horizon " + h);
            }
            List<String> cols = rows.nonEmptyColumns(Features.featureColumns(h));
            if (cols.isEmpty()) {
                throw new ModelUnavailable("no usable feature column at horizon " + h);
            }
            DMatrix dm = null;
            try {
                dm = matrix(rows, cols);
                double[] target = rows.target(h);
                float[] label = new float[target.length];
                for (int i = 0; i < label.length; i++) {
                    label[i] = (float) target[i];
                }
                dm.setLabel(label);
                Booster old = boosters.put(h, XGBoost.train(dm, params(categorical), ROUNDS, Map.of(), null, null));
                if (old != null) {
                    old.dispose();
                }
                columns.put(h, cols);
            } catch (XGBoostError e) {
                throw new ModelUnavailable("xgboost training failed: " + e.getMessage(), e);
            } catch (UnsatisfiedLinkError e) {
                throw new ModelUnavailable("xgboost native library unavailable: " + e.getMessage(), e);
            } finally {
                if (dm != null) {
                    dm.dispose();
                }
            }
        }
        return this;
    }

    @Override
    public double[] predict(FeatureMatrix rows, int horizon) {
        Booster b = boosters.get(horizon);
        if (b == null) {
            throw new IllegalStateException("no booster fitted for horizon " + horizon);
        }
        if (rows.rowCount() == 0) {
            return new double[0];
        }
        DMatrix dm = null;
        try {
            dm = matrix(rows, columns.get(horizon));
            float[][] raw = b.predict(dm);
            double[] out = new double[raw.length];
            for (int i = 0; i < raw.length; i++) {
                out[i] = Math.max(0.0, raw[i][0]);
            }
            return out;
        } catch (XGBoostError e) {
            throw new ModelUnavailable("xgboost prediction failed: " + e.getMessage(), e);
        } finally {
            if (dm != null) {
                dm.dispose();
            }
        }
    }

    private DMatrix matrix(FeatureMatrix rows, List<String> cols) throws XGBoostError {
        DMatrix dm = new DMatrix(rows.flatten(cols), rows.rowCount(), cols.size(), Float.NaN);
        if (categorical) {
            String[] types = new String[cols.size()];
            for (int i = 0; i < types.length; i++) {
                types[i] = Features.isCategorical(cols.get(i)) ? "c" : "q";
            }
            try {
                dm.setFeatureTypes(types);
            } catch (XGBoostError e) {
                dm.dispose();
                throw e;
            }
        }
        return dm;
    }

    public List<String> columnsUsed(int horizon) {
        return columns.getOrDefault(horizon, List.of());
    }

    @Override
    public void close() {
        boosters.values().forEach(Booster::dispose);
        boosters.clear();
    }
}
