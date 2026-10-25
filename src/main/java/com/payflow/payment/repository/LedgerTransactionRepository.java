package com.payflow.payment.repository;

import com.payflow.payment.entity.LedgerTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerTransactionRepository
        extends JpaRepository<LedgerTransaction, Long> {
}
