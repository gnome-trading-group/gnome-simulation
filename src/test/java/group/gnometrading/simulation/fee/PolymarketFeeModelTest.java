package group.gnometrading.simulation.fee;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.schemas.Statics;
import org.junit.jupiter.api.Test;

class PolymarketFeeModelTest {

    private static final double FEE_RATE = 0.07;
    private static final long PRICE_SCALE = Statics.PRICE_SCALING_FACTOR;
    private static final long SIZE_SCALE = Statics.SIZE_SCALING_FACTOR;

    private final PolymarketFeeModel model = new PolymarketFeeModel(FEE_RATE);

    @Test
    void makerFeeIsZero() {
        long price = (long) (0.50 * PRICE_SCALE);
        long qty = 100 * SIZE_SCALE;
        assertEquals(0.0, model.calculateFee(price, qty, true));
    }

    @Test
    void takerFeeAtMidpoint() {
        // p=0.50 maximizes p*(1-p) = 0.25
        long price = (long) (0.50 * PRICE_SCALE);
        long qty = 100 * SIZE_SCALE;
        // expected = C * feeRate * p * (1-p) = 100 * 0.07 * 0.25 = 1.75 dollars
        // returned in scaled units = 1.75 * PRICE_SCALE * SIZE_SCALE
        double expected = 100.0 * FEE_RATE * 0.25 * PRICE_SCALE * SIZE_SCALE;
        assertEquals(expected, model.calculateFee(price, qty, false), 1e-3);
    }

    @Test
    void takerFeeSymmetricAroundMidpoint() {
        long qty = 100 * SIZE_SCALE;
        long priceAt01 = (long) (0.01 * PRICE_SCALE);
        long priceAt99 = (long) (0.99 * PRICE_SCALE);
        double feeAt01 = model.calculateFee(priceAt01, qty, false);
        double feeAt99 = model.calculateFee(priceAt99, qty, false);
        // p*(1-p) is symmetric: 0.01*0.99 == 0.99*0.01; allow FP rounding at scaling boundaries
        assertEquals(feeAt01, feeAt99, 1e6);
    }

    @Test
    void takerFeeAtBoundaryZero() {
        long qty = 100 * SIZE_SCALE;
        long priceAt0 = 0;
        long priceAt1 = PRICE_SCALE;
        assertEquals(0.0, model.calculateFee(priceAt0, qty, false), 1e-6);
        assertEquals(0.0, model.calculateFee(priceAt1, qty, false), 1e-6);
    }

    @Test
    void takerFeeScalesLinearllyWithQuantity() {
        long price = (long) (0.40 * PRICE_SCALE);
        long qty1 = 10 * SIZE_SCALE;
        long qty2 = 20 * SIZE_SCALE;
        double fee1 = model.calculateFee(price, qty1, false);
        double fee2 = model.calculateFee(price, qty2, false);
        assertEquals(fee2, fee1 * 2, 1e-3);
    }
}
