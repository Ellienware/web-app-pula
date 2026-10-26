package com.momocircle.service.ledger;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

/** V39 -> V40 upgrade and clean install on H2 (PostgreSQL mode). Not evidence about PostgreSQL. */
class MigrationUpgradeH2Test extends AbstractMigrationUpgradeTest {
    @Override
    DriverManagerDataSource freshDataSource() {
        return new DriverManagerDataSource("jdbc:h2:mem:mig" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
    }
}
