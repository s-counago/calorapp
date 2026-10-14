package com.sejio.calorapp;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class CreditCardsTest {
    @Test public void lastDayOfNextMonthIncludesLeapYearsAndYearBoundary() {
        assertDue("2026-10-01", "2026-11-30");
        assertDue("2026-10-31", "2026-11-30");
        assertDue("2026-12-31", "2027-01-31");
        assertDue("2028-01-31", "2028-02-29");
        assertDue("2027-01-31", "2027-02-28");
    }

    private void assertDue(String purchase, String expected) {
        assertEquals(expected, Ledger.iso(CreditCards.collectionDay(Ledger.parseIso(purchase))));
    }
}
