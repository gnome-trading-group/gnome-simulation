package group.gnometrading.simulation.fee;

import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.fee.OrderFeeState;

public final class ParametricFeeModel implements FeeModel {

    private final double takerFeeRate;
    private final double makerFeeRate;

    public ParametricFeeModel(double takerFeeRate, double makerFeeRate) {
        this.takerFeeRate = takerFeeRate;
        this.makerFeeRate = makerFeeRate;
    }

    @Override
    public long fee(long nowNanos, boolean buy, long price, long quantity, boolean maker, OrderFeeState state) {
        double rate = maker ? makerFeeRate : takerFeeRate;
        if (rate == 0.0) {
            return 0;
        }
        double normalizedPrice = (double) price / Statics.PRICE_SCALING_FACTOR;
        double normalizedQty = (double) quantity / Statics.SIZE_SCALING_FACTOR;
        return (long) (normalizedQty * rate * normalizedPrice * (1.0 - normalizedPrice) * Statics.PRICE_SCALING_FACTOR);
    }
}
