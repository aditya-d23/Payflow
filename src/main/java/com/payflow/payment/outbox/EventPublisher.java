package com.payflow.payment.outbox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Delivers events to the message broker. A message counts as delivered only
 * once the broker has acknowledged it; anything else must be reported as a
 * failure, so the relay never marks an undelivered event as published.
 */
public interface EventPublisher {

    void publish(OutboxMessage message) throws Exception;

    /**
     * Publishes a batch and returns the failures keyed by outbox row ID; an
     * empty map means every message was acknowledged. The default publishes
     * sequentially. Implementations that can pipeline should override it.
     */
    default Map<Long, Exception> publishAll(List<OutboxMessage> messages) {
        Map<Long, Exception> failures = new HashMap<>();
        for (OutboxMessage message : messages) {
            try {
                publish(message);
            } catch (Exception exception) {
                failures.put(message.id(), exception);
            }
        }
        return failures;
    }
}
