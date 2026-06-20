package org.greeps.clob;

import org.greeps.clob.command.SubmitOrderCommand;
import org.greeps.clob.core.OrderType;
import org.greeps.clob.core.Side;
import org.greeps.clob.engine.OrderRouter;
import org.greeps.clob.event.Event;
import org.greeps.clob.id.OrderIdGenerator;

import java.util.concurrent.LinkedTransferQueue;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        LinkedTransferQueue<Event> eventQueue = new LinkedTransferQueue<>();
        OrderRouter router = new OrderRouter(new OrderIdGenerator(), eventQueue);
        router.registerInstrument("AAPL");

        // Consumer thread — prints every event
        Thread consumer = Thread.ofPlatform().name("event-consumer").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Event event = eventQueue.take();
                    System.out.println(event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Resting ask, then aggressive bid that fully crosses it
        router.submit(new SubmitOrderCommand("AAPL", Side.ASK, OrderType.LIMIT, 100L, 10L));
        router.submit(new SubmitOrderCommand("AAPL", Side.BID, OrderType.LIMIT, 100L, 10L));

        Thread.sleep(200);
        router.shutdown();
        consumer.interrupt();
    }
}
