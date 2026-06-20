package org.greeps.clob.engine;

import org.greeps.clob.command.CancelOrderCommand;
import org.greeps.clob.command.ModifyOrderCommand;
import org.greeps.clob.command.SubmitOrderCommand;
import org.greeps.clob.core.OrderType;
import org.greeps.clob.core.Side;
import org.greeps.clob.event.CancelReason;
import org.greeps.clob.id.OrderIdGenerator;
import org.greeps.clob.ring.EventRingBuffer;
import org.greeps.clob.ring.EventType;
import org.greeps.clob.ring.MutableEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MatchingEngineTest {

    private MatchingEngine engine;
    private EventRingBuffer eventRing;

    @BeforeEach
    void setUp() {
        eventRing = new EventRingBuffer(256);
        engine    = new MatchingEngine("AAPL", new OrderIdGenerator(), eventRing);
    }

    // --- helpers ---

    /** Drain all available events; call done() on each slot as we go. */
    private List<MutableEvent> drain() {
        List<MutableEvent> list = new ArrayList<>();
        MutableEvent slot;
        while ((slot = eventRing.poll()) != null) {
            list.add(slot);
            eventRing.done(slot);
        }
        return list;
    }

    private long submitLimit(Side side, long price, long qty) {
        engine.processSync(new SubmitOrderCommand("AAPL", side, OrderType.LIMIT, price, qty));
        List<MutableEvent> emitted = drain();
        assertEquals(EventType.ORDER_ACCEPTED, emitted.getFirst().eventType);
        return emitted.getFirst().orderId;
    }

    private void cancel(long orderId) {
        engine.processSync(new CancelOrderCommand(orderId, "AAPL"));
    }

    private void modify(long orderId, long newPrice, long newQty) {
        engine.processSync(new ModifyOrderCommand(orderId, "AAPL", newPrice, newQty));
    }

    private MutableEvent firstOfType(List<MutableEvent> events, EventType type) {
        return events.stream().filter(e -> e.eventType == type).findFirst().orElseThrow();
    }

    private List<MutableEvent> allOfType(List<MutableEvent> events, EventType type) {
        return events.stream().filter(e -> e.eventType == type).toList();
    }

    // -----------------------------------------------------------------------
    // Limit order — full fill
    // -----------------------------------------------------------------------

    @Test
    void limitOrder_fullFill_bothSides() {
        long askId = submitLimit(Side.ASK, 100, 10);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 10));
        List<MutableEvent> emitted = drain();

        long aggId = firstOfType(emitted, EventType.ORDER_ACCEPTED).orderId;

        MutableEvent trade    = firstOfType(emitted, EventType.TRADE);
        assertEquals(100,   trade.price);
        assertEquals(10,    trade.quantity);
        assertEquals(askId, trade.sellOrderId);
        assertEquals(aggId, trade.buyOrderId);

        List<MutableEvent> fills = allOfType(emitted, EventType.ORDER_FILLED);
        assertEquals(2, fills.size());

        MutableEvent restFill = fills.stream().filter(f -> f.orderId == askId).findFirst().orElseThrow();
        assertTrue(restFill.isFullFill());

        MutableEvent aggFill = fills.stream().filter(f -> f.orderId == aggId).findFirst().orElseThrow();
        assertTrue(aggFill.isFullFill());
    }

    // -----------------------------------------------------------------------
    // Limit order — partial fill (incoming larger than resting)
    // -----------------------------------------------------------------------

    @Test
    void limitOrder_partialFill_incomingRestsRemainder() {
        submitLimit(Side.ASK, 100, 5);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 10));
        List<MutableEvent> emitted = drain();

        // accepted + trade + restFill(full) — incoming rests, no fill event for it
        assertEquals(3, emitted.size());
        assertEquals(EventType.ORDER_ACCEPTED, emitted.get(0).eventType);
        MutableEvent trade = firstOfType(emitted, EventType.TRADE);
        assertEquals(5, trade.quantity);

        MutableEvent restFill = firstOfType(emitted, EventType.ORDER_FILLED);
        assertTrue(restFill.isFullFill());

        // A new ask should fill the resting bid remainder
        engine.processSync(new SubmitOrderCommand("AAPL", Side.ASK, OrderType.LIMIT, 100, 5));
        List<MutableEvent> second = drain();
        MutableEvent t2 = firstOfType(second, EventType.TRADE);
        assertEquals(5, t2.quantity);
    }

    // -----------------------------------------------------------------------
    // Limit order — price does not cross
    // -----------------------------------------------------------------------

    @Test
    void limitOrder_noCross_restsInBook() {
        submitLimit(Side.ASK, 101, 10);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 10));
        List<MutableEvent> emitted = drain();

        assertEquals(1, emitted.size());
        assertEquals(EventType.ORDER_ACCEPTED, emitted.getFirst().eventType);
    }

    // -----------------------------------------------------------------------
    // Limit order — sweeps multiple price levels
    // -----------------------------------------------------------------------

    @Test
    void limitOrder_sweepsMultipleLevels() {
        submitLimit(Side.ASK, 100, 5);
        submitLimit(Side.ASK, 101, 5);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 102, 10));
        List<MutableEvent> emitted = drain();

        List<MutableEvent> trades = allOfType(emitted, EventType.TRADE);
        assertEquals(2, trades.size());
        assertEquals(100, trades.get(0).price);
        assertEquals(101, trades.get(1).price);

        long fullFills = allOfType(emitted, EventType.ORDER_FILLED).stream()
                .filter(MutableEvent::isFullFill).count();
        assertEquals(3, fullFills); // both resting + the aggressive
    }

    // -----------------------------------------------------------------------
    // Cancel
    // -----------------------------------------------------------------------

    @Test
    void cancel_restingOrder_emitsCancelledEvent() {
        long askId = submitLimit(Side.ASK, 100, 10);
        cancel(askId);
        List<MutableEvent> emitted = drain();

        assertEquals(1, emitted.size());
        MutableEvent cancelled = emitted.getFirst();
        assertEquals(EventType.ORDER_CANCELLED, cancelled.eventType);
        assertEquals(askId, cancelled.orderId);
        assertEquals(CancelReason.CANCELLED, cancelled.cancelReason);
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
        List<MutableEvent> emitted = drain();
        assertEquals(1, emitted.size());
        assertEquals(EventType.ORDER_ACCEPTED, emitted.getFirst().eventType);
    }

    // -----------------------------------------------------------------------
    // Modify — loses time priority
    // -----------------------------------------------------------------------

    @Test
    void modify_losingTimePriority() {
        long first  = submitLimit(Side.ASK, 100, 10);
        long second = submitLimit(Side.ASK, 100, 5);

        modify(first, 100, 10);
        drain();

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 5));
        List<MutableEvent> emitted = drain();

        MutableEvent trade = firstOfType(emitted, EventType.TRADE);
        assertEquals(second, trade.sellOrderId);
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
        List<MutableEvent> emitted = drain();

        assertFalse(allOfType(emitted, EventType.TRADE).isEmpty());
        assertTrue(allOfType(emitted, EventType.ORDER_FILLED).stream()
                .anyMatch(MutableEvent::isFullFill));
        assertTrue(allOfType(emitted, EventType.ORDER_CANCELLED).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Market order — insufficient liquidity
    // -----------------------------------------------------------------------

    @Test
    void marketOrder_partialFill_cancelRemainder() {
        submitLimit(Side.ASK, 100, 3);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.MARKET, 0, 10));
        List<MutableEvent> emitted = drain();

        MutableEvent trade = firstOfType(emitted, EventType.TRADE);
        assertEquals(3, trade.quantity);

        MutableEvent cancelled = firstOfType(emitted, EventType.ORDER_CANCELLED);
        assertEquals(CancelReason.INSUFFICIENT_LIQUIDITY, cancelled.cancelReason);
    }

    @Test
    void marketOrder_emptyBook_cancelImmediately() {
        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.MARKET, 0, 5));
        List<MutableEvent> emitted = drain();

        assertEquals(1, allOfType(emitted, EventType.ORDER_CANCELLED).size());
    }

    // -----------------------------------------------------------------------
    // Price-time priority — FIFO within a level
    // -----------------------------------------------------------------------

    @Test
    void fifoWithinPriceLevel() {
        long first  = submitLimit(Side.ASK, 100, 5);
        long second = submitLimit(Side.ASK, 100, 5);

        engine.processSync(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 5));
        List<MutableEvent> emitted = drain();

        MutableEvent trade = firstOfType(emitted, EventType.TRADE);
        assertEquals(first, trade.sellOrderId);
        assertNotEquals(second, trade.sellOrderId);
    }
}
