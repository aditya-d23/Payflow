package com.payflow.payment;

import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.entity.Account;
import com.payflow.payment.entity.PaymentTransaction;
import com.payflow.payment.exception.InsufficientBalanceException;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.repository.PaymentTransactionRepository;
import com.payflow.payment.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TransferServiceIntegrationTests {

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private MockMvc mockMvc;

    @MockitoSpyBean
    private PaymentTransactionRepository transactionRepository;

    @AfterEach
    void clearDatabase() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void rejectsTransfersThatExceedTheSenderBalance() {
        createAccount("PF10001", "10.0000");
        createAccount("PF10002", "0.0000");

        assertThatThrownBy(() -> transfer(
                "PF10001", "PF10002", "10.0001", "insufficient-key"
        ))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessage("Insufficient account balance");

        assertBalances("PF10001", "10.0000");
        assertBalances("PF10002", "0.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(transactionRepository.findByIdempotencyKey("insufficient-key"))
                .isEmpty();
    }

    @Test
    void transfersFundsAndPersistsTheIdempotencyKey() throws Exception {
        createAccount("PF10001", "100.0000");
        createAccount("PF10002", "25.0000");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "first-transfer-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAccountNumber": "PF10001",
                                  "toAccountNumber": "PF10002",
                                  "amount": 40.0000,
                                  "currency": "INR"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
                .andExpect(jsonPath("$.idempotencyRequestFingerprint")
                        .doesNotExist());

        PaymentTransaction transaction = transactionRepository
                .findByIdempotencyKey("first-transfer-key")
                .orElseThrow();

        assertBalances("PF10001", "60.0000");
        assertBalances("PF10002", "65.0000");
        assertThat(transaction.getStatus()).isEqualTo("COMPLETED");
        assertThat(transaction.getIdempotencyKey())
                .isEqualTo("first-transfer-key");
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void returnsTheOriginalTransactionForAnIdenticalRetry() {
        createAccount("PF10001", "100.0000");
        createAccount("PF10002", "0.0000");

        PaymentTransaction first = transfer(
                "PF10001", "PF10002", "40.0000", "retry-key"
        );
        PaymentTransaction retry = transfer(
                "PF10001", "PF10002", "40.0", "retry-key"
        );

        assertThat(retry.getTransactionReference())
                .isEqualTo(first.getTransactionReference());
        assertBalances("PF10001", "60.0000");
        assertBalances("PF10002", "40.0000");
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void returnsConflictWhenAnIdempotencyKeyIsReusedForAnotherPayload()
            throws Exception {
        createAccount("PF10001", "100.0000");
        createAccount("PF10002", "0.0000");
        transfer("PF10001", "PF10002", "40.0000", "conflict-key");

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "conflict-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAccountNumber": "PF10001",
                                  "toAccountNumber": "PF10002",
                                  "amount": 30.0000,
                                  "currency": "INR"
                                }
                                """))
                .andExpect(status().isConflict());

        assertBalances("PF10001", "60.0000");
        assertBalances("PF10002", "40.0000");
        assertThat(transactionRepository.count()).isEqualTo(1);
    }

    @Test
    void rollsBackAccountBalancesAndIdempotencyRecordWhenPersistenceFails() {
        createAccount("PF10001", "100.0000");
        createAccount("PF10002", "0.0000");
        doThrow(new DataIntegrityViolationException("forced persistence failure"))
                .when(transactionRepository)
                .saveAndFlush(any(PaymentTransaction.class));

        assertThatThrownBy(() -> transfer(
                "PF10001", "PF10002", "40.0000", "rollback-key"
        ))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessage("forced persistence failure");

        assertBalances("PF10001", "100.0000");
        assertBalances("PF10002", "0.0000");
        assertThat(transactionRepository.count()).isZero();
        assertThat(transactionRepository.findByIdempotencyKey("rollback-key"))
                .isEmpty();
    }

    @Test
    void concurrentTransfersCannotOverdrawTheSameAccount() throws Exception {
        createAccount("PF10001", "1000.0000");
        createAccount("PF10002", "0.0000");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                int transferAttempt = attempt;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        transfer(
                                "PF10001",
                                "PF10002",
                                "750.0000",
                                "balance-lock-key-" + transferAttempt
                        );
                        return null;
                    } catch (Throwable exception) {
                        return exception;
                    }
                }));
            }

            ready.await();
            start.countDown();

            List<Throwable> failures = new ArrayList<>();
            for (Future<Throwable> result : results) {
                Throwable failure = result.get();
                if (failure != null) {
                    failures.add(failure);
                }
            }

            assertThat(failures)
                    .hasSize(1)
                    .allSatisfy(failure -> assertThat(failure)
                            .isInstanceOf(InsufficientBalanceException.class));
            assertBalances("PF10001", "250.0000");
            assertBalances("PF10002", "750.0000");
            assertThat(transactionRepository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentRetriesWithTheSameKeyMoveMoneyOnlyOnce() throws Exception {
        createAccount("PF10001", "100.0000");
        createAccount("PF10002", "0.0000");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<PaymentTransaction>> results = new ArrayList<>();
            for (int attempt = 0; attempt < 2; attempt++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return transfer(
                            "PF10001",
                            "PF10002",
                            "40.0000",
                            "concurrent-retry-key"
                    );
                }));
            }

            ready.await();
            start.countDown();

            PaymentTransaction first = results.get(0).get();
            PaymentTransaction second = results.get(1).get();

            assertThat(second.getTransactionReference())
                    .isEqualTo(first.getTransactionReference());
            assertBalances("PF10001", "60.0000");
            assertBalances("PF10002", "40.0000");
            assertThat(transactionRepository.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private Account createAccount(String accountNumber, String balance) {
        return accountRepository.save(new Account(
                accountNumber,
                new BigDecimal(balance),
                "INR"
        ));
    }

    private PaymentTransaction transfer(
            String fromAccountNumber,
            String toAccountNumber,
            String amount,
            String idempotencyKey) {

        return transferService.transfer(new TransferRequest(
                fromAccountNumber,
                toAccountNumber,
                new BigDecimal(amount),
                "INR"
        ), idempotencyKey);
    }

    private void assertBalances(String accountNumber, String expectedBalance) {
        Account account = accountRepository.findByAccountNumber(accountNumber)
                .orElseThrow();
        assertThat(account.getBalance())
                .isEqualByComparingTo(expectedBalance);
    }
}
