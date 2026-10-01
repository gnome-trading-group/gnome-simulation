package group.gnometrading.simulation.latency;

import java.util.Random;

/**
 * Normally distributed latency, truncated at zero.
 *
 * <p>The generator is seeded so that replaying a backtest produces identical results — an
 * unseeded source would make every run differ and break iteration-to-iteration comparison.
 *
 * <p>Draws below zero are clamped rather than resampled. A sigma that is large relative to mu
 * will therefore pile mass at zero; prefer a distribution with positive support if that matters.
 */
public final class GaussianLatency implements LatencyModel {

    public static final long DEFAULT_SEED = 0x9E3779B97F4A7C15L;

    private final double mu;
    private final double sigma;
    private final Random random;

    public GaussianLatency(double mu, double sigma) {
        this(mu, sigma, DEFAULT_SEED);
    }

    public GaussianLatency(double mu, double sigma, long seed) {
        this.mu = mu;
        this.sigma = sigma;
        this.random = new Random(seed);
    }

    @Override
    public long simulate() {
        return Math.max(0L, (long) (mu + sigma * random.nextGaussian()));
    }
}
