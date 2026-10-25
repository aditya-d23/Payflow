-- Transactional outbox: events are inserted in the same transaction as the
-- business change and relayed to Kafka afterwards (at-least-once).
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(100) NOT NULL,
    schema_version INTEGER NOT NULL,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Lease held by a relay while it publishes outside any transaction. A
    -- relay that crashes mid-publish simply lets the lease expire.
    locked_until TIMESTAMPTZ,
    published_at TIMESTAMPTZ,

    CONSTRAINT chk_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD'))
);

CREATE INDEX idx_outbox_events_due
    ON outbox_events(available_at, id)
    WHERE status = 'PENDING';

-- Consumer-side deduplication: a consumer records each event it has applied
-- in the same transaction as the side effect.
CREATE TABLE processed_events (
    consumer VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (consumer, event_id)
);

-- Side effect of the sample notification consumer.
CREATE TABLE transfer_notifications (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    account_number VARCHAR(30) NOT NULL,
    message VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_transfer_notifications_account
    ON transfer_notifications(account_number, id);
