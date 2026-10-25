package com.payflow.payment.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param batchSize    events claimed per relay pass
 * @param lease        how long a claimed event is reserved for one relay
 * @param maxAttempts  publication attempts before an event is marked DEAD
 * @param initialBackoff delay after the first failure; doubles per attempt
 * @param maxBackoff   upper bound on the retry delay
 */
@ConfigurationProperties("payflow.outbox")
public record OutboxProperties(
        @DefaultValue("500") int batchSize,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("10") int maxAttempts,
        @DefaultValue("1s") Duration initialBackoff,
        @DefaultValue("5m") Duration maxBackoff
) {
    Duration backoffAfter(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 30);
        Duration delay = initialBackoff.multipliedBy(multiplier);
        return delay.compareTo(maxBackoff) > 0 ? maxBackoff : delay;
    }
}
