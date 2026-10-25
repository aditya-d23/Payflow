package com.payflow.payment.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuer     required {@code iss} value
 * @param audience   value that must appear in {@code aud}
 * @param jwkSetUri  identity provider's JWKS endpoint (asymmetric keys; preferred)
 * @param hmacSecret shared HS256 secret for local development when no JWKS is
 *                   available; at least 32 bytes, never committed
 */
@ConfigurationProperties("payflow.security.jwt")
public record JwtProperties(
        String issuer,
        String audience,
        String jwkSetUri,
        String hmacSecret
) {}
