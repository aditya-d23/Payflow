
package com.payflow.payment.config;

import com.payflow.payment.dto.CashMovementRequest;
import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.service.AccountService;
import com.payflow.payment.service.TransferService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Seeds a funded development account. Funding goes through a deposit so the
 * ledger stays reconciled, and the fixed idempotency key makes restarts no-ops.
 */
@Component
@Profile("dev")
public class DevDataInitializer implements ApplicationRunner {

    static final String ACCOUNT_NUMBER = "PF10001";

    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final TransferService transferService;

    public DevDataInitializer(
            AccountRepository accountRepository,
            AccountService accountService,
            TransferService transferService) {
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.transferService = transferService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (accountRepository.existsByAccountNumber(ACCOUNT_NUMBER)) {
            return;
        }
        accountService.createAccount(new CreateAccountRequest(ACCOUNT_NUMBER, "INR"));
        transferService.deposit(
                new CashMovementRequest(ACCOUNT_NUMBER, new BigDecimal("1000.00"), "INR"),
                "dev-seed-" + ACCOUNT_NUMBER);
    }
}
