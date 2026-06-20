package org.greeps.clob.command;

public record ModifyOrderCommand(
        long orderId,
        String instrumentId,
        long newPrice,
        long newQuantity
) implements Command {}
