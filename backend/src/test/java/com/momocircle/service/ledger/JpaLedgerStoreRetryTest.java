package com.momocircle.service.ledger;

import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.LedgerException.Code;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Database-free tests of the store's failure handling: which failures are retried, that retries are bounded, that
 * business-rule failures are never retried, and that raw database errors never escape. The retry loop is exercised with no
 * repositories (empty lock list) and a no-op transaction manager, so these run as plain unit tests.
 */
class JpaLedgerStoreRetryTest {

    /** A transaction manager that does nothing: begin/commit/rollback return null/void. */
    private static PlatformTransactionManager noopTxManager() {
        return (PlatformTransactionManager) Proxy.newProxyInstance(
                JpaLedgerStoreRetryTest.class.getClassLoader(), new Class<?>[]{PlatformTransactionManager.class},
                (proxy, method, args) -> null);
    }

    private static JpaLedgerStore store() {
        return new JpaLedgerStore(null, null, null, noopTxManager());
    }

    private static RuntimeException duplicateKey(String constraint) {
        return new RuntimeException("could not execute statement",
                new SQLException("ERROR: duplicate key value violates unique constraint \"" + constraint + "\"", "23505"));
    }

    // ------------------------------------------------------------ classification

    @Test
    void deadlockSerializationAndLockFailuresAreRetryable() {
        assertTrue(JpaLedgerStore.isRetryable(new RuntimeException("x", new SQLException("deadlock detected", "40P01"))));
        assertTrue(JpaLedgerStore.isRetryable(new RuntimeException("x", new SQLException("could not serialize", "40001"))));
        assertTrue(JpaLedgerStore.isRetryable(new ConcurrencyFailureException("lock timeout")));
    }

    @Test
    void aLostIdempotencyOrReversalRaceIsRetryableOnAnyExceptionType() {
        assertTrue(JpaLedgerStore.isRetryable(duplicateKey("uq_ledger_idem")));
        assertTrue(JpaLedgerStore.isRetryable(duplicateKey("uq_ledger_single_reversal")));
        assertTrue(JpaLedgerStore.isRetryable(new PersistenceException() {
            private static final long serialVersionUID = 1L;
            @Override public String getMessage() { return "Unique index violation: \"PUBLIC.UQ_LEDGER_IDEM_INDEX_8\""; }
        })); // H2 spelling, raw (untranslated) JPA exception
    }

    @Test
    void otherFailuresAreNotRetried() {
        assertFalse(JpaLedgerStore.isRetryable(duplicateKey("uq_ledger_provider_ref")));
        assertFalse(JpaLedgerStore.isRetryable(new RuntimeException("boom")));
        assertFalse(JpaLedgerStore.isRetryable(new SQLException("syntax error", "42601")));
        assertFalse(JpaLedgerStore.isRetryable(new LedgerException(Code.INSUFFICIENT_FUNDS, "no")));
    }

    // ------------------------------------------------------------ translation / no leakage

    @Test
    void theBalanceFloorConstraintBecomesInsufficientFunds() {
        LedgerException e = JpaLedgerStore.translate(new RuntimeException("x",
                new SQLException("new row violates check constraint \"ck_ledger_balance_floor\"", "23514")));
        assertEquals(Code.INSUFFICIENT_FUNDS, e.code());
    }

    @Test
    void rawDatabaseDetailsNeverEscapeInTheMessage() {
        LedgerException e = JpaLedgerStore.translate(new PersistenceException() {
            private static final long serialVersionUID = 1L;
            @Override public String getMessage() { return "insert into ledger_postings (secret-sql-detail) failed"; }
        });
        assertEquals(Code.CONCURRENCY_FAILURE, e.code());
        assertFalse(e.getMessage().contains("secret-sql-detail"));
        assertFalse(e.getMessage().contains("ledger_postings"));
    }

    @Test
    void unrelatedProgrammingErrorsAreRethrownUnchanged() {
        NullPointerException npe = new NullPointerException("bug");
        assertThrows(NullPointerException.class, () -> JpaLedgerStore.translate(npe));
    }

    // ------------------------------------------------------------ the retry loop

    @Test
    void aTransientFailureIsRetriedAndTheWorkEventuallySucceeds() {
        AtomicInteger calls = new AtomicInteger();
        String result = store().atomically(List.of(), w -> {
            if (calls.incrementAndGet() < 3) throw new ConcurrencyFailureException("deadlock");
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(3, calls.get());
    }

    @Test
    void retriesAreBoundedAndEndInAnExplicitFailure() {
        AtomicInteger calls = new AtomicInteger();
        LedgerException e = assertThrows(LedgerException.class, () -> store().atomically(List.of(), w -> {
            calls.incrementAndGet();
            throw new ConcurrencyFailureException("always locked");
        }));
        assertEquals(Code.CONCURRENCY_FAILURE, e.code());
        assertEquals(JpaLedgerStore.MAX_ATTEMPTS, calls.get());
    }

    @Test
    void businessRuleFailuresAreNeverRetried() {
        AtomicInteger calls = new AtomicInteger();
        LedgerException e = assertThrows(LedgerException.class, () -> store().atomically(List.of(), w -> {
            calls.incrementAndGet();
            throw new LedgerException(Code.INSUFFICIENT_FUNDS, "no");
        }));
        assertEquals(Code.INSUFFICIENT_FUNDS, e.code());
        assertEquals(1, calls.get());
    }

    @Test
    void aRaceOnTheIdempotencyKeyIsRetriedFromScratchSoTheWinnerIsObserved() {
        AtomicInteger calls = new AtomicInteger();
        String result = store().atomically(List.of(), w -> {
            if (calls.incrementAndGet() == 1) throw duplicateKey("uq_ledger_idem"); // concurrent request committed first
            return "replayed-winner";
        });
        assertEquals("replayed-winner", result);
        assertEquals(2, calls.get());
    }

    @Test
    void aNonRetryableStorageFailureBecomesALedgerExceptionWithoutRetrying() {
        AtomicInteger calls = new AtomicInteger();
        LedgerException e = assertThrows(LedgerException.class, () -> store().atomically(List.of(), w -> {
            calls.incrementAndGet();
            throw new PersistenceException();
        }));
        assertEquals(Code.CONCURRENCY_FAILURE, e.code());
        assertEquals(1, calls.get());
    }
}
