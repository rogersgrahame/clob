package org.greeps.clob.command;

public record CancelOrderCommand(
        long orderId,
        String instrumentId
) implements Command {}
