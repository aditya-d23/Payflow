package com.payflow.payment;

import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.service.ReconciliationService;
import com.payflow.payment.support.H2Tables;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FundingApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @org.junit.jupiter.api.AfterEach
    void clearDatabase() {
        H2Tables.truncateAll(jdbcTemplate);
    }

    @Test
    void depositAndWithdrawalMoveMoneyThroughTheFundingAccount() throws Exception {
        open("PF60001");

        move("/api/v1/deposits", "PF60001", "100.00", "dep-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.fromAccountNumber").value("SYS-FUNDING-INR"))
                .andExpect(jsonPath("$.toAccountNumber").value("PF60001"));
        move("/api/v1/withdrawals", "PF60001", "30.00", "wd-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("WITHDRAWAL"));

        mockMvc.perform(get("/api/v1/accounts/PF60001/balance"))
                .andExpect(jsonPath("$.balance").value(70.0));
        assertThat(accountRepository.findByAccountNumber("SYS-FUNDING-INR")
                .orElseThrow().getBalance()).isEqualByComparingTo("-70");
        assertThat(reconciliationService.reconcile().consistent()).isTrue();
    }

    @Test
    void depositsAreIdempotent() throws Exception {
        open("PF60001");
        move("/api/v1/deposits", "PF60001", "100", "dep-once").andExpect(status().isCreated());
        move("/api/v1/deposits", "PF60001", "100.00", "dep-once")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));

        mockMvc.perform(get("/api/v1/accounts/PF60001/balance"))
                .andExpect(jsonPath("$.balance").value(100.0));
    }

    @Test
    void withdrawalBeyondTheBalanceIsRejected() throws Exception {
        open("PF60001");
        move("/api/v1/withdrawals", "PF60001", "0.01", "wd-nsf")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void reservedAccountNumbersCannotBeCreated() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"SYS-FUNDING-USD\",\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void depositingIntoTheFundingAccountItselfIsRejected() throws Exception {
        open("PF60001");
        move("/api/v1/deposits", "PF60001", "1", "create-funding").andExpect(status().isCreated());
        move("/api/v1/deposits", "SYS-FUNDING-INR", "1", "self-deposit")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"));
    }

    private void open(String accountNumber) throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountNumber\":\"" + accountNumber + "\",\"currency\":\"INR\"}"))
                .andExpect(status().isCreated());
    }

    private ResultActions move(String path, String account, String amount, String key)
            throws Exception {
        return mockMvc.perform(post(path)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"accountNumber":"%s","amount":%s,"currency":"INR"}
                        """.formatted(account, amount)));
    }
}
