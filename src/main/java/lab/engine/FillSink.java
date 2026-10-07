package lab.engine;

/** Receives fills. Primitive arguments so the fast path never has to allocate a Fill object. */
@FunctionalInterface
public interface FillSink {
    void onFill(long makerId, long takerId, long price, long qty);

    /** Order-sensitive checksum used to prove two engines produced the exact same fills. */
    final class Checksum implements FillSink {
        public long hash = 17;
        public long fills;
        public long volume;

        @Override
        public void onFill(long makerId, long takerId, long price, long qty) {
            long h = hash;
            h = h * 1_000_003L + makerId;
            h = h * 1_000_003L + takerId;
            h = h * 1_000_003L + price;
            h = h * 1_000_003L + qty;
            hash = h;
            fills++;
            volume += qty;
        }
    }
}
