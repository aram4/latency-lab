package lab.harness;

import java.util.SplittableRandom;

/**
 * Deterministic, pre-generated order flow stored in primitive arrays — generated up front
 * so the timed region measures the engine, not the random-number generator.
 */
public final class Workload {
    public static final byte ADD = 0;
    public static final byte CANCEL = 1;
    public static final byte IOC = 2;

    public static final int MAX_PRICE = 20_000;

    public final int size;
    public final byte[] type;
    public final long[] id;
    public final boolean[] buy;
    public final long[] price;
    public final long[] qty;
    public final int addCount;
    public final long maxOrderId;

    private Workload(int size) {
        this.size = size;
        this.type = new byte[size];
        this.id = new long[size];
        this.buy = new boolean[size];
        this.price = new long[size];
        this.qty = new long[size];
        this.addCount = 0;
        this.maxOrderId = 0;
    }

    private Workload(Workload w, int addCount, long maxOrderId) {
        this.size = w.size;
        this.type = w.type;
        this.id = w.id;
        this.buy = w.buy;
        this.price = w.price;
        this.qty = w.qty;
        this.addCount = addCount;
        this.maxOrderId = maxOrderId;
    }

    /**
     * Mix: ~55% passive-ish limit adds (a few cross), ~35% cancels of recent orders,
     * ~10% aggressive IOC orders. Mid price does a slow random walk so the book
     * touches many levels instead of a handful of hot ones.
     */
    public static Workload generate(int size, long seed) {
        Workload w = new Workload(size);
        SplittableRandom rnd = new SplittableRandom(seed);
        int recentCap = 4096;
        long[] recent = new long[recentCap];
        int recentCount = 0;
        int recentCursor = 0;
        long nextId = 1;
        int adds = 0;
        long mid = MAX_PRICE / 2;

        for (int i = 0; i < size; i++) {
            if (i % 100 == 0) {
                mid = Math.max(1_000, Math.min(MAX_PRICE - 1_000, mid + rnd.nextInt(-2, 3)));
            }
            int r = rnd.nextInt(100);
            if (r < 55 || recentCount == 0) {
                boolean isBuy = rnd.nextBoolean();
                int offset = rnd.nextInt(-3, 40); // negative offset = marketable
                w.type[i] = ADD;
                w.id[i] = nextId;
                w.buy[i] = isBuy;
                w.price[i] = isBuy ? mid - offset : mid + offset;
                w.qty[i] = rnd.nextInt(1, 101);
                recent[recentCursor] = nextId;
                recentCursor = (recentCursor + 1) % recentCap;
                recentCount = Math.min(recentCount + 1, recentCap);
                nextId++;
                adds++;
            } else if (r < 90) {
                w.type[i] = CANCEL;
                w.id[i] = recent[rnd.nextInt(recentCount)];
            } else {
                boolean isBuy = rnd.nextBoolean();
                w.type[i] = IOC;
                w.id[i] = nextId++;
                w.buy[i] = isBuy;
                w.price[i] = isBuy ? mid + rnd.nextInt(0, 10) : mid - rnd.nextInt(0, 10);
                w.qty[i] = rnd.nextInt(1, 301);
            }
        }
        return new Workload(w, adds, nextId);
    }
}
