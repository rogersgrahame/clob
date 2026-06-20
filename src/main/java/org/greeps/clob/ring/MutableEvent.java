package org.greeps.clob.ring;

import org.greeps.clob.core.OrderType;
import org.greeps.clob.core.Side;
import org.greeps.clob.event.CancelReason;

/**
 * Pre-allocated, mutable event slot used by EventRingBuffer.
 *
 * The volatile {@code sequence} field is the sole coordination mechanism between
 * producer and consumer — all field writes must complete before the volatile write
 * to {@code sequence}, giving the consumer a happens-before guarantee on all fields.
 *
 * Do not write to {@code sequence} outside of EventRingBuffer.
 */
public final class MutableEvent {

    /** Managed exclusively by EventRingBuffer. */
    public volatile long sequence;

    public EventType eventType;

    // Shared
    public long    orderId;
    public String  instrumentId;
    public long    timestamp;

    // ORDER_ACCEPTED
    public Side      side;
    public OrderType orderType;
    public long      price;     // also used for TRADE
    public long      quantity;  // also used for TRADE

    // ORDER_FILLED
    public long fillQty;
    public long remainingQty;
    public long fillPrice;

    // ORDER_CANCELLED
    public CancelReason cancelReason;

    // TRADE
    public long tradeId;
    public long buyOrderId;
    public long sellOrderId;

    MutableEvent(long initialSequence) {
        this.sequence = initialSequence;
    }

    public boolean isFullFill() {
        return remainingQty == 0;
    }
}
