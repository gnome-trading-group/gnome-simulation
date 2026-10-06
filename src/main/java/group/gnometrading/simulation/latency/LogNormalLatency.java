package group.gnometrading.simulation.latency;

import java.util.Random;

/**
 * A fixed floor plus a log-normal excess: never faster than the floor, usually near the median, and now and then far
 * slower, as network and venue latency are. Set by its floor, median and 99th percentile, which can be read straight
 * off a latency chart.
 *
 * <p>The generator is seeded so that replaying a backtest produces identical results.
 */
public final class LogNormalLatency implements LatencyModel {

    // The standard normal's 99th percentile.
    private static final double Z_99 = 2.3263478740408408;

    private final long floorNanos;
    private final double mu;
    private final double sigma;
    private final Random random;

    public LogNormalLatency(long floorNanos, long medianNanos, long p99Nanos, long seed) {
        if (floorNanos < 0 || medianNanos <= floorNanos || p99Nanos <= medianNanos) {
            throw new IllegalArgumentException("Log-normal latency needs 0 <= floor < median < p99, got floor="
                    + floorNanos + " median=" + medianNanos + " p99=" + p99Nanos);
        }
        this.floorNanos = floorNanos;
        this.mu = Math.log(medianNanos - floorNanos);
        this.sigma = (Math.log(p99Nanos - floorNanos) - mu) / Z_99;
        this.random = new Random(seed);
    }

    @Override
    public long simulate() {
        return floorNanos + (long) Math.exp(mu + sigma * random.nextGaussian());
    }
}
