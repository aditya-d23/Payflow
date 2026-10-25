package com.payflow.payment.service;

import com.payflow.payment.dto.TransferResponse;
import com.payflow.payment.entity.Account;
import com.payflow.payment.entity.PaymentTransaction;
import com.payflow.payment.exception.AccountNotFoundException;
import com.payflow.payment.exception.ErrorCode;
import com.payflow.payment.exception.TransferRejectedException;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.repository.PaymentTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferExecutionService {

    private final AccountRepository accountRepository;
    private final PaymentTransactionRepository transactionRepository;

    public TransferExecutionService(
            AccountRepository accountRepository,
            PaymentTransactionRepository transactionRepository
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Moves money in a single database transaction: both row locks, the
     * idempotency claim, the debit, and the credit commit or roll back
     * together. Any exception rolls everything back.
     */
    @Transactional(rollbackFor = Exception.class, timeout = 10)
    public TransferResponse execute(TransferCommand command) {
        String fromNumber = command.fromAccountNumber();
        String toNumber = command.toAccountNumber();

        if (fromNumber.equals(toNumber)) {
            throw new TransferRejectedException(
                    ErrorCode.SAME_ACCOUNT_TRANSFER,
                    "Sender and receiver must be different accounts"
            );
        }

        // Lock accounts in a consistent order so two opposite-direction
        // transfers cannot each hold one lock and wait for the other.
        String firstNumber = fromNumber.compareTo(toNumber) < 0
                ? fromNumber : toNumber;
        String secondNumber = fromNumber.compareTo(toNumber) < 0
                ? toNumber : fromNumber;

        Account first = lock(firstNumber);
        Account second = lock(secondNumber);

        Account sender = fromNumber.equals(firstNumber) ? first : second;
        Account receiver = toNumber.equals(firstNumber) ? first : second;

        sender.ensureActive();
        receiver.ensureActive();

        if (!sender.getCurrency().equals(command.currency())
                || !receiver.getCurrency().equals(command.currency())) {
            throw new TransferRejectedException(
                    ErrorCode.CURRENCY_MISMATCH,
                    "Transfer currency must match both account currencies"
            );
        }

        PaymentTransaction transaction = new PaymentTransaction(
                sender,
                receiver,
                command.amount(),
                command.currency(),
                command.idempotencyKey(),
                command.requestFingerprint()
        );

        // Claim the idempotency key before mutating balances: a concurrent
        // duplicate fails here on the unique constraint.
        transactionRepository.saveAndFlush(transaction);

        sender.debit(command.amount());
        receiver.credit(command.amount());
        transaction.markCompleted();

        return TransferResponse.from(transactionRepository.saveAndFlush(transaction));
    }

    private Account lock(String accountNumber) {
        return accountRepository
                .findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found: " + accountNumber
                ));
    }
}
