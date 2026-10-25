package com.payflow.payment.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Moves committed outbox events to the broker.
 *
 * <ol>
 *   <li>Claim a batch in one short statement: {@code FOR UPDATE SKIP LOCKED}
 *       lets several relays (or application instances) run without blocking
 *       each other, and the lease keeps a claimed row away from them.</li>
 *   <li>Publish each event <em>outside</em> any database transaction, so a
 *       slow broker never holds a connection or row lock.</li>
 *   <li>Mark it published, or schedule a retry with exponential backoff, or
 *       mark it DEAD after {@code maxAttempts}.</li>
 * </ol>
 *
 * Delivery is at-least-once: a crash between publishing and marking means the
 * event is published again once the lease expires. Consumers deduplicate.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbcTemplate;
    private final EventPublisher publisher;
    private final OutboxProperties properties;
    private final Counter published;
    private final Counter failed;
    private final Counter dead;

    public OutboxRelay(
            JdbcTemplate jdbcTemplate,
            EventPublisher publisher,
            OutboxProperties properties,
            MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.publisher = publisher;
        this.properties = properties;
        this.published = outcome(meterRegistry, "published");
        this.failed = outcome(meterRegistry, "failed");
        this.dead = outcome(meterRegistry, "dead");
        Gauge.builder("payflow.outbox.pending", this, OutboxRelay::pendingCount)
                .description("Outbox events not yet published")
                .register(meterRegistry);
    }

    private static Counter outcome(MeterRegistry registry, String outcome) {
        return Counter.builder("payflow.outbox.events")
                .description("Outbox publication attempts by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${payflow.outbox.relay-interval-ms:500}")
    public void relayScheduled() {
        try {
            relayPending();
        } catch (RuntimeException exception) {
            // The database may be briefly unavailable; the next tick retries.
            log.warn("Outbox relay pass failed: {}", exception.toString());
        }
    }

    /** Relays until no due events remain. Returns the number published. */
    public int relayPending() {
        int total = 0;
        int batch;
        do {
            batch = relayBatch();
            total += batch;
        } while (batch == properties.batchSize());
        return total;
    }

    /** Claims and processes one batch. Returns the number of events claimed. */
    public int relayBatch() {
        List<OutboxMessage> claimed = claim();
        if (claimed.isEmpty()) {
            return 0;
        }
        Map<Long, Exception> failures = publisher.publishAll(claimed);
        markPublished(claimed.stream()
                .map(OutboxMessage::id)
                .filter(id -> !failures.containsKey(id))
                .toArray(Long[]::new));
        for (OutboxMessage message : claimed) {
            Exception failure = failures.get(message.id());
            if (failure != null) {
                markFailed(message, failure);
            }
        }
        return claimed.size();
    }

    private List<OutboxMessage> claim() {
        List<OutboxMessage> claimed = jdbcTemplate.query("""
                UPDATE outbox_events
                SET locked_until = now() + make_interval(secs => ?),
                    attempts = attempts + 1
                WHERE id IN (
                    SELECT id FROM outbox_events
                    WHERE status = 'PENDING'
                      AND available_at <= now()
                      AND (locked_until IS NULL OR locked_until < now())
                    ORDER BY id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                RETURNING id, event_id, event_type, schema_version,
                          aggregate_type, aggregate_id, payload, attempts
                """, (rs, row) -> new OutboxMessage(
                rs.getLong("id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getInt("schema_version"),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getString("payload"),
                rs.getInt("attempts")),
                (double) properties.lease().toMillis() / 1000.0,
                properties.batchSize());
        // RETURNING has no defined order; publish oldest first.
        return claimed.stream().sorted(Comparator.comparingLong(OutboxMessage::id)).toList();
    }

    private void markPublished(Long[] ids) {
        if (ids.length == 0) {
            return;
        }
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    UPDATE outbox_events
                    SET status = 'PUBLISHED', published_at = now(),
                        locked_until = NULL, last_error = NULL
                    WHERE id = ANY(?)
                    """);
            statement.setArray(1, connection.createArrayOf("bigint", ids));
            return statement;
        });
        published.increment(ids.length);
    }

    /**
     * Fenced on the claim: every claim increments {@code attempts}, so a relay
     * whose lease expired (and whose row another relay has since claimed or
     * published) matches nothing and cannot clobber the newer state.
     */
    private void markFailed(OutboxMessage message, Exception exception) {
        String error = truncate(exception.getClass().getName() + ": " + exception.getMessage());
        if (message.attempts() >= properties.maxAttempts()) {
            int updated = jdbcTemplate.update("""
                    UPDATE outbox_events
                    SET status = 'DEAD', locked_until = NULL, last_error = ?
                    WHERE id = ? AND status = 'PENDING' AND attempts = ?
                    """, error, message.id(), message.attempts());
            if (updated == 0) {
                return; // lease lost; the current claimant decides
            }
            dead.increment();
            log.error("Outbox event {} marked DEAD after {} attempts: {}",
                    message.eventId(), message.attempts(), error);
            return;
        }
        double delaySeconds = properties.backoffAfter(message.attempts()).toMillis() / 1000.0;
        int updated = jdbcTemplate.update("""
                UPDATE outbox_events
                SET available_at = now() + make_interval(secs => ?),
                    locked_until = NULL, last_error = ?
                WHERE id = ? AND status = 'PENDING' AND attempts = ?
                """, delaySeconds, error, message.id(), message.attempts());
        if (updated == 0) {
            return; // lease lost; the current claimant decides
        }
        failed.increment();
        log.warn("Outbox event {} attempt {} failed; retrying in {}s: {}",
                message.eventId(), message.attempts(), delaySeconds, error);
    }

    private double pendingCount() {
        try {
            Long pending = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'", Long.class);
            return pending == null ? 0 : pending;
        } catch (RuntimeException exception) {
            return Double.NaN;
        }
    }

    private static String truncate(String value) {
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
