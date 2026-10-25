package com.payflow.payment.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TransferFingerprintTests {

    @Test
    void isDeterministicHexSha256() {
        String first = TransferService.fingerprint("A", "B", "10.0000", "INR");
        assertThat(first)
                .isEqualTo(TransferService.fingerprint("A", "B", "10.0000", "INR"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void everyFieldContributes() {
        String base = TransferService.fingerprint("A", "B", "10.0000", "INR");
        assertThat(TransferService.fingerprint("X", "B", "10.0000", "INR")).isNotEqualTo(base);
        assertThat(TransferService.fingerprint("A", "X", "10.0000", "INR")).isNotEqualTo(base);
        assertThat(TransferService.fingerprint("A", "B", "10.0100", "INR")).isNotEqualTo(base);
        assertThat(TransferService.fingerprint("A", "B", "10.0000", "USD")).isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesCannotBeShifted() {
        assertThat(TransferService.fingerprint("AB", "C", "1.0000", "INR"))
                .isNotEqualTo(TransferService.fingerprint("A", "BC", "1.0000", "INR"));
    }

    @Test
    void directionMatters() {
        assertThat(TransferService.fingerprint("A", "B", "1.0000", "INR"))
                .isNotEqualTo(TransferService.fingerprint("B", "A", "1.0000", "INR"));
    }
}
