package com.payflow.payment.outbox;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final JsonMapper jsonMapper;

    public OutboxService(OutboxEventRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Records an event in the caller's transaction, so it exists if and only
     * if the business change commits.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID record(
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            Object data) {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String payload = jsonMapper.writeValueAsString(new EventEnvelope<>(
                eventId, eventType, schemaVersion, aggregateType, aggregateId, now, data));
        repository.save(new OutboxEvent(
                eventId, eventType, schemaVersion, aggregateType, aggregateId, payload));
        return eventId;
    }
}
