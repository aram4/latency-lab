package lab.harness;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Log-linear histogram (same idea as HdrHistogram, ~1.6% precision). Recording is O(1) and
 * allocation-free, or the measurement itself would distort the tail it's observing.
 *
 * Buckets: values 0..127 ns are exact; above that, each power-of-two range is split into
 * 64 linear sub-buckets. Reported values are the bucket's upper edge (conservative).
 */
public final class LatencyHistogram {
    private static final int EXACT = 128;
    private static final int SUB = 64;
    private static final int BUCKETS = EXACT + 57 * SUB;

    private final long[] counts = new long[BUCKETS];
    private long total;
    private long max;
    private long min = Long.MAX_VALUE;

    public void record(long nanos) {
        if (nanos < 0) nanos = 0;
        counts[index(nanos)]++;
        total++;
        if (nanos > max) max = nanos;
        if (nanos < min) min = nanos;
    }

    private static int index(long v) {
        if (v < EXACT) return (int) v;
        int msb = 63 - Long.numberOfLeadingZeros(v); // >= 7
        int k = msb - 6;                              // >= 1
        int sub = (int) (v >>> k) - SUB;              // 0..63
        return EXACT + (k - 1) * SUB + sub;
    }

    private static long upperEdge(int idx) {
        if (idx < EXACT) return idx;
        int k = (idx - EXACT) / SUB + 1;
        long s = (idx - EXACT) % SUB + SUB;
        return ((s + 1) << k) - 1;
    }

    public long percentile(double p) {
        if (total == 0) return 0;
        long target = Math.max(1, (long) Math.ceil(p / 100.0 * total));
        long cumulative = 0;
        for (int i = 0; i < BUCKETS; i++) {
            cumulative += counts[i];
            if (cumulative >= target) return Math.min(upperEdge(i), max);
        }
        return max;
    }

    public long count() { return total; }
    public long max() { return max; }
    public long min() { return total == 0 ? 0 : min; }

    /** CSV percentile distribution, for plotting. */
    public void writeDistribution(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(file))) {
            out.println("percentile,latency_ns");
            double[] ps = {0, 10, 25, 50, 75, 90, 95, 99, 99.5, 99.9, 99.95, 99.99, 99.995, 99.999, 100};
            for (double p : ps) {
                out.printf("%s,%d%n", p, p == 100 ? max : percentile(p));
            }
        }
    }
}
