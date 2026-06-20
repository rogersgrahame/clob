package org.greeps.clob;

import org.greeps.clob.command.SubmitOrderCommand;
import org.greeps.clob.core.OrderType;
import org.greeps.clob.core.Side;
import org.greeps.clob.engine.OrderRouter;
import org.greeps.clob.id.OrderIdGenerator;
import org.greeps.clob.ring.EventRingBuffer;
import org.greeps.clob.ring.MutableEvent;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        EventRingBuffer eventRing = new EventRingBuffer(4096);
        OrderRouter router = new OrderRouter(new OrderIdGenerator(), eventRing);
        router.registerInstrument("AAPL");

        // Consumer thread — spins on the event ring buffer
        Thread consumer = Thread.ofPlatform().name("event-consumer").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                MutableEvent event = eventRing.poll();
                if (event == null) { Thread.onSpinWait(); continue; }
                System.out.printf("%s orderId=%-4d instrument=%s%n",
                        event.eventType, event.orderId, event.instrumentId);
                eventRing.done(event);
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
