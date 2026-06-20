package org.greeps.clob.command;

import org.greeps.clob.core.Side;
import org.greeps.clob.core.OrderType;

public record SubmitOrderCommand(
        String instrumentId,
        Side side,
        OrderType type,
        long price,
        long quantity
) implements Command {}
