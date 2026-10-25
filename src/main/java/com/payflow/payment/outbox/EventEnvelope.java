package com.payflow.payment.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wire format of every PayFlow event. Consumers deduplicate on
 * {@code eventId} and dispatch on {@code eventType} + {@code schemaVersion}.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        OffsetDateTime occurredAt,
        T data
) {}
