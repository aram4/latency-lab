package lab.engine.fast;

import lab.engine.Engine;
import lab.engine.FillSink;

import java.util.Arrays;

/**
 * Same semantics as {@code NaiveOrderBook}, written zero-allocation: price levels are flat
 * arrays indexed by price tick, orders live in a pre-allocated struct-of-arrays pool linked
 * into intrusive per-level lists (O(1) cancel), and fills go through a primitive callback.
 */
public final class FastOrderBook implements Engine {

    private static final int NONE = -1;

    private final FillSink sink;
    private final int maxPrice;

    private final int[] levelHead;
    private final int[] levelTail;
    private int bestBid = NONE;
    private int bestAsk;

    private final long[] slotId;
    private final long[] slotQty;
    private final int[] slotPrice;
    private final boolean[] slotBuy;
    private final int[] slotNext;
    private final int[] slotPrev;
    private int freeHead;

    private final int[] idToSlot;

    public FastOrderBook(FillSink sink, int maxPrice, int poolCapacity, int maxOrderId) {
        this.sink = sink;
        this.maxPrice = maxPrice;
        this.levelHead = new int[maxPrice + 1];
        this.levelTail = new int[maxPrice + 1];
        Arrays.fill(levelHead, NONE);
        Arrays.fill(levelTail, NONE);
        this.bestAsk = maxPrice + 1;

        this.slotId = new long[poolCapacity];
        this.slotQty = new long[poolCapacity];
        this.slotPrice = new int[poolCapacity];
        this.slotBuy = new boolean[poolCapacity];
        this.slotNext = new int[poolCapacity];
        this.slotPrev = new int[poolCapacity];
        for (int i = 0; i < poolCapacity - 1; i++) {
            slotNext[i] = i + 1;
        }
        slotNext[poolCapacity - 1] = NONE;
        this.freeHead = 0;

        this.idToSlot = new int[maxOrderId + 1];
        Arrays.fill(idToSlot, NONE);
    }

    @Override
    public void add(long orderId, boolean buy, long price, long qty) {
        int p = (int) price;
        long remaining = buy ? matchBuy(orderId, p, qty) : matchSell(orderId, p, qty);
        if (remaining > 0) {
            rest(orderId, buy, p, remaining);
        }
    }

    @Override
    public void ioc(long orderId, boolean buy, long price, long qty) {
        int p = (int) price;
        if (buy) {
            matchBuy(orderId, p, qty);
        } else {
            matchSell(orderId, p, qty);
        }
    }

    @Override
    public void cancel(long orderId) {
        if (orderId < 0 || orderId >= idToSlot.length) {
            return;
        }
        int slot = idToSlot[(int) orderId];
        if (slot == NONE) {
            return;
        }
        int p = slotPrice[slot];
        boolean buy = slotBuy[slot];
        unlink(slot, p);
        release(slot, orderId);
        if (levelHead[p] == NONE) {
            if (buy && p == bestBid) {
                bestBid = scanDownFrom(p - 1);
            } else if (!buy && p == bestAsk) {
                bestAsk = scanUpFrom(p + 1);
            }
        }
    }

    private long matchBuy(long takerId, int limit, long qty) {
        long remaining = qty;
        while (remaining > 0 && bestAsk <= limit) {
            int p = bestAsk;
            remaining = consumeLevel(p, takerId, remaining);
            if (levelHead[p] == NONE) {
                bestAsk = scanUpFrom(p + 1);
            }
        }
        return remaining;
    }

    private long matchSell(long takerId, int limit, long qty) {
        long remaining = qty;
        while (remaining > 0 && bestBid != NONE && bestBid >= limit) {
            int p = bestBid;
            remaining = consumeLevel(p, takerId, remaining);
            if (levelHead[p] == NONE) {
                bestBid = scanDownFrom(p - 1);
            }
        }
        return remaining;
    }

    /** Fills against one price level in time priority. Returns taker's remaining qty. */
    private long consumeLevel(int p, long takerId, long remaining) {
        int slot = levelHead[p];
        while (remaining > 0 && slot != NONE) {
            long makerQty = slotQty[slot];
            long fillQty = Math.min(remaining, makerQty);
            long makerId = slotId[slot];
            sink.onFill(makerId, takerId, p, fillQty);
            remaining -= fillQty;
            int next = slotNext[slot];
            if (fillQty == makerQty) {
                unlink(slot, p);
                release(slot, makerId);
            } else {
                slotQty[slot] = makerQty - fillQty;
            }
            slot = next;
        }
        return remaining;
    }

    private void rest(long orderId, boolean buy, int p, long qty) {
        int slot = freeHead;
        if (slot == NONE) {
            throw new IllegalStateException("order pool exhausted; size it for peak resting orders");
        }
        freeHead = slotNext[slot];

        slotId[slot] = orderId;
        slotQty[slot] = qty;
        slotPrice[slot] = p;
        slotBuy[slot] = buy;

        int tail = levelTail[p];
        slotPrev[slot] = tail;
        slotNext[slot] = NONE;
        if (tail == NONE) {
            levelHead[p] = slot;
        } else {
            slotNext[tail] = slot;
        }
        levelTail[p] = slot;
        idToSlot[(int) orderId] = slot;

        if (buy) {
            if (p > bestBid) bestBid = p;
        } else {
            if (p < bestAsk) bestAsk = p;
        }
    }

    private void unlink(int slot, int p) {
        int prev = slotPrev[slot];
        int next = slotNext[slot];
        if (prev == NONE) levelHead[p] = next; else slotNext[prev] = next;
        if (next == NONE) levelTail[p] = prev; else slotPrev[next] = prev;
    }

    private void release(int slot, long orderId) {
        idToSlot[(int) orderId] = NONE;
        slotNext[slot] = freeHead;
        freeHead = slot;
    }

    private int scanDownFrom(int p) {
        while (p >= 0 && levelHead[p] == NONE) p--;
        return p < 0 ? NONE : p;
    }

    private int scanUpFrom(int p) {
        while (p <= maxPrice && levelHead[p] == NONE) p++;
        return p; // maxPrice + 1 means "no asks"
    }
}
