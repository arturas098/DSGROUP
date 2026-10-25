package com.example.wifipresence;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure Java baseline-based RSSI anomaly experiment. Not a human detector. */
public final class SignalAnalyzer {
    public static final int CALIBRATION_SAMPLES = 35;
    private static final int WINDOW = 10;
    private final ArrayList<Integer> calibration = new ArrayList<>();
    private final ArrayDeque<Integer> recent = new ArrayDeque<>();
    private boolean calibrated;
    private boolean calibrating;
    private double baseline;
    private double baselineSd;
    private int distinctBaseline;
    private int consecutiveDisturbances;
    private int lastRssi = Integer.MIN_VALUE;
    private long lastChangeMs;
    private double score;
    private boolean disturbance;

    public void beginCalibration() {
        calibration.clear();
        recent.clear();
        calibrating = true;
        calibrated = false;
        consecutiveDisturbances = 0;
        score = 0;
        disturbance = false;
        lastRssi = Integer.MIN_VALUE;
        lastChangeMs = 0L;
    }

    public void sample(int rssi, long nowMs, boolean phoneMoved) {
        if (rssi >= 0 || rssi < -110) return;
        if (rssi != lastRssi) { lastChangeMs = nowMs; lastRssi = rssi; }
        if (calibrating) {
            calibration.add(rssi);
            if (calibration.size() >= CALIBRATION_SAMPLES) finishCalibration();
            return;
        }
        if (!calibrated) return;
        recent.addLast(rssi);
        if (recent.size() > WINDOW) recent.removeFirst();
        if (recent.size() < 6) return;
        double recentMean = mean(recent);
        double recentSd = sd(recent, recentMean);
        double diff = Math.abs(recentMean - baseline);
        double deviation = Math.max(3.5, 2.5 * baselineSd);
        double volatility = Math.max(3.0, 2.0 * baselineSd);
        // Scale into a display metric, not a probability or person-confidence score.
        score = Math.min(100, 100 * (0.55 * (diff / (2 * deviation))
                + 0.45 * (recentSd / (2 * volatility))));
        boolean anomaly = diff > deviation || recentSd > volatility;
        if (phoneMoved) consecutiveDisturbances = 0;
        else consecutiveDisturbances = anomaly ? consecutiveDisturbances + 1 : 0;
        disturbance = !phoneMoved && consecutiveDisturbances >= 3;
    }

    private void finishCalibration() {
        List<Integer> sorted = new ArrayList<>(calibration);
        Collections.sort(sorted);
        int n = sorted.size();
        baseline = n % 2 == 0 ? (sorted.get(n/2 - 1) + sorted.get(n/2)) / 2.0 : sorted.get(n/2);
        baselineSd = sd(calibration, mean(calibration));
        distinctBaseline = (int)sorted.stream().distinct().count();
        calibrating = false;
        calibrated = true;
        recent.clear();
    }

    private static double mean(Iterable<Integer> xs) {
        double sum = 0; int n = 0;
        for (int x : xs) { sum += x; n++; }
        return n == 0 ? 0 : sum/n;
    }
    private static double sd(Iterable<Integer> xs, double m) {
        double sum = 0; int n = 0;
        for (int x : xs) { double d = x-m; sum += d*d; n++; }
        return n < 2 ? 0 : Math.sqrt(sum/(n-1));
    }
    public boolean isCalibrating() { return calibrating; }
    public boolean isCalibrated() { return calibrated; }
    public int calibrationCount() { return calibration.size(); }
    public double baseline() { return baseline; }
    public double baselineSd() { return baselineSd; }
    public int baselineDistinctReadings() { return distinctBaseline; }
    public double score() { return score; }
    public boolean disturbance() { return disturbance; }
    public long msSinceChange(long nowMs) { return lastChangeMs == 0L ? -1 : nowMs - lastChangeMs; }
}
