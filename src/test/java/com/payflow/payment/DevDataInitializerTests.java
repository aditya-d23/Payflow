package com.payflow.payment;

import com.payflow.payment.config.DevDataInitializer;
import com.payflow.payment.entity.Account;
import com.payflow.payment.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevDataInitializerTests {

    private final AccountRepository accountRepository = mock(
            AccountRepository.class
    );
    private final DevDataInitializer initializer = new DevDataInitializer(
            accountRepository
    );

    @Test
    void createsTheDevelopmentAccountOnFirstStart() throws Exception {
        when(accountRepository.existsByAccountNumber("PF10001"))
                .thenReturn(false);

        initializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor
                .forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account account = accountCaptor.getValue();
        assertThat(account.getAccountNumber()).isEqualTo("PF10001");
        assertThat(account.getBalance()).isEqualByComparingTo(
                new BigDecimal("1000.00")
        );
        assertThat(account.getCurrency()).isEqualTo("INR");
    }

    @Test
    void leavesTheDevelopmentAccountUnchangedOnRestart() throws Exception {
        when(accountRepository.existsByAccountNumber("PF10001"))
                .thenReturn(true);

        initializer.run(new DefaultApplicationArguments());

        verify(accountRepository, never()).save(any(Account.class));
    }
}
