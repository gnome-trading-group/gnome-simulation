package group.gnometrading.simulation.fee;

import group.gnometrading.schemas.Statics;

public final class StaticFeeModel implements FeeModel {

    private final double takerFee;
    private final double makerFee;

    public StaticFeeModel(double takerFee, double makerFee) {
        this.takerFee = takerFee;
        this.makerFee = makerFee;
    }

    @Override
    public double calculateFee(long price, long quantity, boolean isMaker) {
        double notional = price * ((double) quantity / Statics.SIZE_SCALING_FACTOR);
        return isMaker ? notional * makerFee : notional * takerFee;
    }
}
