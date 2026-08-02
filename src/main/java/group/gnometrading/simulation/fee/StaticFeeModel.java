package group.gnometrading.simulation.fee;

public final class StaticFeeModel implements FeeModel {

    private final double takerFee;
    private final double makerFee;

    public StaticFeeModel(double takerFee, double makerFee) {
        this.takerFee = takerFee;
        this.makerFee = makerFee;
    }

    @Override
    public double calculateFee(long price, long quantity, boolean isMaker) {
        double notional = (double) price * quantity;
        return isMaker ? notional * makerFee : notional * takerFee;
    }
}
