package com.payflow.payment.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Used when Kafka is disabled (local development without a broker). Logs
 * metadata only; payloads may contain account numbers.
 */
@Component
@ConditionalOnProperty(name = "payflow.kafka.enabled", havingValue = "false", matchIfMissing = true)
class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxMessage message) {
        log.info("Outbox event {} v{} {} (Kafka disabled; not delivered to a broker)",
                message.eventType(), message.schemaVersion(), message.eventId());
    }
}
