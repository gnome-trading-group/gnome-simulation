package group.gnometrading.simulation.latency;

public interface LatencyModel {
    /** Returns simulated latency in nanoseconds. */
    long simulate();

    default long simulate(boolean isMaker) {
        return simulate();
    }
}
