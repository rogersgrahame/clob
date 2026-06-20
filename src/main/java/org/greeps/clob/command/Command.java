package org.greeps.clob.command;

public sealed interface Command
        permits SubmitOrderCommand, CancelOrderCommand, ModifyOrderCommand {}
