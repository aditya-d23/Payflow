package com.payflow.payment.outbox;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxPropertiesTests {

    private final OutboxProperties properties = new OutboxProperties(
            100, Duration.ofSeconds(30), 10, Duration.ofSeconds(1), Duration.ofMinutes(5));

    @Test
    void backoffDoublesPerAttempt() {
        assertThat(properties.backoffAfter(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.backoffAfter(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.backoffAfter(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.backoffAfter(9)).isEqualTo(Duration.ofSeconds(256));
    }

    @Test
    void backoffIsCappedAndNeverOverflows() {
        assertThat(properties.backoffAfter(10)).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.backoffAfter(1_000)).isEqualTo(Duration.ofMinutes(5));
    }
}
