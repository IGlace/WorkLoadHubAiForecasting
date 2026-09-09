package com.workloadhub.forecast.seed;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/** Every random draw of the generator goes through one seeded stream, so a seed fixes the output. */
public final class SeedRandom {

    private final Random random;

    public SeedRandom(long seed) {
        this.random = new Random(seed);
    }

    public UUID uuid() {
        long msb = random.nextLong();
        long lsb = random.nextLong();
        msb = (msb & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L; // version 4
        lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // IETF variant
        return new UUID(msb, lsb);
    }

    public double uniform(double a, double b) {
        return a + (b - a) * random.nextDouble();
    }

    /** Inclusive on both ends. */
    public int between(int a, int b) {
        return a + random.nextInt(b - a + 1);
    }

    public boolean chance(double p) {
        return random.nextDouble() < p;
    }

    public double gaussian() {
        return random.nextGaussian();
    }

    public double lognormal(double median, double sigma) {
        return median * Math.exp(sigma * random.nextGaussian());
    }

    /** Knuth's method below 30, a rounded normal above; never negative. */
    public int poisson(double lambda) {
        if (lambda <= 0) {
            return 0;
        }
        if (lambda < 30) {
            double l = Math.exp(-lambda);
            int k = 0;
            double p = 1.0;
            do {
                k++;
                p *= random.nextDouble();
            } while (p > l);
            return k - 1;
        }
        return (int) Math.max(0, Math.round(lambda + Math.sqrt(lambda) * random.nextGaussian()));
    }

    public <T> T pick(List<T> items, double[] weights) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        double x = random.nextDouble() * total;
        for (int i = 0; i < items.size(); i++) {
            x -= weights[i];
            if (x < 0) {
                return items.get(i);
            }
        }
        return items.get(items.size() - 1);
    }

    public <T> T pick(List<T> items) {
        return items.get(random.nextInt(items.size()));
    }

    /** A timestamp on the day, between fromHour (inclusive) and toHour (exclusive), to the second. */
    public LocalDateTime at(LocalDate day, int fromHour, int toHour) {
        int seconds = between(fromHour * 3600, toHour * 3600 - 1);
        return day.atStartOfDay().plusSeconds(seconds);
    }
}
