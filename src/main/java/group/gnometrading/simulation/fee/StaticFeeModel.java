package group.gnometrading.simulation.fee;

import group.gnometrading.schemas.Statics;
import group.gnometrading.sm.fee.OrderFeeState;

public final class StaticFeeModel implements FeeModel {

    private final double takerFee;
    private final double makerFee;

    public StaticFeeModel(double takerFee, double makerFee) {
        this.takerFee = takerFee;
        this.makerFee = makerFee;
    }

    @Override
    public long fee(long nowNanos, boolean buy, long price, long quantity, boolean maker, OrderFeeState state) {
        double notional = price * ((double) quantity / Statics.SIZE_SCALING_FACTOR);
        return (long) (maker ? notional * makerFee : notional * takerFee);
    }
}
