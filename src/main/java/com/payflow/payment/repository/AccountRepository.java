
package com.payflow.payment.repository;

import com.payflow.payment.entity.Account;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    String LOCK_TIMEOUT_MILLIS = "3000";

    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);


    /**
     * Row lock held until the surrounding transaction ends. The timeout bounds
     * how long a request waits behind a contended account before it fails with
     * a retryable error instead of tying up a request thread.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout",
            value = LOCK_TIMEOUT_MILLIS))
    @Query("SELECT a FROM Account a WHERE a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberForUpdate(
            @Param("accountNumber") String accountNumber
    );
}
