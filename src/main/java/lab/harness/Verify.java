package lab.harness;

import lab.engine.Engine;
import lab.engine.FillSink;

import java.util.ArrayList;
import java.util.List;

/**
 * Correctness gate: hand-written scenarios with known expected fills, plus a differential
 * test where naive and fast replay the same random order flow and must produce identical
 * fill sequences (order-sensitive checksum).
 */
public final class Verify {

    record F(long maker, long taker, long price, long qty) {}

    public static void main(String[] args) {
        int failures = 0;
        for (String name : List.of("naive", "fast")) {
            failures += scenarios(name);
        }
        failures += differential();
        if (failures > 0) {
            System.out.println("\nFAILED: " + failures + " check(s)");
            System.exit(1);
        }
        System.out.println("\nALL CHECKS PASSED");
    }

    private static int scenarios(String name) {
        int fails = 0;

        // Time priority within a level, then price priority across levels, partial fill of last maker.
        {
            List<F> fills = new ArrayList<>();
            Engine e = small(name, fills);
            e.add(1, false, 101, 10);
            e.add(2, false, 100, 5);
            e.add(3, false, 100, 5);
            e.add(4, true, 101, 12); // takes #2 (5), #3 (5), then 2 of #1 @101
            fails += check(name, "price-time priority", fills,
                    List.of(new F(2, 4, 100, 5), new F(3, 4, 100, 5), new F(1, 4, 101, 2)));
        }
        // Cancel removes a resting order; later taker skips it.
        {
            List<F> fills = new ArrayList<>();
            Engine e = small(name, fills);
            e.add(1, true, 99, 10);
            e.add(2, true, 99, 10);
            e.cancel(1);
            e.cancel(1); // double cancel is a no-op
            e.ioc(3, false, 99, 4);
            fails += check(name, "cancel + ioc", fills, List.of(new F(2, 3, 99, 4)));
        }
        // IOC remainder must not rest; non-crossing limit rests and is hit later.
        {
            List<F> fills = new ArrayList<>();
            Engine e = small(name, fills);
            e.ioc(1, true, 100, 10);   // nothing to hit, discarded
            e.add(2, false, 100, 3);   // would have matched #1 had it rested
            e.add(3, true, 99, 5);     // doesn't cross, rests
            e.add(4, false, 99, 6);    // hits #3 for 5, rests 1 @99
            e.add(5, true, 100, 10);   // hits #4 remainder @99 first (better price), then #2 @100
            fails += check(name, "ioc discard + resting remainder", fills,
                    List.of(new F(3, 4, 99, 5), new F(4, 5, 99, 1), new F(2, 5, 100, 3)));
        }
        return fails;
    }

    private static int differential() {
        int fails = 0;
        for (long seed : new long[]{1, 2, 3, 42, 1337}) {
            Workload w = Workload.generate(300_000, seed);
            FillSink.Checksum a = replay("naive", w);
            FillSink.Checksum b = replay("fast", w);
            boolean ok = a.hash == b.hash && a.fills == b.fills && a.volume == b.volume;
            System.out.printf("[%s] differential seed=%-5d fills=%,d volume=%,d checksum=%x%n",
                    ok ? "PASS" : "FAIL", seed, a.fills, a.volume, a.hash);
            if (!ok) {
                System.out.printf("       naive fills=%d vol=%d hash=%x | fast fills=%d vol=%d hash=%x%n",
                        a.fills, a.volume, a.hash, b.fills, b.volume, b.hash);
                fails++;
            }
        }
        return fails;
    }

    private static FillSink.Checksum replay(String name, Workload w) {
        FillSink.Checksum sink = new FillSink.Checksum();
        Engine e = Bench.create(name, sink, w);
        for (int i = 0; i < w.size; i++) {
            switch (w.type[i]) {
                case Workload.ADD -> e.add(w.id[i], w.buy[i], w.price[i], w.qty[i]);
                case Workload.CANCEL -> e.cancel(w.id[i]);
                default -> e.ioc(w.id[i], w.buy[i], w.price[i], w.qty[i]);
            }
        }
        return sink;
    }

    private static Engine small(String name, List<F> fills) {
        FillSink sink = (m, t, p, q) -> fills.add(new F(m, t, p, q));
        return name.equals("naive")
                ? new lab.engine.naive.NaiveOrderBook(sink)
                : new lab.engine.fast.FastOrderBook(sink, 1_000, 64, 64);
    }

    private static int check(String engine, String scenario, List<F> actual, List<F> expected) {
        boolean ok = actual.equals(expected);
        System.out.printf("[%s] %-6s %s%n", ok ? "PASS" : "FAIL", engine, scenario);
        if (!ok) {
            System.out.println("       expected " + expected + "\n       actual   " + actual);
        }
        return ok ? 0 : 1;
    }
}
