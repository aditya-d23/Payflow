package com.payflow.payment;

import com.payflow.payment.entity.Account;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.support.H2Tables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;

import static com.payflow.payment.support.TestJwt.ALICE;
import static com.payflow.payment.support.TestJwt.BOB;
import static com.payflow.payment.support.TestJwt.admin;
import static com.payflow.payment.support.TestJwt.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Who may do what. Resources a caller cannot see are reported as 404. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SecurityApiTests {

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
    void requestsWithoutATokenAreUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/accounts/PF70001"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").exists());
        mockMvc.perform(post("/api/v1/transfers").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsPublicButOtherActuatorEndpointsAreAdminOnly() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info").with(customer(ALICE)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void customersSeeOnlyTheirOwnAccounts() throws Exception {
        owned(ALICE, "PF70001", "50.00");

        mockMvc.perform(get("/api/v1/accounts/PF70001").with(customer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerId").value(ALICE));
        for (String path : new String[]{"/api/v1/accounts/PF70001",
                "/api/v1/accounts/PF70001/balance",
                "/api/v1/accounts/PF70001/transactions"}) {
            mockMvc.perform(get(path).with(customer(BOB)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        }
        mockMvc.perform(get("/api/v1/accounts/PF70001/balance").with(admin()))
                .andExpect(status().isOk());
    }

    @Test
    void customersCannotSpendFromAnotherCustomersAccount() throws Exception {
        owned(ALICE, "PF70001", "50.00");
        owned(BOB, "PF70002", "0.00");

        transfer(customer(BOB), "PF70001", "PF70002", "10", "steal")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        // Administrators service accounts but do not initiate customer transfers.
        transfer(admin(), "PF70001", "PF70002", "10", "admin-steal")
                .andExpect(status().isNotFound());

        assertBalance("PF70001", "50.00");
        assertBalance("PF70002", "0.00");
    }

    @Test
    void customersCanPayAnotherCustomer() throws Exception {
        owned(ALICE, "PF70001", "50.00");
        owned(BOB, "PF70002", "0.00");

        transfer(customer(ALICE), "PF70001", "PF70002", "10", "pay-bob")
                .andExpect(status().isCreated());
        assertBalance("PF70002", "10.00");
    }

    @Test
    void transfersAreVisibleOnlyToTheirParticipants() throws Exception {
        owned(ALICE, "PF70001", "50.00");
        owned(BOB, "PF70002", "0.00");
        owned("carol", "PF70003", "0.00");
        String location = transfer(customer(ALICE), "PF70001", "PF70002", "5", "visible")
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location).with(customer(ALICE))).andExpect(status().isOk());
        mockMvc.perform(get(location).with(customer(BOB))).andExpect(status().isOk());
        mockMvc.perform(get(location).with(customer("carol")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
        mockMvc.perform(get(location).with(admin())).andExpect(status().isOk());
    }

    @Test
    void idempotencyKeysAreScopedToTheCaller() throws Exception {
        owned(ALICE, "PF70001", "50.00");
        owned(BOB, "PF70002", "50.00");

        String aliceRef = transfer(customer(ALICE), "PF70001", "PF70002", "10", "same-key")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader("Location");
        // Bob's identical key is a different request in his own namespace:
        // neither a replay of Alice's transfer nor a conflict that leaks it.
        String bobRef = transfer(customer(BOB), "PF70002", "PF70001", "3", "same-key")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getHeader("Location");

        assertThat(bobRef).isNotEqualTo(aliceRef);
        assertBalance("PF70001", "43.00");
        assertBalance("PF70002", "57.00");
    }

    @Test
    void fundingStatusOwnershipAndReconciliationRequireTheAdminScope() throws Exception {
        owned(ALICE, "PF70001", "0.00");
        RequestPostProcessor alice = customer(ALICE);

        mockMvc.perform(post("/api/v1/deposits").with(alice).header("Idempotency-Key", "d")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"PF70001\",\"amount\":100,\"currency\":\"INR\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/withdrawals").with(alice).header("Idempotency-Key", "w")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"PF70001\",\"amount\":1,\"currency\":\"INR\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(patch("/api/v1/accounts/PF70001/status").with(alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FROZEN\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/accounts/PF70001/owner").with(alice)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":\"mallory\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/reconciliation").with(alice))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/reconciliation").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consistent").exists());
        assertBalance("PF70001", "0.00");
    }

    @Test
    void onlyAdministratorsOpenAccountsForSomeoneElse() throws Exception {
        String forBob = "{\"accountNumber\":\"PF70009\",\"currency\":\"INR\",\"ownerId\":\"bob\"}";

        mockMvc.perform(post("/api/v1/accounts").with(customer(ALICE))
                        .contentType(MediaType.APPLICATION_JSON).content(forBob))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/accounts").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(forBob))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerId").value(BOB));
    }

    @Test
    void blankOwnerIdsAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/accounts").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"PF70010\",\"currency\":\"INR\",\"ownerId\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void administratorsCanAssignOwnersToPreAuthenticationAccounts() throws Exception {
        accountRepository.save(new Account("PF70001", BigDecimal.TEN, "INR"));
        mockMvc.perform(get("/api/v1/accounts/PF70001").with(customer(ALICE)))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/v1/accounts/PF70001/owner").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ownerId\":\"alice\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/accounts/PF70001").with(customer(ALICE)))
                .andExpect(status().isOk());
    }

    private void owned(String owner, String number, String balance) {
        accountRepository.save(new Account(owner, number, new BigDecimal(balance), "INR"));
    }

    private ResultActions transfer(
            RequestPostProcessor caller, String from, String to, String amount, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/transfers").with(caller)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccountNumber":"%s","toAccountNumber":"%s",
                         "amount":%s,"currency":"INR"}
                        """.formatted(from, to, amount)));
    }

    private void assertBalance(String accountNumber, String expected) {
        assertThat(accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow().getBalance()).isEqualByComparingTo(expected);
    }
}
