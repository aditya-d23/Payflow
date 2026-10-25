package com.payflow.payment;

import com.payflow.payment.dto.CashMovementRequest;
import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.dto.TransferResponse;
import com.payflow.payment.security.Caller;
import com.payflow.payment.service.AccountService;
import com.payflow.payment.service.TransferService;
import com.payflow.payment.support.PostgresIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end: transfer, then outbox, then the scheduled relay, then Kafka,
 * then the deduplicating consumer. Also checks duplicate delivery and
 * dead-lettering.
 */
@TestPropertySource(properties = {
        "payflow.kafka.enabled=true",
        "payflow.scheduling.enabled=true",
        "payflow.outbox.relay-interval-ms=100"
})
class KafkaOutboxPostgresIT extends PostgresIntegrationTest {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.2.0");

    static {
        KAFKA.start();
    }

    private static final Caller ALICE = Caller.customer("alice");
    private static final Caller ADMIN = Caller.admin("ops-admin");

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    void aCompletedTransferReachesTheConsumerExactlyOnceDespiteRedelivery() throws Exception {
        accountService.createAccount(new CreateAccountRequest("PG90001", "INR"), ALICE);
        accountService.createAccount(new CreateAccountRequest("PG90002", "INR"), ALICE);
        transferService.deposit(new CashMovementRequest(
                "PG90001", new BigDecimal("100.00"), "INR"), "k-dep", ADMIN);
        TransferResponse transfer = transferService.transfer(new TransferRequest(
                "PG90001", "PG90002", new BigDecimal("25.00"), "INR"), "k-transfer", ALICE)
                .transfer();

        awaitTrue(() -> notifications("PG90002") == 1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT message FROM transfer_notifications WHERE account_number = 'PG90001' ORDER BY id",
                // Different transfers are different aggregates (Kafka keys), so
                // only per-aggregate order is guaranteed, not order across them.
                String.class)).containsExactlyInAnyOrder("Received 100.0000 INR", "Sent 25.0000 INR");
        awaitTrue(() -> count("SELECT COUNT(*) FROM outbox_events WHERE status <> 'PUBLISHED'") == 0);

        // Simulate the at-least-once redelivery a relay crash would cause.
        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM outbox_events WHERE aggregate_id = ?",
                String.class, transfer.transactionReference().toString());
        kafkaTemplate.send("payflow.transfers", transfer.transactionReference().toString(), payload)
                .get();
        String marker = "PG9" + UUID.randomUUID().toString().substring(0, 6);
        sendTransferEvent(marker);
        awaitTrue(() -> notifications(marker) == 1);

        assertThat(notifications("PG90002")).isEqualTo(1);
    }

    @Test
    void publishedRecordsAreKeyedByAggregateAndCarryEventHeaders() {
        accountService.createAccount(new CreateAccountRequest("PG90010", "INR"), ALICE);

        ConsumerRecord<String, String> record = pollOne("payflow.accounts",
                r -> "PG90010".equals(r.key()));

        assertThat(record.value()).contains("\"eventType\":\"AccountCreated\"");
        assertThat(header(record, "payflow-event-type")).isEqualTo("AccountCreated");
        assertThat(header(record, "payflow-schema-version")).isEqualTo("1");
        assertThat(UUID.fromString(header(record, "payflow-event-id"))).isNotNull();
    }

    @Test
    void aPoisonMessageIsDeadLetteredWithoutBlockingThePartition() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        kafkaTemplate.send("payflow.transfers", key, "{not json").get();
        String marker = "PG9" + UUID.randomUUID().toString().substring(0, 6);
        sendTransferEvent(marker);

        ConsumerRecord<String, String> dead = pollOne("payflow.transfers.DLT",
                r -> key.equals(r.key()));
        assertThat(dead.value()).isEqualTo("{not json");
        awaitTrue(() -> notifications(marker) == 1);
    }

    private void sendTransferEvent(String toAccount) throws Exception {
        String payload = """
                {"eventId":"%s","eventType":"TransferCompleted","schemaVersion":1,
                 "aggregateType":"transfer","aggregateId":"x","occurredAt":"2026-01-01T00:00:00Z",
                 "data":{"fromAccountNumber":"SYS-FUNDING-INR","toAccountNumber":"%s",
                         "amount":1,"currency":"INR"}}
                """.formatted(UUID.randomUUID(), toAccount);
        kafkaTemplate.send("payflow.transfers", toAccount, payload).get();
    }

    private ConsumerRecord<String, String> pollOne(
            String topic, java.util.function.Predicate<ConsumerRecord<String, String>> match) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(30);
            List<ConsumerRecord<String, String>> seen = new ArrayList<>();
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(250))) {
                    seen.add(record);
                    if (match.test(record)) {
                        return record;
                    }
                }
            }
            throw new AssertionError("No matching record on " + topic + " among " + seen.size());
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private long notifications(String accountNumber) {
        return count("SELECT COUNT(*) FROM transfer_notifications WHERE account_number = '"
                + accountNumber + "'");
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(30);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within 30s");
            }
            Thread.sleep(100);
        }
    }
}
