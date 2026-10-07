package lab.harness;

import lab.engine.Engine;
import lab.engine.FillSink;
import lab.engine.fast.FastOrderBook;
import lab.engine.naive.NaiveOrderBook;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * Latency benchmark harness: closed-loop ("service time", vulnerable to coordinated omission)
 * vs. open-loop ("response time", measured from the intended send time).
 *
 * Usage: Bench [--engine naive|fast|both] [--mode closed|open] [--rate ops/s]
 *              [--events N] [--warmup N] [--seed N] [--label text]
 */
public final class Bench {

    public static void main(String[] args) throws Exception {
        String engineArg = arg(args, "--engine", "both");
        String mode = arg(args, "--mode", "closed");
        long rate = Long.parseLong(arg(args, "--rate", "200000"));
        int events = Integer.parseInt(arg(args, "--events", "2000000"));
        int warmup = Integer.parseInt(arg(args, "--warmup", "500000"));
        long seed = Long.parseLong(arg(args, "--seed", "42"));
        String label = arg(args, "--label", "");

        printEnvironment();
        if (mode.equals("hiccup")) {
            hiccup(Integer.parseInt(arg(args, "--seconds", "10")));
            return;
        }
        System.out.printf("mode=%s events=%,d warmup=%,d seed=%d%s%n%n",
                mode, events, warmup, seed, mode.equals("open") ? " rate=" + String.format("%,d", rate) + "/s" : "");

        List<String> engines = engineArg.equals("both") ? List.of("naive", "fast") : List.of(engineArg);
        printHeader();
        for (String e : engines) {
            run(e, mode, rate, events, warmup, seed, label);
        }
    }

    private static void run(String engineName, String mode, long rate, int events, int warmup,
                            long seed, String label) throws Exception {
        Workload w = Workload.generate(warmup + events, seed);
        FillSink.Checksum sink = new FillSink.Checksum();
        Engine engine = create(engineName, sink, w);

        // Warm-up: same code path as the measured run so the JIT compiles what we measure.
        closedLoop(engine, w, 0, warmup, new LatencyHistogram());

        System.gc();
        Thread.sleep(200);

        long gcCount0 = gcCount(), gcTime0 = gcTimeMs();
        long alloc0 = allocatedBytes();
        long t0 = System.nanoTime();

        LatencyHistogram hist = new LatencyHistogram();
        LatencyHistogram service = null;
        if (mode.equals("open")) {
            service = new LatencyHistogram();
            openLoop(engine, w, warmup, warmup + events, rate, hist, service);
        } else {
            closedLoop(engine, w, warmup, warmup + events, hist);
        }

        long elapsed = System.nanoTime() - t0;
        long allocPerOp = (allocatedBytes() - alloc0) / events;
        long gcs = gcCount() - gcCount0;
        long gcMs = gcTimeMs() - gcTime0;
        double throughput = events / (elapsed / 1e9);

        printRow(engineName + (service != null ? " (response)" : ""), hist, throughput, allocPerOp, gcs, gcMs);
        if (service != null) {
            printRow(engineName + " (service)", service, throughput, allocPerOp, gcs, gcMs);
        }

        String tag = engineName + "-" + mode + (label.isEmpty() ? "" : "-" + label);
        hist.writeDistribution(Path.of("results", tag + ".csv"));
        appendSummary(tag, hist, throughput, allocPerOp, gcs, gcMs, sink.hash);
    }

    static void closedLoop(Engine engine, Workload w, int from, int to, LatencyHistogram hist) {
        for (int i = from; i < to; i++) {
            long start = System.nanoTime();
            dispatch(engine, w, i);
            hist.record(System.nanoTime() - start);
        }
    }

    static void openLoop(Engine engine, Workload w, int from, int to, long ratePerSec,
                         LatencyHistogram response, LatencyHistogram service) {
        long intervalNs = 1_000_000_000L / ratePerSec;
        long base = System.nanoTime() + 1_000_000;
        for (int i = from; i < to; i++) {
            long intended = base + (long) (i - from) * intervalNs;
            long now;
            while ((now = System.nanoTime()) < intended) {
                Thread.onSpinWait(); // busy-spin: burns a core, never sleeps (sleeping costs ~50us+)
            }
            dispatch(engine, w, i);
            long end = System.nanoTime();
            response.record(end - intended); // includes time spent queued behind earlier stalls
            service.record(end - now);       // what a naive closed-loop harness would report
        }
    }

    private static void dispatch(Engine engine, Workload w, int i) {
        switch (w.type[i]) {
            case Workload.ADD -> engine.add(w.id[i], w.buy[i], w.price[i], w.qty[i]);
            case Workload.CANCEL -> engine.cancel(w.id[i]);
            default -> engine.ioc(w.id[i], w.buy[i], w.price[i], w.qty[i]);
        }
    }

    static Engine create(String name, FillSink sink, Workload w) {
        return switch (name) {
            case "naive" -> new NaiveOrderBook(sink);
            case "fast" -> new FastOrderBook(sink, Workload.MAX_PRICE, w.addCount + 1, (int) w.maxOrderId);
            default -> throw new IllegalArgumentException("unknown engine: " + name);
        };
    }

    private static void printHeader() {
        System.out.printf("%-18s %8s %8s %8s %9s %9s %10s %8s %11s %9s %6s %7s%n",
                "engine", "min", "p50", "p90", "p99", "p99.9", "p99.99", "max(us)",
                "ops/s", "alloc B/op", "GCs", "GC ms");
        System.out.println("-".repeat(124));
    }

    private static void printRow(String name, LatencyHistogram h, double tput, long allocPerOp, long gcs, long gcMs) {
        System.out.printf("%-18s %8s %8s %8s %9s %9s %10s %8.1f %,11.0f %9d %6d %7d%n",
                name, ns(h.min()), ns(h.percentile(50)), ns(h.percentile(90)), ns(h.percentile(99)),
                ns(h.percentile(99.9)), ns(h.percentile(99.99)), h.max() / 1000.0,
                tput, allocPerOp, gcs, gcMs);
    }

    private static String ns(long v) {
        return v < 10_000 ? v + "ns" : String.format("%.1fus", v / 1000.0);
    }

    private static void appendSummary(String tag, LatencyHistogram h, double tput, long alloc,
                                      long gcs, long gcMs, long checksum) throws Exception {
        Path file = Path.of("results", "summary.csv");
        Files.createDirectories(file.getParent());
        if (!Files.exists(file)) {
            Files.writeString(file, "timestamp,run,p50_ns,p99_ns,p999_ns,p9999_ns,max_ns,ops_per_s,alloc_b_per_op,gcs,gc_ms,jvm_opts,fill_checksum\n");
        }
        String jvmOpts = String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments())
                .replaceAll("-Djavax\\S*|-Dhttps?\\.\\S*|-Djdk\\.http\\S*", "").trim().replace(',', ';');
        String line = String.format("%s,%s,%d,%d,%d,%d,%d,%.0f,%d,%d,%d,\"%s\",%d%n",
                Instant.now(), tag, h.percentile(50), h.percentile(99), h.percentile(99.9),
                h.percentile(99.99), h.max(), tput, alloc, gcs, gcMs, jvmOpts, checksum);
        Files.writeString(file, line, StandardOpenOption.APPEND);
    }

    private static void printEnvironment() {
        LatencyHistogram clock = new LatencyHistogram();
        for (int i = 0; i < 1_000_000; i++) {
            long a = System.nanoTime();
            clock.record(System.nanoTime() - a);
        }
        String gcs = String.join("+", ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).toList());
        System.out.printf("java %s | %d cpus | GC: %s | heap max %d MB%n",
                System.getProperty("java.version"), Runtime.getRuntime().availableProcessors(), gcs,
                Runtime.getRuntime().maxMemory() / (1024 * 1024));
        System.out.printf("System.nanoTime() cost: p50=%dns p99=%dns  (the floor of anything we can measure)%n",
                clock.percentile(50), clock.percentile(99));
    }

    /**
     * jHiccup-style probe: spin reading the clock and record every gap. No engine, no
     * allocation, so any large gap is the platform stealing the CPU — the floor no engine
     * optimisation can beat.
     */
    static void hiccup(int seconds) {
        LatencyHistogram gaps = new LatencyHistogram();
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        long last = System.nanoTime();
        long over10us = 0, over100us = 0, over1ms = 0;
        while (last < end) {
            long now = System.nanoTime();
            long gap = now - last;
            gaps.record(gap);
            if (gap > 10_000) over10us++;
            if (gap > 100_000) over100us++;
            if (gap > 1_000_000) over1ms++;
            last = now;
        }
        System.out.printf("%nplatform hiccups over %ds of pure spinning (%,d samples):%n", seconds, gaps.count());
        System.out.printf("  p50=%s p99.99=%s p99.999=%s max=%.1fus%n",
                ns(gaps.percentile(50)), ns(gaps.percentile(99.99)), ns(gaps.percentile(99.999)), gaps.max() / 1000.0);
        System.out.printf("  stalls >10us: %d | >100us: %d | >1ms: %d%n", over10us, over100us, over1ms);
    }

    private static long gcCount() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, gc.getCollectionCount());
        return n;
    }

    private static long gcTimeMs() {
        long n = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) n += Math.max(0, gc.getCollectionTime());
        return n;
    }

    private static long allocatedBytes() {
        var bean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        return bean.getCurrentThreadAllocatedBytes();
    }

    private static String arg(String[] args, String key, String def) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(key)) return args[i + 1];
        }
        return def;
    }
}
