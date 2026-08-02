package group.gnometrading.simulation.fee;

import group.gnometrading.schemas.Statics;

public final class PolymarketFeeModel implements FeeModel {

    private final double feeRate;

    public PolymarketFeeModel(double feeRate) {
        this.feeRate = feeRate;
    }

    @Override
    public double calculateFee(long price, long quantity, boolean isMaker) {
        if (isMaker) {
            return 0.0;
        }
        double normalizedPrice = (double) price / Statics.PRICE_SCALING_FACTOR;
        double normalizedQty = (double) quantity / Statics.SIZE_SCALING_FACTOR;
        // toScaledFee() divides by SIZE_SCALING_FACTOR, so return actual_fee * PRICE_SCALE * SIZE_SCALE
        return normalizedQty
                * feeRate
                * normalizedPrice
                * (1.0 - normalizedPrice)
                * Statics.PRICE_SCALING_FACTOR
                * Statics.SIZE_SCALING_FACTOR;
    }
}
