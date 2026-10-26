package com.momocircle.service.ledger;

import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V39 -> V40 upgrade and clean install on a disposable PostgreSQL container. SKIPPED when Docker is unavailable. */
@Testcontainers(disabledWithoutDocker = true)
class MigrationUpgradePostgresTest extends AbstractMigrationUpgradeTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private int n;

    /** Each test gets its own schema in the same disposable container so the two tests cannot interfere. */
    @Override
    synchronized DriverManagerDataSource freshDataSource() {
        String schema = "mig" + (++n);
        DriverManagerDataSource admin = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        new org.springframework.jdbc.core.JdbcTemplate(admin).execute("create schema " + schema);
        return new DriverManagerDataSource(PG.getJdbcUrl() + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                PG.getUsername(), PG.getPassword());
    }
}
