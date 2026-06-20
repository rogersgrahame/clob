package org.greeps.clob.book;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PriceLadderTest {

    // -----------------------------------------------------------------------
    // Ascending (asks) — lowest price best
    // -----------------------------------------------------------------------

    @Test
    void ascending_bestPriceIsLowest() {
        PriceLadder ladder = new PriceLadder(false);
        ladder.getOrCreate(103);
        ladder.getOrCreate(101);
        ladder.getOrCreate(102);

        assertEquals(101, ladder.bestPrice());
        assertEquals(3, ladder.size());
    }

    @Test
    void ascending_removeMiddle_orderPreserved() {
        PriceLadder ladder = new PriceLadder(false);
        ladder.getOrCreate(100);
        ladder.getOrCreate(101);
        ladder.getOrCreate(102);

        ladder.remove(101);

        assertEquals(100, ladder.bestPrice());
        assertEquals(2, ladder.size());
        assertNull(ladder.get(101));
    }

    @Test
    void ascending_removeBest_nextBecomesNew() {
        PriceLadder ladder = new PriceLadder(false);
        ladder.getOrCreate(100);
        ladder.getOrCreate(101);

        ladder.remove(100);

        assertEquals(101, ladder.bestPrice());
        assertEquals(1, ladder.size());
    }

    // -----------------------------------------------------------------------
    // Descending (bids) — highest price best
    // -----------------------------------------------------------------------

    @Test
    void descending_bestPriceIsHighest() {
        PriceLadder ladder = new PriceLadder(true);
        ladder.getOrCreate(100);
        ladder.getOrCreate(103);
        ladder.getOrCreate(101);

        assertEquals(103, ladder.bestPrice());
        assertEquals(3, ladder.size());
    }

    @Test
    void descending_removeBest_nextBecomesNew() {
        PriceLadder ladder = new PriceLadder(true);
        ladder.getOrCreate(103);
        ladder.getOrCreate(101);

        ladder.remove(103);

        assertEquals(101, ladder.bestPrice());
        assertEquals(1, ladder.size());
    }

    // -----------------------------------------------------------------------
    // Empty / sentinel
    // -----------------------------------------------------------------------

    @Test
    void emptyLadder_bestPriceIsSentinel() {
        PriceLadder ladder = new PriceLadder(false);
        assertEquals(Long.MIN_VALUE, ladder.bestPrice());
        assertNull(ladder.bestLevel());
        assertTrue(ladder.isEmpty());
    }

    @Test
    void drainToEmpty_isEmpty() {
        PriceLadder ladder = new PriceLadder(false);
        ladder.getOrCreate(100);
        ladder.remove(100);

        assertTrue(ladder.isEmpty());
        assertEquals(Long.MIN_VALUE, ladder.bestPrice());
        assertNull(ladder.bestLevel());
    }

    // -----------------------------------------------------------------------
    // getOrCreate idempotence — same price returns same level
    // -----------------------------------------------------------------------

    @Test
    void getOrCreate_samePriceTwice_returnsSameLevel() {
        PriceLadder ladder = new PriceLadder(false);
        PriceLevel first  = ladder.getOrCreate(100);
        PriceLevel second = ladder.getOrCreate(100);

        assertSame(first, second);
        assertEquals(1, ladder.size());
    }

    @Test
    void get_existingPrice_returnsLevel() {
        PriceLadder ladder = new PriceLadder(false);
        PriceLevel created = ladder.getOrCreate(100);
        assertSame(created, ladder.get(100));
    }

    @Test
    void get_absentPrice_returnsNull() {
        PriceLadder ladder = new PriceLadder(false);
        assertNull(ladder.get(999));
    }

    // -----------------------------------------------------------------------
    // Capacity guard
    // -----------------------------------------------------------------------

    @Test
    void capacityExceeded_throwsIllegalState() {
        PriceLadder ladder = new PriceLadder(2, false);
        ladder.getOrCreate(100);
        ladder.getOrCreate(101);

        assertThrows(IllegalStateException.class, () -> ladder.getOrCreate(102));
    }
}
