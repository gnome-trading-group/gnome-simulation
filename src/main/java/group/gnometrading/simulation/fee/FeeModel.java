package group.gnometrading.simulation.fee;

public interface FeeModel {
    double calculateFee(double notional, boolean isMaker);
}
