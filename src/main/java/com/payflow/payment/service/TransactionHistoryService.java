package com.payflow.payment.service;

import com.payflow.payment.dto.PageResponse;
import com.payflow.payment.dto.TransactionHistoryItemResponse;
import com.payflow.payment.entity.Account;
import com.payflow.payment.entity.PaymentTransaction;
import com.payflow.payment.exception.AccountNotFoundException;
import com.payflow.payment.repository.AccountRepository;
import com.payflow.payment.repository.PaymentTransactionRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionHistoryService {

    private final AccountRepository accountRepository;
    private final PaymentTransactionRepository transactionRepository;

    public TransactionHistoryService(
            AccountRepository accountRepository,
            PaymentTransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionHistoryItemResponse> getTransactions(
            String accountNumber,
            int page,
            int size) {

        Account account = accountRepository
                .findByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found: " + accountNumber
                ));

        Sort sort = Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        );

        Pageable pageable = PageRequest.of(page, size, sort);

        Page<PaymentTransaction> transactions =
                transactionRepository.findAccountTransactions(
                        account.getId(),
                        pageable
                );

        Page<TransactionHistoryItemResponse> response =
                transactions.map(transaction -> toResponse(
                        transaction, account.getId()
                ));

        return PageResponse.from(response);
    }

    private TransactionHistoryItemResponse toResponse(
            PaymentTransaction transaction,
            Long accountId) {

        String direction = transaction.getFromAccount().getId()
                .equals(accountId) ? "OUTGOING" : "INCOMING";

        return new TransactionHistoryItemResponse(
                transaction.getTransactionReference(),
                direction,
                transaction.getFromAccount().getAccountNumber(),
                transaction.getToAccount().getAccountNumber(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus().name(),
                transaction.getCreatedAt()
        );
    }
}