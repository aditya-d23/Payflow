package com.payflow.payment.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
        kafkaTemplate.send(record(message)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Sends the whole batch before waiting, so a batch costs roughly one
     * broker round trip instead of one per event. The idempotent producer
     * keeps per-partition order with several requests in flight.
     */
    @Override
    public Map<Long, Exception> publishAll(List<OutboxMessage> messages) {
        Map<Long, Exception> failures = new HashMap<>();
        List<OutboxMessage> sent = new ArrayList<>();
        List<CompletableFuture<?>> acks = new ArrayList<>();
        // One deadline for the whole batch, sends included: send() itself can
        // block for max.block.ms per record while metadata is unavailable, and
        // the batch must finish well inside the relay's lease.
        long deadline = System.nanoTime() + sendTimeout.toNanos();
        for (OutboxMessage message : messages) {
            if (System.nanoTime() - deadline >= 0) {
                failures.put(message.id(), new TimeoutException(
                        "Batch send deadline of " + sendTimeout + " elapsed before sending"));
                continue;
            }
            try {
                acks.add(kafkaTemplate.send(record(message)));
                sent.add(message);
            } catch (RuntimeException exception) {
                failures.put(message.id(), exception);
            }
        }
        for (int i = 0; i < sent.size(); i++) {
            try {
                long remaining = Math.max(deadline - System.nanoTime(), 0);
                acks.get(i).get(remaining, TimeUnit.NANOSECONDS);
            } catch (Exception exception) {
                failures.put(sent.get(i).id(), exception);
            }
        }
        return failures;
    }

    private ProducerRecord<String, String> record(OutboxMessage message) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                topics.forAggregate(message.aggregateType()),
                message.aggregateId(),
                message.payload());
        record.headers()
                .add(EVENT_ID_HEADER, bytes(message.eventId().toString()))
                .add(EVENT_TYPE_HEADER, bytes(message.eventType()))
                .add(SCHEMA_VERSION_HEADER, bytes(Integer.toString(message.schemaVersion())));
        return record;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
