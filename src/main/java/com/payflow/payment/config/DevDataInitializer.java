
package com.payflow.payment.config;

import com.payflow.payment.entity.Account;
import com.payflow.payment.repository.AccountRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Component
@Profile("dev")
public class DevDataInitializer implements ApplicationRunner {

    private final AccountRepository accountRepository;

    public DevDataInitializer(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        createDevelopmentAccountIfAbsent(
                "PF10001",
                new BigDecimal("1000.00")
        );
    }

    private void createDevelopmentAccountIfAbsent(
            String accountNumber,
            BigDecimal initialBalance) {

        if (!accountRepository.existsByAccountNumber(accountNumber)) {
            accountRepository.save(new Account(
                    accountNumber,
                    initialBalance,
                    "INR"
            ));
        }
    }
}
