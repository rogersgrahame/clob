package org.greeps.clob.engine;

import org.greeps.clob.command.CancelOrderCommand;
import org.greeps.clob.command.ModifyOrderCommand;
import org.greeps.clob.command.SubmitOrderCommand;
import org.greeps.clob.core.OrderType;
import org.greeps.clob.core.Side;
import org.greeps.clob.event.*;
import org.greeps.clob.id.OrderIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedTransferQueue;

import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineTest {

    private MatchingEngine engine;
    private LinkedTransferQueue<Event> events;

    @BeforeEach
    void setUp() {
        events = new LinkedTransferQueue<>();
        engine = new MatchingEngine("AAPL", new OrderIdGenerator(), events);
    }

    // --- helpers ---

    private List<Event> drain() {
        List<Event> list = new ArrayList<>();
        events.drainTo(list);
        return list;
    }

    private long submitLimit(Side side, long price, long qty) {
        engine.processSync(new SubmitOrderCommand("AAPL", side, OrderType.LIMIT, price, qty));
        return ((OrderAcceptedEvent) drain().getFirst()).orderId();
    }

    private long submitMarket(Side side, long qty) {
        engine.processSync(new SubmitOrderCommand("AAPL", side, OrderType.MARKET, 0, qty));
        return ((OrderAcceptedEvent) drain().getFirst()).orderId();
    }

    private void cancel(long orderId) {
        engine.processSync(new CancelOrderCommand(orderId, "AAPL"));
    }

    private void modify(long orderId, long newPrice, long newQty) {
        engine.processSync(new ModifyOrderCommand(orderId, "AAPL", newPrice, newQty));
    }

    // -----------------------------------------------------------------------
    // Limit order — full fill
    // -----------------------------------------------------------------------

    @Test
    void limitOrder_fullFill_bothSides() {
        long askId = submitLimit(Side.ASK, 100, 10);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 10));
        List<Event> emitted = drain();

        OrderAcceptedEvent accepted  = (OrderAcceptedEvent) emitted.get(0);
        TradeEvent          trade    = (TradeEvent)          emitted.get(1);
        OrderFilledEvent    restFill = (OrderFilledEvent)    emitted.get(2);
        OrderFilledEvent    aggFill  = (OrderFilledEvent)    emitted.get(3);

        assertEquals(100,   trade.price());
        assertEquals(10,    trade.quantity());
        assertEquals(askId, trade.sellOrderId());
        assertEquals(accepted.orderId(), trade.buyOrderId());

        assertTrue(restFill.isFull());
        assertEquals(askId, restFill.orderId());

        assertTrue(aggFill.isFull());
        assertEquals(accepted.orderId(), aggFill.orderId());
    }

    // -----------------------------------------------------------------------
    // Limit order — partial fill (incoming larger than resting)
    // -----------------------------------------------------------------------

    @Test
    void limitOrder_partialFill_incomingRestsRemainder() {
        submitLimit(Side.ASK, 100, 5);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 10));
        List<Event> emitted = drain();

        // accepted + trade + restFill(full) — no aggFill, incoming rests with 5 remaining
        assertEquals(3, emitted.size());
        assertInstanceOf(OrderAcceptedEvent.class, emitted.get(0));
        TradeEvent trade = (TradeEvent) emitted.get(1);
        assertEquals(5, trade.quantity());
        assertTrue(((OrderFilledEvent) emitted.get(2)).isFull());

        // A new ask at same price should fill the resting bid remainder
        engine.processSync(new SubmitOrderCommand("AAPL", Side.ASK, OrderType.LIMIT, 100, 5));
        List<Event> second = drain();
        assertTrue(second.stream().anyMatch(e -> e instanceof TradeEvent t && t.quantity() == 5));
    }

    // -----------------------------------------------------------------------
    // Limit order — price does not cross (no match)
    // -----------------------------------------------------------------------

    @Test
    void limitOrder_noCross_restsInBook() {
        submitLimit(Side.ASK, 101, 10);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 10));
        List<Event> emitted = drain();

        assertEquals(1, emitted.size());
        assertInstanceOf(OrderAcceptedEvent.class, emitted.getFirst());
    }

    // -----------------------------------------------------------------------
    // Limit order — multiple price levels consumed
    // -----------------------------------------------------------------------

    @Test
    void limitOrder_sweepsMultipleLevels() {
        submitLimit(Side.ASK, 100, 5);
        submitLimit(Side.ASK, 101, 5);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 102, 10));
        List<Event> emitted = drain();

        List<TradeEvent> trades = emitted.stream()
                .filter(e -> e instanceof TradeEvent).map(e -> (TradeEvent) e).toList();
        assertEquals(2, trades.size());
        assertEquals(100, trades.get(0).price());
        assertEquals(101, trades.get(1).price());

        long fullFills = emitted.stream()
                .filter(e -> e instanceof OrderFilledEvent f && f.isFull()).count();
        assertEquals(3, fullFills); // both resting + the aggressive
    }

    // -----------------------------------------------------------------------
    // Cancel
    // -----------------------------------------------------------------------

    @Test
    void cancel_restingOrder_emitsCancelledEvent() {
        long askId = submitLimit(Side.ASK, 100, 10);
        cancel(askId);
        List<Event> emitted = drain();

        assertEquals(1, emitted.size());
        OrderCancelledEvent cancelled = (OrderCancelledEvent) emitted.getFirst();
        assertEquals(askId, cancelled.orderId());
        assertEquals(CancelReason.CANCELLED, cancelled.reason());
    }

    @Test
    void cancel_unknownOrder_isNoOp() {
        cancel(999L);
        assertTrue(drain().isEmpty());
    }

    @Test
    void cancel_preventsSubsequentMatch() {
        long askId = submitLimit(Side.ASK, 100, 10);
        cancel(askId);
        drain();

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 10));
        List<Event> emitted = drain();

        assertEquals(1, emitted.size());
        assertInstanceOf(OrderAcceptedEvent.class, emitted.getFirst());
    }

    // -----------------------------------------------------------------------
    // Modify — cancel + re-submit, loses time priority
    // -----------------------------------------------------------------------

    @Test
    void modify_losingTimePriority() {
        long first  = submitLimit(Side.ASK, 100, 10);
        long second = submitLimit(Side.ASK, 100, 5);

        modify(first, 100, 10);
        drain();

        // A bid of qty 5 should fill 'second' (now front of queue), not the modified order
        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 5));
        List<Event> emitted = drain();

        TradeEvent trade = emitted.stream()
                .filter(e -> e instanceof TradeEvent).map(e -> (TradeEvent) e)
                .findFirst().orElseThrow();
        assertEquals(second, trade.sellOrderId());
    }

    @Test
    void modify_unknownOrder_isNoOp() {
        modify(999L, 100, 10);
        assertTrue(drain().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Market order — full fill
    // -----------------------------------------------------------------------

    @Test
    void marketOrder_fullFill() {
        submitLimit(Side.ASK, 100, 10);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.MARKET, 0, 10));
        List<Event> emitted = drain();

        assertTrue(emitted.stream().anyMatch(e -> e instanceof TradeEvent t && t.quantity() == 10));
        assertTrue(emitted.stream().anyMatch(e -> e instanceof OrderFilledEvent f && f.isFull()));
        assertFalse(emitted.stream().anyMatch(e -> e instanceof OrderCancelledEvent));
    }

    // -----------------------------------------------------------------------
    // Market order — insufficient liquidity (partial fill, cancel remainder)
    // -----------------------------------------------------------------------

    @Test
    void marketOrder_partialFill_cancelRemainder() {
        submitLimit(Side.ASK, 100, 3);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.MARKET, 0, 10));
        List<Event> emitted = drain();

        TradeEvent trade = emitted.stream()
                .filter(e -> e instanceof TradeEvent).map(e -> (TradeEvent) e)
                .findFirst().orElseThrow();
        assertEquals(3, trade.quantity());

        OrderCancelledEvent cancelled = emitted.stream()
                .filter(e -> e instanceof OrderCancelledEvent).map(e -> (OrderCancelledEvent) e)
                .findFirst().orElseThrow();
        assertEquals(CancelReason.INSUFFICIENT_LIQUIDITY, cancelled.reason());
    }

    @Test
    void marketOrder_emptyBook_cancelImmediately() {
        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.MARKET, 0, 5));
        List<Event> emitted = drain();

        long cancelCount = emitted.stream().filter(e -> e instanceof OrderCancelledEvent).count();
        assertEquals(1, cancelCount);
    }

    // -----------------------------------------------------------------------
    // Price-time priority — FIFO within a level
    // -----------------------------------------------------------------------

    @Test
    void fifoWithinPriceLevel() {
        long first  = submitLimit(Side.ASK, 100, 5);
        long second = submitLimit(Side.ASK, 100, 5);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 5));
        List<Event> emitted = drain();

        TradeEvent trade = emitted.stream()
                .filter(e -> e instanceof TradeEvent).map(e -> (TradeEvent) e)
                .findFirst().orElseThrow();
        assertEquals(first, trade.sellOrderId());
    }
}
