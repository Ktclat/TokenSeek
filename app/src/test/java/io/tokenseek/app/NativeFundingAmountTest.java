package io.tokenseek.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NativeFundingAmountTest {
    @Test public void keepsExactCentsAndNormalizesCustomAmounts() {
        assertEquals("50.00", NativeFunding.parseAmount("50").value);
        assertEquals("0.05", NativeFunding.parseAmount(".05").value);
        assertEquals("12.30", NativeFunding.parseAmount("００１２．３").value);
        assertEquals("999999999999.99", NativeFunding.parseAmount("999999999999.99").value);
        assertEquals("", NativeFunding.parseAmount("  ").value);
    }

    @Test public void rejectsInputsThatWouldChangeThePaymentAmount() {
        for (String raw : new String[]{"0", ".00", "-5", "5.123", "1e2", "1,000", "2.0.0", "¥100"}) {
            assertEquals(raw, "", NativeFunding.parseAmount(raw).value);
            assertEquals(raw, "invalid", NativeFunding.parseAmount(raw).error);
        }
        assertEquals("overflow", NativeFunding.parseAmount("1000000000000").error);
    }
}
