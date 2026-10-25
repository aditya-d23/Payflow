package com.payflow.payment;

import com.payflow.payment.config.DevDataInitializer;
import com.payflow.payment.dto.CashMovementRequest;
import com.payflow.payment.dto.CreateAccountRequest;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.service.AccountService;
import com.payflow.payment.service.TransferService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevDataInitializerTests {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final AccountService accountService = mock(AccountService.class);
    private final TransferService transferService = mock(TransferService.class);
    private final DevDataInitializer initializer = new DevDataInitializer(
            accountRepository, accountService, transferService);

    @Test
    void createsAndFundsTheDevelopmentAccountThroughTheLedger() throws Exception {
        when(accountRepository.existsByAccountNumber("PF10001")).thenReturn(false);

        initializer.run(new DefaultApplicationArguments());

        verify(accountService).createAccount(new CreateAccountRequest("PF10001", "INR"));
        ArgumentCaptor<CashMovementRequest> deposit =
                ArgumentCaptor.forClass(CashMovementRequest.class);
        verify(transferService).deposit(deposit.capture(), eq("dev-seed-PF10001"));
        assertThat(deposit.getValue().accountNumber()).isEqualTo("PF10001");
        assertThat(deposit.getValue().amount()).isEqualByComparingTo(new BigDecimal("1000.00"));
        assertThat(deposit.getValue().currency()).isEqualTo("INR");
    }

    @Test
    void leavesTheDevelopmentAccountUnchangedOnRestart() throws Exception {
        when(accountRepository.existsByAccountNumber("PF10001")).thenReturn(true);

        initializer.run(new DefaultApplicationArguments());

        verifyNoInteractions(accountService, transferService);
    }
}
