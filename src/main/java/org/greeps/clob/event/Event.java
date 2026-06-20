package org.greeps.clob.event;

public sealed interface Event
        permits OrderAcceptedEvent, OrderFilledEvent, OrderCancelledEvent, TradeEvent {}
