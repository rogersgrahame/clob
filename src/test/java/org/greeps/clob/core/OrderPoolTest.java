package org.greeps.clob.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderPoolTest {

    @Test
    void borrowedOrder_hasCorrectFields() {
        OrderPool pool = new OrderPool(4);
        Order order = pool.borrow(42L, "AAPL", Side.BID, OrderType.LIMIT, 100L, 10L, 999L);

        assertEquals(42L,          order.orderId());
        assertEquals("AAPL",       order.instrumentId());
        assertEquals(Side.BID,     order.side());
        assertEquals(OrderType.LIMIT, order.type());
        assertEquals(100L,         order.price());
        assertEquals(10L,          order.quantity());
        assertEquals(10L,          order.remaining());
        assertEquals(OrderStatus.NEW, order.status());
        assertEquals(999L,         order.timestamp());
    }

    @Test
    void releasedOrder_isReusedOnNextBorrow() {
        OrderPool pool = new OrderPool(1);
        Order first = pool.borrow(1L, "AAPL", Side.ASK, OrderType.LIMIT, 100L, 5L, 1L);

        pool.release(first);

        Order second = pool.borrow(2L, "MSFT", Side.BID, OrderType.MARKET, 0L, 3L, 2L);

        assertSame(first, second); // same object reused
        assertEquals(2L,    second.orderId());
        assertEquals("MSFT", second.instrumentId());
        assertEquals(3L,    second.remaining());
        assertEquals(OrderStatus.NEW, second.status());
    }

    @Test
    void poolExhausted_fallsBackToHeapAllocation() {
        OrderPool pool = new OrderPool(1);
        Order first  = pool.borrow(1L, "AAPL", Side.BID, OrderType.LIMIT, 100L, 1L, 1L);
        Order second = pool.borrow(2L, "AAPL", Side.BID, OrderType.LIMIT, 100L, 1L, 2L);

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second); // second was heap-allocated
        assertEquals(0, pool.available());
    }

    @Test
    void borrowedOrder_remainingResetCorrectlyAfterFill() {
        OrderPool pool = new OrderPool(2);
        Order order = pool.borrow(1L, "AAPL", Side.BID, OrderType.LIMIT, 100L, 10L, 1L);
        order.fill(7L);
        assertEquals(3L, order.remaining());

        pool.release(order);

        // Re-borrow — remaining must be reset to new quantity
        Order reused = pool.borrow(2L, "AAPL", Side.BID, OrderType.LIMIT, 100L, 5L, 2L);
        assertEquals(5L, reused.remaining());
        assertEquals(OrderStatus.NEW, reused.status());
    }

    @Test
    void availableCount_tracksCorrectly() {
        OrderPool pool = new OrderPool(3);
        assertEquals(3, pool.available());

        Order a = pool.borrow(1L, "X", Side.BID, OrderType.LIMIT, 1, 1, 1);
        assertEquals(2, pool.available());

        Order b = pool.borrow(2L, "X", Side.BID, OrderType.LIMIT, 1, 1, 1);
        assertEquals(1, pool.available());

        pool.release(a);
        assertEquals(2, pool.available());

        pool.release(b);
        assertEquals(3, pool.available());
    }
}
