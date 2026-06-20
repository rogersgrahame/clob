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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderRouterTest {

    private EventRingBuffer eventRing;
    private OrderRouter     router;

    @BeforeEach
    void setUp() {
        eventRing = new EventRingBuffer(256);
        router    = new OrderRouter(new OrderIdGenerator(), eventRing);
        router.registerInstrument("AAPL");
        router.registerInstrument("TSLA");
    }

    @AfterEach
    void tearDown() {
        router.shutdown();
    }

    // --- helpers ---

    /**
     * Snapshot of a MutableEvent's fields — copied before the slot is released,
     * since slot fields may be overwritten once done() is called.
     */
    record EventSnapshot(EventType type, long orderId, String instrumentId,
                         CancelReason cancelReason, long sellOrderId) {}

    /**
     * Spin-poll until {@code count} events arrive or a 2s deadline is exceeded.
     * Releases each slot immediately after snapshotting.
     */
    private List<EventSnapshot> awaitEvents(int count) {
        List<EventSnapshot> snapshots = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 2_000;
        while (snapshots.size() < count && System.currentTimeMillis() < deadline) {
            MutableEvent slot = eventRing.poll();
            if (slot == null) { Thread.onSpinWait(); continue; }
            snapshots.add(new EventSnapshot(
                    slot.eventType, slot.orderId, slot.instrumentId,
                    slot.cancelReason, slot.sellOrderId));
            eventRing.done(slot);
        }
        return snapshots;
    }

    /**
     * Submit a resting limit order and return the accepted orderId.
     * Uses a non-crossing price so the order always rests in the book.
     */
    private long submitResting(String instrument, Side side, long price, long qty) {
        router.submit(new SubmitOrderCommand(instrument, side, OrderType.LIMIT, price, qty));
        List<EventSnapshot> events = awaitEvents(1);
        assertEquals(EventType.ORDER_ACCEPTED, events.getFirst().type());
        return events.getFirst().orderId();
    }

    /**
     * Submits a sentinel order to the same instrument after a no-op operation.
     * If we receive only the sentinel's ORDER_ACCEPTED, the prior operation emitted nothing.
     */
    private void assertNoEventsEmitted(String instrument) {
        router.submit(new SubmitOrderCommand(instrument, Side.ASK, OrderType.LIMIT, 999_999, 1));
        List<EventSnapshot> events = awaitEvents(1);
        assertEquals(1, events.size());
        assertEquals(EventType.ORDER_ACCEPTED, events.getFirst().type());
    }

    // -----------------------------------------------------------------------
    // Cancel
    // -----------------------------------------------------------------------

    @Test
    void cancel_restingOrder_emitsCancelledEvent() {
        long askId = submitResting("AAPL", Side.ASK, 100, 10);

        router.cancel(new CancelOrderCommand(askId, "AAPL"));
        List<EventSnapshot> events = awaitEvents(1);

        assertEquals(1, events.size());
        EventSnapshot cancelled = events.getFirst();
        assertEquals(EventType.ORDER_CANCELLED, cancelled.type());
        assertEquals(askId, cancelled.orderId());
        assertEquals(CancelReason.CANCELLED, cancelled.cancelReason());
    }

    @Test
    void cancel_unknownOrder_noEventsEmitted() {
        router.cancel(new CancelOrderCommand(999L, "AAPL"));
        assertNoEventsEmitted("AAPL");
    }

    @Test
    void cancel_preventsSubsequentMatch() {
        long askId = submitResting("AAPL", Side.ASK, 100, 10);

        router.cancel(new CancelOrderCommand(askId, "AAPL"));
        awaitEvents(1); // consume ORDER_CANCELLED

        // crossing BID should find no resting order — just gets accepted and rests
        router.submit(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100, 10));
        List<EventSnapshot> events = awaitEvents(1);
        assertEquals(1, events.size());
        assertEquals(EventType.ORDER_ACCEPTED, events.getFirst().type());
    }

    // -----------------------------------------------------------------------
    // Modify
    // -----------------------------------------------------------------------

    @Test
    void modify_emitsModifiedEventWithSameOrderId() {
        long originalId = submitResting("AAPL", Side.ASK, 100, 10);

        router.modify(new ModifyOrderCommand(originalId, "AAPL", 101, 5));
        List<EventSnapshot> events = awaitEvents(1);

        assertEquals(1, events.size());
        EventSnapshot modified = events.getFirst();
        assertEquals(EventType.ORDER_MODIFIED, modified.type());
        assertEquals(originalId, modified.orderId()); // same orderId retained
    }

    @Test
    void modify_unknownOrder_noEventsEmitted() {
        router.modify(new ModifyOrderCommand(999L, "AAPL", 100, 10));
        assertNoEventsEmitted("AAPL");
    }

    @Test
    void modify_newPriceCrossesBook_triggersMatch() {
        // resting BID at 100, resting ASK at 105
        submitResting("AAPL", Side.BID, 100, 10);
        long askId = submitResting("AAPL", Side.ASK, 105, 10);

        // modify the ASK down to 100 — now it crosses the BID
        router.modify(new ModifyOrderCommand(askId, "AAPL", 100, 10));

        // expect: ORDER_MODIFIED + TRADE + 2x ORDER_FILLED
        List<EventSnapshot> events = awaitEvents(4);
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.TRADE));
        assertTrue(events.stream().anyMatch(e -> e.type() == EventType.ORDER_MODIFIED
                && e.orderId() == askId));
    }

    // -----------------------------------------------------------------------
    // Routing — multiple instruments
    // -----------------------------------------------------------------------

    @Test
    void cancel_routedToCorrectInstrument() {
        long aaplId = submitResting("AAPL", Side.ASK, 100, 10);
        long tslaId = submitResting("TSLA", Side.ASK, 200, 5);

        // cancel only the AAPL order
        router.cancel(new CancelOrderCommand(aaplId, "AAPL"));
        List<EventSnapshot> events = awaitEvents(1);

        assertEquals(1, events.size());
        assertEquals(EventType.ORDER_CANCELLED, events.getFirst().type());
        assertEquals(aaplId, events.getFirst().orderId());
        assertEquals("AAPL", events.getFirst().instrumentId());

        // TSLA order should still be in its book — a crossing BID should match
        router.submit(new SubmitOrderCommand("TSLA", Side.BID, OrderType.LIMIT, 200, 5));
        List<EventSnapshot> tslaEvents = awaitEvents(3); // ORDER_ACCEPTED + TRADE + 2x ORDER_FILLED
        assertTrue(tslaEvents.stream().anyMatch(e -> e.type() == EventType.TRADE));
    }

    @Test
    void unknownInstrument_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () ->
                router.submit(new SubmitOrderCommand("UNKNOWN", Side.BID, OrderType.LIMIT, 100, 1)));
    }
}
