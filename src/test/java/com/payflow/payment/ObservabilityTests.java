package com.payflow.payment;

import com.payflow.payment.entity.Account;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.support.H2Tables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static com.payflow.payment.support.TestJwt.ALICE;
import static com.payflow.payment.support.TestJwt.admin;
import static com.payflow.payment.support.TestJwt.customer;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@AutoConfigureMetrics
class ObservabilityTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearDatabase() {
        H2Tables.truncateAll(jdbcTemplate);
    }

    @Test
    void probesArePublicAndHideDetailsFromAnonymousCallers() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mockMvc.perform(get("/actuator/health").with(admin()))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    @Test
    void prometheusExposesBoundedTransferMetricsToAdminsOnly() throws Exception {
        accountRepository.save(new Account(ALICE, "PF80001", new BigDecimal("10"), "INR"));
        accountRepository.save(new Account(ALICE, "PF80002", BigDecimal.ZERO, "INR"));
        for (String amount : new String[]{"1", "100"}) {
            mockMvc.perform(post("/api/v1/transfers").with(customer(ALICE))
                    .header("Idempotency-Key", "metrics-" + amount)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"fromAccountNumber\":\"PF80001\",\"toAccountNumber\":\"PF80002\","
                            + "\"amount\":" + amount + ",\"currency\":\"INR\"}"));
        }

        mockMvc.perform(get("/actuator/prometheus").with(customer(ALICE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(
                        "payflow_transfers_total{application=\"payment-service\",outcome=\"completed\",type=\"transfer\"}")))
                .andExpect(content().string(containsString("outcome=\"insufficient_funds\"")))
                .andExpect(content().string(containsString("payflow_transfer_duration_seconds")))
                .andExpect(content().string(containsString("payflow_outbox_pending")))
                .andExpect(content().string(containsString("hikaricp_connections")))
                // High-cardinality identifiers must never become labels.
                .andExpect(content().string(not(containsString("PF80001"))))
                .andExpect(content().string(not(containsString("metrics-1"))));
    }

    @Test
    void openApiDocumentIsPublishedWithBearerSecurity() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("PayFlow API"))
                .andExpect(jsonPath("$.paths['/api/v1/transfers'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/accounts/{accountNumber}/balance'].get").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
    }
}
