package com.payflow.payment.outbox;

import java.util.UUID;

/** An outbox row claimed by the relay for publication. */
public record OutboxMessage(
        long id,
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        String payload,
        int attempts
) {}
