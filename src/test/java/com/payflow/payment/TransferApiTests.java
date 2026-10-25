package com.payflow.payment;

import com.payflow.payment.entity.Account;
import com.payflow.payment.entity.AccountStatus;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.repository.PaymentTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for transfers: status codes, error codes, and the response
 * shape. Financial correctness under concurrency lives in the PostgreSQL ITs.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TransferApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentTransactionRepository transactionRepository;

    @AfterEach
    void clearDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void createdTransferExposesNoBalancesAndCanBeFetched() throws Exception {
        account("PF20001", "100.00", "INR");
        account("PF20002", "0.00", "INR");

        String location = transfer("PF20001", "PF20002", "25.50", "INR", "fetch-key")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.amount").value(25.5))
                .andExpect(jsonPath("$.completedAt").exists())
                .andExpect(jsonPath("$.fromAccount").doesNotExist())
                .andExpect(jsonPath("$.toAccount").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andReturn().getResponse().getHeader("Location");

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromAccountNumber").value("PF20001"))
                .andExpect(jsonPath("$.toAccountNumber").value("PF20002"));
    }

    @Test
    void replayIsMarkedWithHeader() throws Exception {
        account("PF20001", "100.00", "INR");
        account("PF20002", "0.00", "INR");
        transfer("PF20001", "PF20002", "10", "INR", "replay-header-key")
                .andExpect(status().isCreated());

        transfer("PF20001", "PF20002", "10.00", "INR", "replay-header-key")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void sameAccountTransferIsUnprocessable() throws Exception {
        account("PF20001", "100.00", "INR");

        transfer("PF20001", "PF20001", "1", "INR", "same-account-key")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("SAME_ACCOUNT_TRANSFER"))
                .andExpect(jsonPath("$.path").value("/api/v1/transfers"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void currencyMismatchIsUnprocessableAndMovesNothing() throws Exception {
        account("PF20001", "100.00", "INR");
        account("PF20002", "0.00", "USD");

        transfer("PF20001", "PF20002", "1", "INR", "mismatch-key")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));

        assertBalance("PF20001", "100.00");
        assertThat(transactionRepository.count()).isZero();
    }

    @Test
    void frozenAndClosedAccountsCannotSendOrReceive() throws Exception {
        account("PF20001", "100.00", "INR");
        withStatus(account("PF20002", "0.00", "INR"), AccountStatus.FROZEN);
        withStatus(account("PF20003", "0.00", "INR"), AccountStatus.CLOSED);

        transfer("PF20001", "PF20002", "1", "INR", "frozen-key")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        transfer("PF20003", "PF20001", "1", "INR", "closed-key")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));

        assertBalance("PF20001", "100.00");
    }

    @Test
    void amountsFinerThanTheCurrencyMinorUnitAreRejected() throws Exception {
        account("PF20001", "100.00", "INR");
        account("PF20002", "0.00", "INR");

        transfer("PF20001", "PF20002", "1.001", "INR", "precision-key")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }

    @Test
    void zeroAndNegativeAmountsFailValidation() throws Exception {
        transfer("PF20001", "PF20002", "0", "INR", "zero-key")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field").value(hasItem("amount")));
        transfer("PF20001", "PF20002", "-5", "INR", "negative-key")
                .andExpect(status().isBadRequest());
    }

    @Test
    void unsupportedCurrencyIsRejected() throws Exception {
        transfer("PF20001", "PF20002", "1", "XAU", "currency-key")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_CURRENCY"));
    }

    @Test
    void missingAccountIsNotFound() throws Exception {
        account("PF20001", "100.00", "INR");

        transfer("PF20001", "PF29999", "1", "INR", "missing-key")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        assertBalance("PF20001", "100.00");
    }

    @Test
    void insufficientFundsIsUnprocessable() throws Exception {
        account("PF20001", "5.00", "INR");
        account("PF20002", "0.00", "INR");

        transfer("PF20001", "PF20002", "5.01", "INR", "nsf-key")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
    }

    @Test
    void blankOrControlCharacterIdempotencyKeysAreRejected() throws Exception {
        transfer("PF20001", "PF20002", "1", "INR", "   ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
        transfer("PF20001", "PF20002", "1", "INR", "a".repeat(256))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    void malformedBodyAndUnknownTransferUseTheStandardErrorShape() throws Exception {
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "malformed-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        mockMvc.perform(get("/api/v1/transfers/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        mockMvc.perform(get("/api/v1/transfers/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void suppliedCorrelationIdIsEchoed() throws Exception {
        mockMvc.perform(get("/api/v1/transfers/00000000-0000-0000-0000-000000000000")
                        .header("X-Request-Id", "trace-123"))
                .andExpect(header().string("X-Request-Id", "trace-123"))
                .andExpect(jsonPath("$.correlationId").value("trace-123"));
    }

    private Account account(String number, String balance, String currency) {
        return accountRepository.save(
                new Account(number, new BigDecimal(balance), currency));
    }

    private void withStatus(Account account, AccountStatus status) {
        account.changeStatus(status);
        accountRepository.save(account);
    }

    private ResultActions transfer(
            String from, String to, String amount, String currency, String key)
            throws Exception {
        return mockMvc.perform(post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccountNumber":"%s","toAccountNumber":"%s",
                         "amount":%s,"currency":"%s"}
                        """.formatted(from, to, amount, currency)));
    }

    private void assertBalance(String accountNumber, String expected) {
        assertThat(accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow().getBalance()).isEqualByComparingTo(expected);
    }
}
