package com.payflow.payment.service;

import com.payflow.payment.dto.TransferRequest;
import com.payflow.payment.entity.Account;
import com.payflow.payment.entity.PaymentTransaction;
import com.payflow.payment.exception.AccountNotFoundException;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.repository.PaymentTransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

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

    @Transactional(rollbackFor = Exception.class)
    public PaymentTransaction execute(
            TransferRequest request,
            String idempotencyKey,
            String requestFingerprint
    ) {
        String fromNumber = request.fromAccountNumber().trim();
        String toNumber = request.toAccountNumber().trim();

        if (fromNumber.equals(toNumber)) {
            throw new IllegalArgumentException(
                    "Sender and receiver must be different accounts"
            );
        }

        BigDecimal amount = request.amount();

        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Transfer amount must be positive"
            );
        }

        // Lock accounts in a consistent order to reduce deadlocks.
        String firstNumber = fromNumber.compareTo(toNumber) < 0
                ? fromNumber : toNumber;
        String secondNumber = fromNumber.compareTo(toNumber) < 0
                ? toNumber : fromNumber;

        Account first = accountRepository
                .findByAccountNumberForUpdate(firstNumber)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found: " + firstNumber
                ));

        Account second = accountRepository
                .findByAccountNumberForUpdate(secondNumber)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found: " + secondNumber
                ));

        Account sender = fromNumber.equals(firstNumber) ? first : second;
        Account receiver = toNumber.equals(firstNumber) ? first : second;

        if (!sender.getCurrency().equals(request.currency())
                || !receiver.getCurrency().equals(request.currency())) {
            throw new IllegalArgumentException(
                    "Transfer currency must match both account currencies"
            );
        }

        PaymentTransaction transaction = new PaymentTransaction(
                sender,
                receiver,
                amount,
                request.currency(),
                idempotencyKey,
                requestFingerprint
        );

        transactionRepository.saveAndFlush(transaction);

        sender.debit(amount);
        receiver.credit(amount);
        transaction.markCompleted();

        return transaction;
    }
}
