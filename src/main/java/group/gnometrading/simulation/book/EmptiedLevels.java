package group.gnometrading.simulation.book;

import java.util.Arrays;

/**
 * One side's levels a book change emptied while a pairing trade may still be on its way: each price with the volume the
 * change took off it, unclaimed, and when. A feed that delivers a trade's book change first can empty the top level
 * before the print arrives; without this, the print finds the level gone and its size spills into the next one, where
 * it fills orders the real taker never reached.
 *
 * <p>Prices are kept sorted best first, as {@link PriceLadder} keeps them, in primitive arrays that grow rarely, so
 * recording and claiming allocate nothing once warm.
 */
final class EmptiedLevels {

    private static final int INITIAL_CAPACITY = 8;

    private final boolean descending;
    private long[] prices = new long[INITIAL_CAPACITY];
    private long[] shrinks = new long[INITIAL_CAPACITY];
    private long[] firstNanos = new long[INITIAL_CAPACITY];
    private long[] lastNanos = new long[INITIAL_CAPACITY];
    private int size;

    /** @param descending true for bids (highest price first), false for asks */
    EmptiedLevels(boolean descending) {
        this.descending = descending;
    }

    int size() {
        return size;
    }

    long priceAt(int index) {
        return prices[index];
    }

    /** Whether the level at {@code index} comes before, or at the same price as, {@code price} in this side's order. */
    boolean atOrBefore(int index, long price) {
        return descending ? prices[index] >= price : prices[index] <= price;
    }

    /**
     * Records that a book change at {@code venueNanos} emptied the level at {@code price}, taking {@code shrink} off it.
     * One too far from what is already recorded there for a single trade to pair with both replaces it.
     */
    void record(long price, long shrink, long fromNanos, long venueNanos, TradePairing pairing) {
        int index = indexOf(price);
        if (index >= 0) {
            if (pairing.continues(firstNanos[index], venueNanos)) {
                shrinks[index] += shrink;
                firstNanos[index] = Math.min(firstNanos[index], fromNanos);
                lastNanos[index] = Math.max(lastNanos[index], venueNanos);
                return;
            }
            set(index, price, shrink, fromNanos, venueNanos);
            return;
        }
        int at = -index - 1;
        if (size == prices.length) {
            grow();
        }
        System.arraycopy(prices, at, prices, at + 1, size - at);
        System.arraycopy(shrinks, at, shrinks, at + 1, size - at);
        System.arraycopy(firstNanos, at, firstNanos, at + 1, size - at);
        System.arraycopy(lastNanos, at, lastNanos, at + 1, size - at);
        size++;
        set(at, price, shrink, fromNanos, venueNanos);
    }

    /**
     * What of a trade at {@code tradeNanos} the emptied level at {@code index} explains: up to its unclaimed shrink when
     * the trade pairs with it, nothing otherwise. Returns the trade's size left after it; a level fully claimed is
     * removed, so the caller must not advance past {@code index} then.
     */
    long claim(int index, long size, long tradeNanos, TradePairing pairing) {
        if (!pairing.pairs(firstNanos[index], lastNanos[index], tradeNanos)) {
            return size;
        }
        long claimed = Math.min(size, shrinks[index]);
        shrinks[index] -= claimed;
        if (shrinks[index] == 0) {
            removeAt(index);
        }
        return size - claimed;
    }

    /**
     * The emptied level at {@code price}, if any, handed over to the level that now stands there: its unclaimed shrink
     * becomes that level's pending shrink, so a trade still on its way claims it there.
     */
    void handOver(long price, OrderBookLevel level) {
        int index = indexOf(price);
        if (index < 0) {
            return;
        }
        level.pendingShrink += shrinks[index];
        level.pendingFirstNanos = level.pendingShrink == shrinks[index]
                ? firstNanos[index]
                : Math.min(level.pendingFirstNanos, firstNanos[index]);
        level.pendingLastNanos = Math.max(level.pendingLastNanos, lastNanos[index]);
        removeAt(index);
    }

    /** Drops levels no trade at or after {@code nowNanos} could still pair with. */
    void expire(long nowNanos, TradePairing pairing) {
        for (int i = size - 1; i >= 0; i--) {
            if (pairing.stale(lastNanos[i], nowNanos)) {
                removeAt(i);
            }
        }
    }

    void clear() {
        size = 0;
    }

    private void set(int index, long price, long shrink, long fromNanos, long venueNanos) {
        prices[index] = price;
        shrinks[index] = shrink;
        firstNanos[index] = fromNanos;
        lastNanos[index] = venueNanos;
    }

    private void removeAt(int index) {
        System.arraycopy(prices, index + 1, prices, index, size - index - 1);
        System.arraycopy(shrinks, index + 1, shrinks, index, size - index - 1);
        System.arraycopy(firstNanos, index + 1, firstNanos, index, size - index - 1);
        System.arraycopy(lastNanos, index + 1, lastNanos, index, size - index - 1);
        size--;
    }

    private int indexOf(long price) {
        int low = 0;
        int high = size - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midPrice = prices[mid];
            if (midPrice == price) {
                return mid;
            }
            if (descending ? midPrice > price : midPrice < price) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return -(low + 1);
    }

    private void grow() {
        int capacity = prices.length * 2;
        prices = Arrays.copyOf(prices, capacity);
        shrinks = Arrays.copyOf(shrinks, capacity);
        firstNanos = Arrays.copyOf(firstNanos, capacity);
        lastNanos = Arrays.copyOf(lastNanos, capacity);
    }
}
