package com.payflow.payment.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Publishes to one topic per aggregate type, keyed by aggregate ID so all
 * events for one transfer or account land on the same partition in order.
 * Blocks until the broker acknowledges (acks=all) or the timeout elapses.
 */
@Component
@ConditionalOnProperty(name = "payflow.kafka.enabled", havingValue = "true")
class KafkaEventPublisher implements EventPublisher {

    static final String EVENT_ID_HEADER = "payflow-event-id";
    static final String EVENT_TYPE_HEADER = "payflow-event-type";
    static final String SCHEMA_VERSION_HEADER = "payflow-schema-version";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final KafkaTopics topics;
    private final Duration sendTimeout;

    KafkaEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            KafkaTopics topics,
            @Value("${payflow.kafka.send-timeout:10s}") Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.sendTimeout = sendTimeout;
    }

    @Override
    public void publish(OutboxMessage message) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topics.forAggregate(message.aggregateType()),
                message.aggregateId(),
                message.payload());
        record.headers()
                .add(EVENT_ID_HEADER, bytes(message.eventId().toString()))
                .add(EVENT_TYPE_HEADER, bytes(message.eventType()))
                .add(SCHEMA_VERSION_HEADER, bytes(Integer.toString(message.schemaVersion())));
        kafkaTemplate.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
