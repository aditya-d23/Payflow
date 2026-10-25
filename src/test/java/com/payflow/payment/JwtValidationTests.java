package com.payflow.payment;

import com.payflow.payment.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real signed tokens through the real decoder (no MockMvc jwt() shortcut).
 * An authenticated call to an unknown account yields 404; a rejected token
 * yields 401, which is how each case is told apart.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class JwtValidationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void acceptsAValidToken() throws Exception {
        call(sign(TestJwt.TEST_SECRET, claims -> {})).andExpect(status().isNotFound());
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        call(sign(TestJwt.TEST_SECRET, claims -> claims
                .issuedAt(Instant.now().minusSeconds(7200))
                .expiresAt(Instant.now().minusSeconds(3600))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void rejectsTheWrongIssuer() throws Exception {
        call(sign(TestJwt.TEST_SECRET, claims -> claims.issuer("https://evil.example")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsTheWrongAudience() throws Exception {
        call(sign(TestJwt.TEST_SECRET, claims -> claims.audience(List.of("another-api"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() throws Exception {
        call(sign("a-completely-different-secret-0123456789", claims -> {}))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsATokenWithoutSubject() throws Exception {
        call(sign(TestJwt.TEST_SECRET, claims -> claims.subject("")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsSubjectsInTheReservedNamespace() throws Exception {
        // Such a subject could otherwise share pre-authentication idempotency keys.
        call(sign(TestJwt.TEST_SECRET, claims -> claims.subject("payflow:legacy")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnUnsignedToken() throws Exception {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String body = b64.encodeToString(("{\"sub\":\"alice\",\"iss\":\"payflow-local\","
                + "\"aud\":[\"payflow-api\"],\"exp\":" + (Instant.now().getEpochSecond() + 600) + "}")
                .getBytes(StandardCharsets.UTF_8));
        call(header + "." + body + ".").andExpect(status().isUnauthorized());
    }

    private ResultActions call(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/accounts/NOPE1")
                .header("Authorization", "Bearer " + token));
    }

    private static String sign(String secret, Consumer<JwtClaimsSet.Builder> customize) {
        SecretKeySpec key = new SecretKeySpec(
                secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("payflow-local")
                .audience(List.of("payflow-api"))
                .subject("alice")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(600));
        customize.accept(claims);
        return NimbusJwtEncoder.withSecretKey(key).build().encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }
}
