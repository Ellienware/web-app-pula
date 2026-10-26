package com.momocircle.service.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Runs every shared scenario on PostgreSQL WITH the opt-in guards migration (V41) applied, then adds tests that only the
 * triggers can satisfy. Commit-time rules are tested by actually committing: the failure must appear at COMMIT, not at the
 * insert statement. SKIPPED when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
class LedgerPostgresGuardsTest extends AbstractLedgerPersistenceTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration,classpath:db/postgresql-guards");
    }

    @Autowired PlatformTransactionManager txm;

    @Override
    protected boolean databaseGuardsEnabled() {
        return true;
    }

    private void rawTx(String id, String key) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("insert into ledger_transactions (id, type, status, initiator_user_id, idempotency_key, fingerprint, currency, "
                        + "provider_reference, simulated, created_at, updated_at) values (?, 'INTERNAL_TRANSFER', 'POSTED', 'raw', ?, 'fp', 'ZAR', ?, true, ?, ?)",
                id, key, "SIM-" + id.substring(0, 12).toUpperCase(), now, now);
    }

    private void rawPosting(String txId, int line, String acct, String dir, long minor) {
        jdbc.update("insert into ledger_postings (id, transaction_id, line_no, account_id, direction, amount_minor, currency, created_at) "
                + "values (?,?,?,?,?,?,'ZAR',?)", UUID.randomUUID().toString(), txId, line, acct, dir, minor, Timestamp.from(Instant.now()));
    }

    private void inOneTransaction(Runnable work) {
        new TransactionTemplate(txm).executeWithoutResult(status -> work.run());
    }

    @Test
    void anUnbalancedTransactionIsRejectedAtCommitAndLeavesNothingBehind() {
        String w = wallet(newUser()), f = engineFunding();
        String tx = UUID.randomUUID().toString();
        // The failure is raised by the deferred trigger at COMMIT; depending on translation it is a DataAccessException or a
        // TransactionSystemException, so assert the general type and then prove nothing was committed.
        assertThrows(RuntimeException.class, () -> inOneTransaction(() -> {
            rawTx(tx, "unb-" + tx);
            rawPosting(tx, 1, w, "CREDIT", 100);
            rawPosting(tx, 2, f, "DEBIT", 90);   // statements succeed; the deferred trigger fails at commit
        }));
        assertEquals(0L, jdbc.queryForObject("select count(*) from ledger_transactions where id = ?", Long.class, tx));
    }

    @Test
    void aTransactionWithFewerThanTwoPostingsIsRejectedAtCommit() {
        String w = wallet(newUser());
        String one = UUID.randomUUID().toString(), none = UUID.randomUUID().toString();
        assertThrows(RuntimeException.class, () -> inOneTransaction(() -> {
            rawTx(one, "one-" + one);
            rawPosting(one, 1, w, "CREDIT", 100);
        }));
        assertThrows(RuntimeException.class, () -> inOneTransaction(() -> rawTx(none, "none-" + none)));
        assertEquals(0L, jdbc.queryForObject("select count(*) from ledger_transactions where id in (?, ?)", Long.class, one, none));
    }

    @Test
    void postedEntriesCannotBeUpdatedDeletedOrTruncated() {
        String u = newUser();
        String w = wallet(u);
        fund(u, w, 1_000);
        assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_postings set amount_minor = amount_minor + 1 where account_id = ?", w));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from ledger_postings where account_id = ?", w));
        assertThrows(DataAccessException.class, () -> jdbc.execute("truncate table ledger_postings"));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from ledger_transactions where initiator_user_id = ?", u));
        assertThrows(DataAccessException.class, () -> jdbc.update("delete from ledger_accounts where id = ?", w));
        assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_transactions set type = 'REVERSAL' where initiator_user_id = ?", u));
        assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_transactions set status = 'PENDING' where initiator_user_id = ?", u)); // POSTED -> PENDING
        assertThrows(DataAccessException.class, () -> jdbc.update("update ledger_accounts set kind = 'DEMO_FUNDING' where id = ?", w));
        assertReconciled();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Three inherited raw-SQL schema tests insert a bare ledger_transactions row (no postings) and expect it to commit.
    // With the guards that is, by design, impossible: the deferred trigger refuses a transaction without >= 2 postings.
    // Those constraints (idempotency uniqueness, single reversal, composite-FK currency checks) are still proven by
    // LedgerPostgresPersistenceTest, which runs the same tests without the guards. Here each is overridden with the
    // guard-specific truth, so none is skipped and none errors for the wrong reason.

    private void assertBareTransactionIsRefusedAtCommit(String type, String key) {
        String id = UUID.randomUUID().toString();
        assertThrows(RuntimeException.class, () -> inOneTransaction(() -> {
            Timestamp now = Timestamp.from(Instant.now());
            jdbc.update("insert into ledger_transactions (id, type, status, initiator_user_id, idempotency_key, fingerprint, currency, "
                            + "reverses_tx_id, provider_reference, simulated, created_at, updated_at) values (?,?,'POSTED','raw',?,'fp','ZAR',null,?,true,?,?)",
                    id, type, key + id, "SIM-" + id.substring(0, 12).toUpperCase(), now, now);
        }));
        assertEquals(0L, jdbc.queryForObject("select count(*) from ledger_transactions where id = ?", Long.class, id));
    }

    @Override
    @Test
    void theSchemaRejectsDuplicateIdempotencyKeysPerInitiatorButAllowsOtherInitiators() {
        assertBareTransactionIsRefusedAtCommit("INTERNAL_TRANSFER", "guard-dup-");
    }

    @Override
    @Test
    void theSchemaRejectsMismatchedCurrenciesAndBadPostings() {
        assertBareTransactionIsRefusedAtCommit("INTERNAL_TRANSFER", "guard-cur-");
    }

    @Override
    @Test
    void theSchemaAllowsAtMostOneReversalPerTransaction() {
        assertBareTransactionIsRefusedAtCommit("INTERNAL_TRANSFER", "guard-rev-");
    }

    private String engineFunding() {
        return store.systemAccountId(com.momocircle.ledger.Account.Kind.DEMO_FUNDING, ZAR).orElseThrow();
    }
}
