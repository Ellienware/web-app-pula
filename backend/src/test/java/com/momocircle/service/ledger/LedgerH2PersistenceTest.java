package com.momocircle.service.ledger;

import org.springframework.test.context.TestPropertySource;

/**
 * The shared persistence scenarios on H2 in PostgreSQL mode (the dev database). Flyway applies V1..V40 and Hibernate runs
 * {@code ddl-auto=validate}, so this also proves entity/schema agreement. H2 differs from PostgreSQL in locking and has no
 * PL/pgSQL, so it is NOT evidence of PostgreSQL behaviour; see LedgerPostgresPersistenceTest.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:ledger9a;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
class LedgerH2PersistenceTest extends AbstractLedgerPersistenceTest {
}
