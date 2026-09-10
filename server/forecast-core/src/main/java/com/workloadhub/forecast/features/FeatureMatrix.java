package com.workloadhub.forecast.features;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** An immutable table: named columns, member-week keys, doubles with NaN for unknown, code books for the categorical columns. */
public final class FeatureMatrix {

    private final List<String> columns;
    private final Map<String, Integer> index;
    private final List<MemberWeek> keys;
    private final double[][] values;
    private final Map<String, List<String>> codebooks;

    private FeatureMatrix(List<String> columns, List<MemberWeek> keys, double[][] values, Map<String, List<String>> codebooks) {
        this.columns = List.copyOf(columns);
        this.index = new LinkedHashMap<>();
        for (int i = 0; i < this.columns.size(); i++) {
            this.index.put(this.columns.get(i), i);
        }
        this.keys = List.copyOf(keys);
        this.values = values;
        Map<String, List<String>> books = new LinkedHashMap<>();
        codebooks.forEach((k, v) -> books.put(k, List.copyOf(v)));
        this.codebooks = books;
    }

    public static FeatureMatrix of(List<String> columns, List<MemberWeek> keys, double[][] values, Map<String, List<String>> codebooks) {
        if (keys.size() != values.length) {
            throw new IllegalArgumentException("keys " + keys.size() + " rows " + values.length);
        }
        double[][] copy = new double[values.length][];
        for (int i = 0; i < values.length; i++) {
            if (values[i].length != columns.size()) {
                throw new IllegalArgumentException("row " + i + " has " + values[i].length + " values for " + columns.size() + " columns");
            }
            copy[i] = values[i].clone();
        }
        return new FeatureMatrix(columns, keys, copy, codebooks);
    }

    public List<String> columns() {
        return columns;
    }

    public int rowCount() {
        return keys.size();
    }

    public MemberWeek key(int i) {
        return keys.get(i);
    }

    public List<MemberWeek> keys() {
        return keys;
    }

    public Map<String, List<String>> codebooks() {
        return codebooks;
    }

    public int columnIndex(String column) {
        Integer i = index.get(column);
        if (i == null) {
            throw new IllegalArgumentException("unknown column " + column);
        }
        return i;
    }

    public double get(int row, String column) {
        return values[row][columnIndex(column)];
    }

    public double[] column(String column) {
        int c = columnIndex(column);
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i][c];
        }
        return out;
    }

    public double[] target(int h) {
        return column(Features.target(h));
    }

    public FeatureMatrix filter(Predicate<MemberWeek> keep) {
        List<MemberWeek> k = new ArrayList<>();
        List<double[]> v = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            if (keep.test(keys.get(i))) {
                k.add(keys.get(i));
                v.add(values[i]);
            }
        }
        return new FeatureMatrix(columns, k, v.toArray(double[][]::new), codebooks);
    }

    public FeatureMatrix rowsWithKnown(String column) {
        int c = columnIndex(column);
        List<MemberWeek> k = new ArrayList<>();
        List<double[]> v = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            if (!Double.isNaN(values[i][c])) {
                k.add(keys.get(i));
                v.add(values[i]);
            }
        }
        return new FeatureMatrix(columns, k, v.toArray(double[][]::new), codebooks);
    }

    public List<String> nonEmptyColumns(List<String> candidates) {
        List<String> out = new ArrayList<>();
        for (String c : candidates) {
            int ci = columnIndex(c);
            for (double[] row : values) {
                if (!Double.isNaN(row[ci])) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /** Row-major floats over the given columns, NaN where unknown: the layout XGBoost's DMatrix takes. */
    public float[] flatten(List<String> cols) {
        int[] ci = cols.stream().mapToInt(this::columnIndex).toArray();
        float[] out = new float[values.length * ci.length];
        int p = 0;
        for (double[] row : values) {
            for (int c : ci) {
                out[p++] = (float) row[c];
            }
        }
        return out;
    }

    public String decode(String column, double code) {
        List<String> book = codebooks.get(column);
        if (book == null || Double.isNaN(code)) {
            return null;
        }
        return book.get((int) code);
    }
}
