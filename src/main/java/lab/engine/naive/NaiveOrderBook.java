package lab.engine.naive;

import lab.engine.Engine;
import lab.engine.FillSink;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * The idiomatic baseline: TreeMap of price levels, ArrayDeque per level, HashMap for id
 * lookup, an Order object per order and a Fill record + ArrayList per call. Correct, and
 * the allocation on every call is where the GC tail (see README) comes from.
 */
public final class NaiveOrderBook implements Engine {

    record Fill(long makerId, long takerId, long price, long qty) {}

    static final class Order {
        final long id;
        final boolean buy;
        final long price;
        long remaining;

        Order(long id, boolean buy, long price, long qty) {
            this.id = id;
            this.buy = buy;
            this.price = price;
            this.remaining = qty;
        }
    }

    private final NavigableMap<Long, ArrayDeque<Order>> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, ArrayDeque<Order>> asks = new TreeMap<>();
    private final Map<Long, Order> byId = new HashMap<>();
    private final FillSink sink;

    public NaiveOrderBook(FillSink sink) {
        this.sink = sink;
    }

    @Override
    public void add(long orderId, boolean buy, long price, long qty) {
        Order order = new Order(orderId, buy, price, qty);
        List<Fill> fills = match(order);
        if (order.remaining > 0) {
            NavigableMap<Long, ArrayDeque<Order>> side = buy ? bids : asks;
            side.computeIfAbsent(price, p -> new ArrayDeque<>()).addLast(order);
            byId.put(orderId, order);
        }
        publish(fills);
    }

    @Override
    public void ioc(long orderId, boolean buy, long price, long qty) {
        Order order = new Order(orderId, buy, price, qty);
        publish(match(order));
    }

    @Override
    public void cancel(long orderId) {
        Order order = byId.remove(orderId);
        if (order == null) {
            return;
        }
        NavigableMap<Long, ArrayDeque<Order>> side = order.buy ? bids : asks;
        ArrayDeque<Order> level = side.get(order.price);
        level.remove(order); // O(level size)
        if (level.isEmpty()) {
            side.remove(order.price);
        }
    }

    private List<Fill> match(Order taker) {
        List<Fill> fills = new ArrayList<>();
        NavigableMap<Long, ArrayDeque<Order>> opposite = taker.buy ? asks : bids;
        while (taker.remaining > 0 && !opposite.isEmpty()) {
            Map.Entry<Long, ArrayDeque<Order>> best = opposite.firstEntry();
            long levelPrice = best.getKey();
            boolean crosses = taker.buy ? levelPrice <= taker.price : levelPrice >= taker.price;
            if (!crosses) {
                break;
            }
            ArrayDeque<Order> level = best.getValue();
            while (taker.remaining > 0 && !level.isEmpty()) {
                Order maker = level.peekFirst();
                long qty = Math.min(taker.remaining, maker.remaining);
                maker.remaining -= qty;
                taker.remaining -= qty;
                fills.add(new Fill(maker.id, taker.id, levelPrice, qty));
                if (maker.remaining == 0) {
                    level.pollFirst();
                    byId.remove(maker.id);
                }
            }
            if (level.isEmpty()) {
                opposite.remove(levelPrice);
            }
        }
        return fills;
    }

    private void publish(List<Fill> fills) {
        for (Fill f : fills) {
            sink.onFill(f.makerId(), f.takerId(), f.price(), f.qty());
        }
    }
}
