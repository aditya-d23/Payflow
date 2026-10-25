package com.payflow.payment.outbox;

import com.payflow.payment.service.SystemAccounts;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Sample downstream consumer that records a notification for each customer
 * account involved in a transfer.
 *
 * <p>Kafka may deliver an event more than once, so the event ID is recorded
 * in {@code processed_events} in the same database transaction as the side
 * effect. A redelivery finds the ID and does nothing. The offset is committed
 * after the listener returns, so a crash before commit means redelivery, not
 * loss.
 */
@Component
@ConditionalOnProperty(name = "payflow.kafka.enabled", havingValue = "true")
class TransferNotificationConsumer {

    static final String CONSUMER = "transfer-notifications";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectReader reader;

    TransferNotificationConsumer(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        // Money must never pass through a double.
        this.reader = jsonMapper.reader()
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @KafkaListener(
            topics = "${payflow.kafka.topics.transfers:payflow.transfers}",
            groupId = "payflow-" + CONSUMER)
    @Transactional
    public void onTransferEvent(String payload) {
        JsonNode envelope = reader.readTree(payload);
        UUID eventId = UUID.fromString(envelope.required("eventId").asString());
        String eventType = envelope.required("eventType").asString();
        if (!PayflowEvents.TransferCompleted.TYPE.equals(eventType)) {
            return;
        }
        int version = envelope.required("schemaVersion").asInt();
        if (version != PayflowEvents.TransferCompleted.VERSION) {
            throw new IllegalArgumentException("Unsupported " + eventType + " version " + version);
        }

        int inserted = jdbcTemplate.update("""
                INSERT INTO processed_events (consumer, event_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """, CONSUMER, eventId);
        if (inserted == 0) {
            return; // already applied
        }

        JsonNode data = envelope.required("data");
        String amount = data.required("amount").decimalValue().toPlainString()
                + " " + data.required("currency").asString();
        notify(eventId, data.required("fromAccountNumber").asString(), "Sent " + amount);
        notify(eventId, data.required("toAccountNumber").asString(), "Received " + amount);
    }

    private void notify(UUID eventId, String accountNumber, String message) {
        if (SystemAccounts.isReserved(accountNumber)) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO transfer_notifications (event_id, account_number, message)
                VALUES (?, ?, ?)
                """, eventId, accountNumber, message);
    }
}
