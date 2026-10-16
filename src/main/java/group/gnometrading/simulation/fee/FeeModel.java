package group.gnometrading.simulation.fee;

import group.gnometrading.sm.fee.OrderFeeState;

public interface FeeModel {
    /**
     * The fee for one fill at {@code nowNanos} (epoch nanoseconds), in price units (1e9 = $1) so it can be written to
     * the execution report without rescaling. {@code state} is the order's own, the same instance for each of its
     * fills, for venues that carry rounding across an order's fills.
     */
    long fee(long nowNanos, boolean buy, long price, long quantity, boolean maker, OrderFeeState state);
}
