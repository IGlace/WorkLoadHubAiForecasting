package com.workloadhub.forecast.facts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/** Standardised k-means over a few pattern statistics, the labelling with the best silhouette wins. */
public final class Clustering {

    public static final int MIN_MEMBERS = 6;
    static final int RESTARTS = 10;
    static final int MAX_ITERATIONS = 100;

    private Clustering() {
    }

    public static Map<UUID, Integer> assign(List<MemberPattern> tableIn) {
        List<MemberPattern> table = tableIn.stream().sorted((a, b) -> a.memberId().toString().compareTo(b.memberId().toString())).toList();
        Map<UUID, Integer> out = new LinkedHashMap<>();
        int n = table.size();
        if (n < MIN_MEMBERS) {
            table.forEach(p -> out.put(p.memberId(), 0));
            return out;
        }
        double[][] x = standardise(features(table));
        int[] best = new int[n];
        double bestScore = -1.0;
        for (int k = 2; k <= Math.min(5, n - 1); k++) {
            int[] labels = kMeans(x, k, new Random(0));
            if (Arrays.stream(labels).distinct().count() < 2) {
                continue;
            }
            double score = silhouette(x, labels, k);
            if (score > bestScore) {
                bestScore = score;
                best = labels;
            }
        }
        Map<Integer, Integer> renumber = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int label = renumber.computeIfAbsent(best[i], k -> renumber.size());
            out.put(table.get(i).memberId(), label);
        }
        return out;
    }

    static double[][] features(List<MemberPattern> table) {
        double[][] x = new double[table.size()][];
        for (int i = 0; i < table.size(); i++) {
            MemberPattern p = table.get(i);
            x[i] = new double[] {p.hoursPerWeek13w(), p.trendHoursPerWeek(), z(p.shareManual()), z(p.shareSelfPicked()), z(p.shareProject()),
                    z(p.estimateRatioMedian()), z(p.cycleDaysMedian()), z(p.shareLate())};
        }
        return x;
    }

    private static double z(Double v) {
        return v == null ? 0.0 : v;
    }

    static double[][] standardise(double[][] x) {
        int n = x.length;
        int d = x[0].length;
        double[][] out = new double[n][d];
        for (int j = 0; j < d; j++) {
            double mean = 0;
            for (double[] row : x) {
                mean += row[j] / n;
            }
            double var = 0;
            for (double[] row : x) {
                var += (row[j] - mean) * (row[j] - mean) / n;
            }
            double std = Math.sqrt(var);
            for (int i = 0; i < n; i++) {
                out[i][j] = std == 0 ? 0.0 : (x[i][j] - mean) / std;
            }
        }
        return out;
    }

    static int[] kMeans(double[][] x, int k, Random rnd) {
        int[] bestLabels = null;
        double bestInertia = Double.POSITIVE_INFINITY;
        for (int restart = 0; restart < RESTARTS; restart++) {
            double[][] centres = seed(x, k, rnd);
            int[] labels = new int[x.length];
            for (int it = 0; it < MAX_ITERATIONS; it++) {
                boolean changed = false;
                for (int i = 0; i < x.length; i++) {
                    int nearest = nearest(x[i], centres);
                    if (nearest != labels[i]) {
                        labels[i] = nearest;
                        changed = true;
                    }
                }
                double[][] next = new double[k][x[0].length];
                int[] counts = new int[k];
                for (int i = 0; i < x.length; i++) {
                    counts[labels[i]]++;
                    for (int j = 0; j < x[i].length; j++) {
                        next[labels[i]][j] += x[i][j];
                    }
                }
                for (int c = 0; c < k; c++) {
                    if (counts[c] == 0) {
                        next[c] = centres[c];
                    } else {
                        for (int j = 0; j < next[c].length; j++) {
                            next[c][j] /= counts[c];
                        }
                    }
                }
                centres = next;
                if (!changed && it > 0) {
                    break;
                }
            }
            double inertia = 0;
            for (int i = 0; i < x.length; i++) {
                inertia += sq(x[i], centres[labels[i]]);
            }
            if (inertia < bestInertia) {
                bestInertia = inertia;
                bestLabels = labels.clone();
            }
        }
        return bestLabels;
    }

    /** k-means++ seeding. */
    private static double[][] seed(double[][] x, int k, Random rnd) {
        double[][] centres = new double[k][];
        centres[0] = x[rnd.nextInt(x.length)].clone();
        for (int c = 1; c < k; c++) {
            double[] d = new double[x.length];
            double total = 0;
            for (int i = 0; i < x.length; i++) {
                double min = Double.POSITIVE_INFINITY;
                for (int j = 0; j < c; j++) {
                    min = Math.min(min, sq(x[i], centres[j]));
                }
                d[i] = min;
                total += min;
            }
            double r = rnd.nextDouble() * total;
            int chosen = x.length - 1;
            double acc = 0;
            for (int i = 0; i < x.length; i++) {
                acc += d[i];
                if (acc >= r) {
                    chosen = i;
                    break;
                }
            }
            centres[c] = x[chosen].clone();
        }
        return centres;
    }

    private static int nearest(double[] p, double[][] centres) {
        int best = 0;
        double bestD = Double.POSITIVE_INFINITY;
        for (int c = 0; c < centres.length; c++) {
            double d = sq(p, centres[c]);
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        return best;
    }

    private static double sq(double[] a, double[] b) {
        double s = 0;
        for (int j = 0; j < a.length; j++) {
            s += (a[j] - b[j]) * (a[j] - b[j]);
        }
        return s;
    }

    static double silhouette(double[][] x, int[] labels, int k) {
        int n = x.length;
        double total = 0;
        for (int i = 0; i < n; i++) {
            double[] sums = new double[k];
            int[] counts = new int[k];
            for (int j = 0; j < n; j++) {
                if (j != i) {
                    sums[labels[j]] += Math.sqrt(sq(x[i], x[j]));
                    counts[labels[j]]++;
                }
            }
            int own = labels[i];
            double a = counts[own] == 0 ? 0.0 : sums[own] / counts[own];
            double b = Double.POSITIVE_INFINITY;
            for (int c = 0; c < k; c++) {
                if (c != own && counts[c] > 0) {
                    b = Math.min(b, sums[c] / counts[c]);
                }
            }
            total += counts[own] == 0 || b == Double.POSITIVE_INFINITY ? 0.0 : (b - a) / Math.max(a, b);
        }
        return total / n;
    }
}
