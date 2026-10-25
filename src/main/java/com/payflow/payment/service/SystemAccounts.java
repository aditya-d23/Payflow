package com.payflow.payment.service;

import com.payflow.payment.entity.Account;
import com.payflow.payment.repository.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Per-currency funding accounts. V6 seeds the default currencies; any other
 * configured currency gets its account on first use.
 */
@Component
public class SystemAccounts {

    public static final String RESERVED_PREFIX = "SYS-";

    private final AccountRepository accountRepository;

    public SystemAccounts(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public static String fundingAccountNumber(String currency) {
        return RESERVED_PREFIX + "FUNDING-" + currency;
    }

    public static boolean isReserved(String accountNumber) {
        return accountNumber.toUpperCase(Locale.ROOT).startsWith(RESERVED_PREFIX);
    }

    /**
     * Not transactional on purpose: the insert runs in the repository's own
     * transaction, so losing a creation race does not poison the caller's.
     */
    public String ensureFundingAccount(String currency) {
        String number = fundingAccountNumber(currency);
        if (!accountRepository.existsByAccountNumber(number)) {
            try {
                accountRepository.saveAndFlush(Account.system(number, currency));
            } catch (DataIntegrityViolationException alreadyCreated) {
                // Another request created it first.
            }
        }
        return number;
    }
}
