package group.gnometrading.simulation.fee;

public interface FeeModel {
    double calculateFee(long price, long quantity, boolean isMaker);
}
