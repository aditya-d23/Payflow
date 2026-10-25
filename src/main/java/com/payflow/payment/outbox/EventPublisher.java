package com.payflow.payment.outbox;

/**
 * Delivers one event to the message broker. Must return only after the broker
 * has acknowledged the write, and throw otherwise, so the relay never marks
 * an undelivered event as published.
 */
public interface EventPublisher {

    void publish(OutboxMessage message) throws Exception;
}
