
package com.payflow.payment.config;

import com.payflow.payment.dto.CashMovementRequest;
import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.entity.Account;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.security.Caller;
import com.payflow.payment.service.AccountService;
import com.payflow.payment.service.TransferService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Seeds a funded development account. Funding goes through a deposit so the
 * ledger stays reconciled. While the balance is zero the deposit is retried on
 * every start, so a crash between creating and funding the account heals
 * itself; the fixed idempotency key turns an already-applied deposit into a
 * replay that moves no money.
 */
@Component
@Profile("dev")
public class DevDataInitializer implements ApplicationRunner {

    static final String ACCOUNT_NUMBER = "PF10001";
    static final String OWNER = "dev-user";
    private static final Caller SEEDER = Caller.admin("dev-seeder");

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
        Optional<Account> existing = accountRepository.findByAccountNumber(ACCOUNT_NUMBER);
        if (existing.isEmpty()) {
            accountService.createAccount(
                    new CreateAccountRequest(ACCOUNT_NUMBER, "INR", OWNER), SEEDER);
        } else if (existing.get().getBalance().signum() != 0) {
            return; // funded, by this seeder or otherwise
        }
        transferService.deposit(
                new CashMovementRequest(ACCOUNT_NUMBER, new BigDecimal("1000.00"), "INR"),
                "dev-seed-" + ACCOUNT_NUMBER, SEEDER);
    }
}
