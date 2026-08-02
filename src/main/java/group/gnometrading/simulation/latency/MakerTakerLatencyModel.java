package group.gnometrading.simulation.latency;

public final class MakerTakerLatencyModel implements LatencyModel {

    private final long baseNanos;
    private final long takerDelayNanos;
    private final long makerDelayNanos;

    public MakerTakerLatencyModel(long baseNanos, long takerDelayNanos, long makerDelayNanos) {
        this.baseNanos = baseNanos;
        this.takerDelayNanos = takerDelayNanos;
        this.makerDelayNanos = makerDelayNanos;
    }

    @Override
    public long simulate() {
        return baseNanos + takerDelayNanos;
    }

    @Override
    public long simulate(boolean isMaker) {
        return baseNanos + (isMaker ? makerDelayNanos : takerDelayNanos);
    }
}
