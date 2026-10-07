package lab.engine;

/**
 * Price-time-priority order book, verified identical across implementations by {@code Verify}.
 * {@link #add} rests any unfilled remainder; {@link #ioc} discards it. {@link #cancel} is a
 * no-op for an unknown or already-filled id. Order ids are assigned by the caller in
 * increasing order, which lets fast implementations index by id.
 */
public interface Engine {
    void add(long orderId, boolean buy, long price, long qty);

    void ioc(long orderId, boolean buy, long price, long qty);

    void cancel(long orderId);
}
