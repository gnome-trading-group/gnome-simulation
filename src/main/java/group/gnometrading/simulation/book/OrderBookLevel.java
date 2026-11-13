package group.gnometrading.simulation.book;

import java.util.ArrayDeque;

public final class OrderBookLevel {

    public long price;
    public long size;
    /** Displayed liquidity we have already taken; the replayed feed never shows our trades. */
    public long consumed;
    /** Beyond the depth the latest snapshot showed: its size is only the last one seen. */
    public boolean outOfView;

    /**
     * Volume book changes took off this level that no trade has claimed yet. Applied as cancels until a paired trade
     * claims it; see {@link TradePairing}.
     */
    long pendingShrink;
    /** The venue time of the earliest and latest book changes in {@link #pendingShrink}. */
    long pendingFirstNanos;

    long pendingLastNanos;
    /** How much the cancels applied for {@link #pendingShrink} cleared from {@link #consumed}. */
    long pendingConsumedCleared;

    public final ArrayDeque<LocalOrder> localOrders;

    public OrderBookLevel(long price, long size) {
        this.price = price;
        this.size = size;
        this.localOrders = new ArrayDeque<>();
    }

    /** Makes this an empty level at {@code price}, for {@link PriceLadder} to reuse it. */
    void reset(long price) {
        this.price = price;
        this.size = 0;
        this.consumed = 0;
        this.outOfView = false;
        this.pendingShrink = 0;
        this.pendingFirstNanos = 0;
        this.pendingLastNanos = 0;
        this.pendingConsumedCleared = 0;
        this.localOrders.clear();
    }

    /** Displayed size less what we have taken; none while out of view, since its size is stale. */
    public long available() {
        return outOfView ? 0 : Math.max(0, size - consumed);
    }

    /** Market volume an order joining the back of this level would have ahead of it. */
    public long queueOnJoin() {
        return Math.max(0, size - consumed);
    }

    /**
     * Records {@code quantity} taken from the front of this level. Our own resting orders here were behind it, so
     * they move up by as much.
     */
    public void take(long quantity) {
        consumed += quantity;
        for (LocalOrder localOrder : localOrders) {
            localOrder.phantomVolume = Math.max(0, localOrder.phantomVolume - quantity);
        }
    }

    public boolean hasLocalOrders() {
        return !localOrders.isEmpty();
    }

    /** Places a local order at the back; one joining while a shrink is pending had no queue position before it. */
    void join(LocalOrder localOrder) {
        localOrder.joinedDuringShrink = pendingShrink > 0;
        localOrder.pendingCancelled = 0;
        localOrders.addLast(localOrder);
    }

    /**
     * Adds a book change that took {@code shrink} off this level at {@code venueNanos} to its pending shrink. One too far
     * from the pending shrink for a single trade to pair with both settles the earlier one as cancels and starts anew.
     */
    void recordShrink(long shrink, long venueNanos, TradePairing pairing) {
        if (pendingShrink <= 0 || !pairing.continues(pendingFirstNanos, venueNanos)) {
            pendingShrink = 0;
            pendingConsumedCleared = 0;
            pendingFirstNanos = venueNanos;
            pendingLastNanos = venueNanos;
            for (LocalOrder localOrder : localOrders) {
                localOrder.pendingCancelled = 0;
                localOrder.joinedDuringShrink = false;
            }
        }
        pendingShrink += shrink;
        pendingFirstNanos = Math.min(pendingFirstNanos, venueNanos);
        pendingLastNanos = Math.max(pendingLastNanos, venueNanos);
    }
}
