package group.gnometrading.simulation.fee;

import group.gnometrading.schemas.Statics;

public final class ParametricFeeModel implements FeeModel {

    private final double takerFeeRate;
    private final double makerFeeRate;

    public ParametricFeeModel(double takerFeeRate, double makerFeeRate) {
        this.takerFeeRate = takerFeeRate;
        this.makerFeeRate = makerFeeRate;
    }

    @Override
    public double calculateFee(long price, long quantity, boolean isMaker) {
        double rate = isMaker ? makerFeeRate : takerFeeRate;
        if (rate == 0.0) {
            return 0.0;
        }
        double normalizedPrice = (double) price / Statics.PRICE_SCALING_FACTOR;
        double normalizedQty = (double) quantity / Statics.SIZE_SCALING_FACTOR;
        // toScaledFee() divides by SIZE_SCALING_FACTOR, so return actual_fee * PRICE_SCALE * SIZE_SCALE
        return normalizedQty
                * rate
                * normalizedPrice
                * (1.0 - normalizedPrice)
                * Statics.PRICE_SCALING_FACTOR
                * Statics.SIZE_SCALING_FACTOR;
    }
}
