package group.gnometrading.simulation.fee;

import static org.junit.jupiter.api.Assertions.*;

import group.gnometrading.schemas.Statics;
import org.junit.jupiter.api.Test;

class ParametricFeeModelTest {

    private static final double TAKER_RATE = 0.07;
    private static final double MAKER_RATE = 0.0175;
    private static final long PRICE_SCALE = Statics.PRICE_SCALING_FACTOR;
    private static final long SIZE_SCALE = Statics.SIZE_SCALING_FACTOR;

    private final ParametricFeeModel takerOnlyModel = new ParametricFeeModel(TAKER_RATE, 0.0);
    private final ParametricFeeModel parametricModel = new ParametricFeeModel(TAKER_RATE, MAKER_RATE);

    @Test
    void makerFeeIsZeroWhenRateIsZero() {
        long price = (long) (0.50 * PRICE_SCALE);
        long qty = 100 * SIZE_SCALE;
        assertEquals(0, takerOnlyModel.fee(0, true, price, qty, true, null));
    }

    @Test
    void makerFeeUsesCorrectRate() {
        long price = (long) (0.50 * PRICE_SCALE);
        long qty = 100 * SIZE_SCALE;
        long expected = (long) (100.0 * MAKER_RATE * 0.25 * PRICE_SCALE);
        assertEquals(expected, parametricModel.fee(0, true, price, qty, true, null));
    }

    @Test
    void takerFeeAtMidpoint() {
        // p=0.50 maximizes p*(1-p) = 0.25
        long price = (long) (0.50 * PRICE_SCALE);
        long qty = 100 * SIZE_SCALE;
        // expected = C * feeRate * p * (1-p) = 100 * 0.07 * 0.25 = 1.75 dollars, returned in price units
        long expected = (long) (100.0 * TAKER_RATE * 0.25 * PRICE_SCALE);
        assertEquals(expected, parametricModel.fee(0, true, price, qty, false, null));
    }

    @Test
    void takerFeeSymmetricAroundMidpoint() {
        long qty = 100 * SIZE_SCALE;
        long priceAt01 = (long) (0.01 * PRICE_SCALE);
        long priceAt99 = (long) (0.99 * PRICE_SCALE);
        long feeAt01 = parametricModel.fee(0, true, priceAt01, qty, false, null);
        long feeAt99 = parametricModel.fee(0, true, priceAt99, qty, false, null);
        // p*(1-p) is symmetric: 0.01*0.99 == 0.99*0.01; allow FP rounding at scaling boundaries
        assertEquals(feeAt01, feeAt99, 1);
    }

    @Test
    void takerFeeAtBoundaryZero() {
        long qty = 100 * SIZE_SCALE;
        long priceAt0 = 0;
        long priceAt1 = PRICE_SCALE;
        assertEquals(0, parametricModel.fee(0, true, priceAt0, qty, false, null));
        assertEquals(0, parametricModel.fee(0, true, priceAt1, qty, false, null));
    }

    @Test
    void takerFeeScalesLinearlyWithQuantity() {
        long price = (long) (0.40 * PRICE_SCALE);
        long qty1 = 10 * SIZE_SCALE;
        long qty2 = 20 * SIZE_SCALE;
        long fee1 = parametricModel.fee(0, true, price, qty1, false, null);
        long fee2 = parametricModel.fee(0, true, price, qty2, false, null);
        assertEquals(fee2, fee1 * 2, 1);
    }
}
