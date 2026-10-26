package com.momocircle.service.ledger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Migration safety on a DISPOSABLE database: an existing schema at V39 (with a historical user and payment) must upgrade to
 * V40 with every historical row unchanged; a clean database must reach V40; re-running is a no-op. Never run against a
 * database holding data you care about.
 */
abstract class AbstractMigrationUpgradeTest {

    abstract DriverManagerDataSource freshDataSource();

    private Flyway flyway(DriverManagerDataSource ds, String target) {
        var cfg = Flyway.configure().dataSource(ds).locations("classpath:db/migration");
        if (target != null) cfg.target(target);
        return cfg.load();
    }

    @Test
    void upgradeFromV39PreservesHistoricalRowsAndAddsTheLedger() {
        DriverManagerDataSource ds = freshDataSource();
        Flyway v39 = flyway(ds, "39");
        v39.migrate();
        assertEquals("39", v39.info().current().getVersion().getVersion());

        JdbcTemplate jdbc = new JdbcTemplate(ds);
        Timestamp t = Timestamp.from(Instant.parse("2026-01-02T03:04:05Z"));
        jdbc.update("insert into payments (id, created_at, updated_at, amount, currency, status, provider, provider_reference, idempotency_key) "
                + "values ('hist-pay-1', ?, ?, 123.45, 'ZAR', 'SUCCESSFUL', 'paystack', 'ps_ref_1', 'hist-key-1')", t, t);
        jdbc.update("insert into payments (id, created_at, updated_at, amount, currency, status, provider) "
                + "values ('hist-pay-2', ?, ?, 9.99, 'ZAR', 'SUCCESSFUL', 'paystack-mock')", t, t);
        List<Map<String, Object>> before = jdbc.queryForList(
                "select id, amount, currency, status, provider, provider_reference, idempotency_key from payments order by id");

        Flyway latest = flyway(ds, "40"); // this test is about the V39 -> V40 step; later versions are covered below
        latest.migrate();
        assertEquals("40", latest.info().current().getVersion().getVersion());

        List<Map<String, Object>> after = jdbc.queryForList(
                "select id, amount, currency, status, provider, provider_reference, idempotency_key from payments order by id");
        assertEquals(before, after, "V40 must not change any historical payment row");
        assertNull(jdbc.queryForObject("select ledger_transaction_id from payments where id = 'hist-pay-1'", String.class));
        assertEquals(2L, jdbc.queryForObject("select count(*) from ledger_accounts where owner_user_id is null", Long.class));
        assertEquals(0L, jdbc.queryForObject("select count(*) from ledger_transactions", Long.class));
        assertEquals(0L, jdbc.queryForObject("select count(*) from ledger_postings", Long.class));

        // ... and the later wallet migrations (V42 payment requests, V43 business wallets) apply on top without touching anything
        Flyway onward = flyway(ds, null);
        onward.migrate();
        assertTrue(Integer.parseInt(onward.info().current().getVersion().getVersion()) >= 43);
        assertEquals(before, jdbc.queryForList(
                "select id, amount, currency, status, provider, provider_reference, idempotency_key from payments order by id"),
                "later migrations must not change any historical payment row");
        assertEquals(2L, jdbc.queryForObject("select count(*) from ledger_accounts where owner_user_id is null and owner_business_id is null", Long.class));
        assertEquals(0L, jdbc.queryForObject("select count(*) from business_wallet_members", Long.class));
        assertEquals(0L, jdbc.queryForObject("select count(*) from wallet_payment_requests", Long.class));
    }

    @Test
    void aCleanDatabaseMigratesToV40AndRerunningIsANoOp() {
        DriverManagerDataSource ds = freshDataSource();
        Flyway flyway = flyway(ds, "40");
        flyway.migrate();
        assertEquals("40", flyway.info().current().getVersion().getVersion());
        assertEquals(0, flyway(ds, "40").migrate().migrationsExecuted);
    }

    @Test
    void aCleanDatabaseMigratesToTheLatestVersionAndRerunningIsANoOp() {
        DriverManagerDataSource ds = freshDataSource();
        Flyway flyway = flyway(ds, null);
        flyway.migrate();
        assertTrue(Integer.parseInt(flyway.info().current().getVersion().getVersion()) >= 43,
                "V42 (payment requests) and V43 (business wallets) must be applied");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        assertEquals(0L, jdbc.queryForObject("select count(*) from wallet_profiles", Long.class));
        assertEquals(0L, jdbc.queryForObject("select count(*) from business_wallet_profiles", Long.class));
        assertEquals(2L, jdbc.queryForObject("select count(*) from ledger_accounts where owner_business_id is null and owner_user_id is null", Long.class));
        assertEquals(0, flyway(ds, null).migrate().migrationsExecuted);
    }
}
