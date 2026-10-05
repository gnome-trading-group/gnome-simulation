package group.gnometrading.simulation.latency;

/**
 * Derives independent, reproducible seeds for the latency models of one run.
 *
 * <p>Every model seeded with the same constant draws the same sequence, so a run's network and order-processing
 * latencies, and every listing's, would move in lockstep. Mixing a base seed with a stream id gives each model its
 * own sequence while keeping the run reproducible from the base seed alone.
 */
public final class LatencySeeds {

    public static final long NETWORK_STREAM = 1;
    public static final long ORDER_PROCESSING_STREAM = 2;

    private LatencySeeds() {}

    public static long derive(long baseSeed, long stream) {
        return mix64(baseSeed + mix64(stream + 0x9E3779B97F4A7C15L));
    }

    // SplitMix64's finalizer: adjacent inputs map to unrelated outputs.
    private static long mix64(long input) {
        long mixed = (input ^ (input >>> 30)) * 0xBF58476D1CE4E5B9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }
}
