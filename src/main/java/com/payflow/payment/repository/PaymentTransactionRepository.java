package com.payflow.payment.repository;

import com.payflow.payment.entity.PaymentTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentTransactionRepository
        extends JpaRepository<PaymentTransaction, Long> {

    boolean existsByTransactionReference(
            java.util.UUID transactionReference
    );

    @Query("""
            SELECT t
            FROM PaymentTransaction t
            JOIN FETCH t.fromAccount
            JOIN FETCH t.toAccount
            WHERE t.idempotencyKey = :idempotencyKey
            """)
    Optional<PaymentTransaction> findByIdempotencyKey(
            @Param("idempotencyKey") String idempotencyKey
    );

    @Query(
            value = """
            SELECT t
            FROM PaymentTransaction t
            WHERE t.fromAccount.id = :accountId
               OR t.toAccount.id = :accountId
            """,
            countQuery = """
            SELECT COUNT(t)
            FROM PaymentTransaction t
            WHERE t.fromAccount.id = :accountId
               OR t.toAccount.id = :accountId
            """
    )
    Page<PaymentTransaction> findAccountTransactions(
            @Param("accountId") Long accountId,
            Pageable pageable
    );
}
