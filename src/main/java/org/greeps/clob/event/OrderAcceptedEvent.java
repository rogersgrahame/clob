package org.greeps.clob.event;

import org.greeps.clob.core.Side;
import org.greeps.clob.core.OrderType;

public record OrderAcceptedEvent(
        long orderId,
        String instrumentId,
        Side side,
        OrderType type,
        long price,
        long quantity,
        long timestamp
) implements Event {}
