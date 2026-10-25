package com.payflow.payment.service;

import com.payflow.payment.entity.Account;
import com.payflow.payment.entity.EntryDirection;
import com.payflow.payment.entity.LedgerEntry;
import com.payflow.payment.entity.LedgerTransaction;
import com.payflow.payment.entity.LedgerTransactionType;
import com.payflow.payment.entity.PaymentTransaction;
import com.payflow.payment.repository.LedgerTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * The only code path that changes account balances. Every balance change is
 * recorded as a balanced set of ledger entries in the caller's transaction,
 * so balances and ledger can only commit together.
 */
@Service
public class LedgerService {

    private final LedgerTransactionRepository ledgerTransactionRepository;

    public LedgerService(LedgerTransactionRepository ledgerTransactionRepository) {
        this.ledgerTransactionRepository = ledgerTransactionRepository;
    }

    public record Posting(Account account, EntryDirection direction, BigDecimal amount) {}

    /**
     * Applies and records the postings. Callers must already hold row locks
     * on every account involved.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction post(
            LedgerTransactionType type,
            String currency,
            PaymentTransaction source,
            List<Posting> postings) {
        validate(currency, postings);

        LedgerTransaction transaction = new LedgerTransaction(
                type, currency, source, type.name() + " " + source.getTransactionReference());
        for (Posting posting : postings) {
            if (posting.direction() == EntryDirection.DEBIT) {
                posting.account().debit(posting.amount());
            } else {
                posting.account().credit(posting.amount());
            }
            new LedgerEntry(transaction, posting.account(),
                    posting.direction(), posting.amount());
        }
        return ledgerTransactionRepository.save(transaction);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerTransaction postMovement(
            LedgerTransactionType type,
            PaymentTransaction source,
            Account from,
            Account to) {
        return post(type, source.getCurrency(), source, List.of(
                new Posting(from, EntryDirection.DEBIT, source.getAmount()),
                new Posting(to, EntryDirection.CREDIT, source.getAmount())));
    }

    static void validate(String currency, List<Posting> postings) {
        if (postings.size() < 2) {
            throw new IllegalArgumentException("A posting needs at least two entries");
        }
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (Posting posting : postings) {
            if (posting.amount() == null || posting.amount().signum() <= 0) {
                throw new IllegalArgumentException("Entry amounts must be positive");
            }
            if (!posting.account().getCurrency().equals(currency)) {
                throw new IllegalArgumentException(
                        "Entry account currency does not match the posting currency");
            }
            if (posting.direction() == EntryDirection.DEBIT) {
                debits = debits.add(posting.amount());
            } else {
                credits = credits.add(posting.amount());
            }
        }
        if (debits.compareTo(credits) != 0) {
            throw new IllegalArgumentException(
                    "Unbalanced posting: debits " + debits + " != credits " + credits);
        }
    }
}
