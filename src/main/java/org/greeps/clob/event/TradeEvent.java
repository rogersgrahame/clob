package org.greeps.clob.event;

public record TradeEvent(
        long tradeId,
        String instrumentId,
        long buyOrderId,
        long sellOrderId,
        long price,
        long quantity,
        long timestamp
) implements Event {}
