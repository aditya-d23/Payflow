package com.payflow.payment;

import com.payflow.payment.dto.CashMovementRequest;
import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.dto.TransferResponse;
import com.payflow.payment.exception.InsufficientBalanceException;
import com.payflow.payment.outbox.EventPublisher;
import com.payflow.payment.outbox.OutboxMessage;
import com.payflow.payment.outbox.OutboxRelay;
import com.payflow.payment.security.Caller;
import com.payflow.payment.service.AccountService;
import com.payflow.payment.service.TransferService;
import com.payflow.payment.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = {
        "payflow.outbox.max-attempts=3",
        "payflow.outbox.initial-backoff=0s",
        "payflow.outbox.batch-size=5"
})
class OutboxPostgresIT extends PostgresIntegrationTest {

    private static final Caller ALICE = Caller.customer("alice");
    private static final Caller ADMIN = Caller.admin("ops-admin");

    @Autowired
    private AccountService accountService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private RecordingPublisher publisher;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }

    static class RecordingPublisher implements EventPublisher {
        final List<OutboxMessage> published = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresRemaining = new AtomicInteger();

        @Override
        public void publish(OutboxMessage message) {
            if (failuresRemaining.getAndUpdate(n -> Math.max(n - 1, 0)) > 0) {
                throw new IllegalStateException("broker unavailable");
            }
            published.add(message);
        }
    }

    @BeforeEach
    void resetPublisher() {
        publisher.published.clear();
        publisher.failuresRemaining.set(0);
    }

    @Test
    void aCommittedTransferWritesExactlyOneEventInTheSameTransaction() {
        open("PG80001");
        open("PG80002");
        deposit("PG80001", "50.00");

        TransferResponse transfer = transferService.transfer(new TransferRequest(
                "PG80001", "PG80002", new BigDecimal("20.00"), "INR"), "evt-1", ALICE)
                .transfer();

        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT event_type, aggregate_id, payload FROM outbox_events "
                        + "WHERE aggregate_id = ?", transfer.transactionReference().toString());
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("event_type", "TransferCompleted");
            assertThat((String) event.get("payload"))
                    .contains("\"schemaVersion\":1")
                    .contains("\"fromAccountNumber\":\"PG80001\"")
                    .contains("\"amount\":20.0000");
        });
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'AccountCreated'"))
                .isEqualTo(2);
    }

    @Test
    void aRolledBackTransferWritesNoEvent() {
        open("PG80001");
        open("PG80002");

        assertThatThrownBy(() -> transferService.transfer(new TransferRequest(
                "PG80001", "PG80002", BigDecimal.ONE, "INR"), "evt-nsf", ALICE))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'TransferCompleted'"))
                .isZero();
    }

    @Test
    void relayPublishesInOrderAndMarksEventsPublished() {
        open("PG80001");
        open("PG80002");

        assertThat(relay.relayPending()).isEqualTo(2);

        assertThat(publisher.published).extracting(OutboxMessage::aggregateId)
                .containsExactly("PG80001", "PG80002");
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED' "
                + "AND published_at IS NOT NULL AND locked_until IS NULL")).isEqualTo(2);
        assertThat(relay.relayPending()).isZero();
    }

    @Test
    void relayDrainsMoreThanOneBatch() {
        for (int i = 0; i < 12; i++) {
            open("PG8%04d".formatted(i));
        }
        assertThat(relay.relayPending()).isEqualTo(12);
        assertThat(publisher.published).hasSize(12);
    }

    @Test
    void failedPublicationIsRetriedThenSucceeds() {
        open("PG80001");
        publisher.failuresRemaining.set(2);

        relay.relayBatch();
        relay.relayBatch();
        assertThat(publisher.published).isEmpty();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, attempts, last_error FROM outbox_events");
        assertThat(row).containsEntry("status", "PENDING").containsEntry("attempts", 2);
        assertThat((String) row.get("last_error")).contains("broker unavailable");

        relay.relayBatch();
        assertThat(publisher.published).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM outbox_events", String.class))
                .isEqualTo("PUBLISHED");
    }

    @Test
    void eventsThatKeepFailingAreMarkedDead() {
        open("PG80001");
        publisher.failuresRemaining.set(100);

        for (int i = 0; i < 5; i++) {
            relay.relayBatch();
        }

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, attempts FROM outbox_events");
        assertThat(row).containsEntry("status", "DEAD").containsEntry("attempts", 3);
    }

    @Test
    void anExpiredLeaseFromACrashedRelayIsReclaimed() {
        open("PG80001");
        // A relay claimed the event and died before marking it.
        jdbcTemplate.update("UPDATE outbox_events SET locked_until = now() + interval '1 hour', attempts = 1");
        assertThat(relay.relayBatch()).isZero();

        jdbcTemplate.update("UPDATE outbox_events SET locked_until = now() - interval '1 second'");
        assertThat(relay.relayBatch()).isEqualTo(1);
        assertThat(publisher.published).hasSize(1);
    }

    @Test
    void concurrentRelaysNeverClaimTheSameEvent() {
        for (int i = 0; i < 40; i++) {
            open("PG8%04d".formatted(i));
        }

        List<CompletableFuture<Integer>> relays = List.of(
                CompletableFuture.supplyAsync(relay::relayPending),
                CompletableFuture.supplyAsync(relay::relayPending),
                CompletableFuture.supplyAsync(relay::relayPending),
                CompletableFuture.supplyAsync(relay::relayPending));
        int total = relays.stream().mapToInt(CompletableFuture::join).sum();

        assertThat(total).isEqualTo(40);
        assertThat(publisher.published).extracting(OutboxMessage::eventId)
                .doesNotHaveDuplicates()
                .hasSize(40);
    }

    private void open(String accountNumber) {
        accountService.createAccount(new CreateAccountRequest(accountNumber, "INR"), ALICE);
    }

    private void deposit(String accountNumber, String amount) {
        transferService.deposit(new CashMovementRequest(
                accountNumber, new BigDecimal(amount), "INR"), "dep-" + UUID.randomUUID(), ADMIN);
    }

    private long count(String sql) {
        return jdbcTemplate.queryForObject(sql, Long.class);
    }
}
