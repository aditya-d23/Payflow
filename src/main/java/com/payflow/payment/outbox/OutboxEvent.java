package com.payflow.payment.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Written through JPA by {@link OutboxService}; claimed and updated through
 * SQL by {@link OutboxRelay}, which needs {@code FOR UPDATE SKIP LOCKED}.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    // Stamped by the database, not the JVM: the relay compares these with the
    // database's now(), and two clocks would make fresh events look "not yet due".
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(name = "available_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime availableAt;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    OutboxEvent(
            UUID eventId,
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            String payload) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.status = "PENDING";
    }
}
