package org.greeps.clob.event;

public record OrderCancelledEvent(
        long orderId,
        String instrumentId,
        CancelReason reason,
        long timestamp
) implements Event {}
