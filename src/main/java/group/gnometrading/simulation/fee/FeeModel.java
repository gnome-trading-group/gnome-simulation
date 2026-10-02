package group.gnometrading.simulation.fee;

public interface FeeModel {
    /**
     * Returns the fee in price units (1e9 = $1) so it can be written to the execution report without rescaling.
     */
    double calculateFee(long price, long quantity, boolean isMaker);
}
