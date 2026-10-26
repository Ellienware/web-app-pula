package com.momocircle.stokvel;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.dto.AssistantDtos.ActionType;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;

/** Structural guarantees that must keep holding: AI cannot act on stokvels; migrations stay sequential; no custody. */
class StokvelGuardrailsTest {

    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    @Test
    void assistantHasNoStokvelActionSoItCannotApproveSpendReorderOrPay() {
        for (ActionType t : ActionType.values()) {
            String n = t.name();
            assertFalse(n.contains("STOKVEL") || n.contains("APPROVE") || n.contains("PAYOUT") || n.contains("ROTATION")
                            || n.contains("SAVE_TO_BUY") || n.contains("CHECKOUT") || n.contains("PURCHASE") || n.contains("BUY")
                            || n.contains("QUOTE") || n.contains("SERVICE") || n.contains("BOOKING") || n.contains("MATERIAL")
                            || n.contains("SETTLE") || n.contains("PAYOUT") || n.contains("REFUND") || n.contains("RECONCIL") || n.contains("ADJUST"),
                    "AI action " + n + " would let the assistant act on stokvel, Save-to-Buy, services or settlement decisions");
        }
    }

    @Test
    void migrationsAreOrderedAndStokvelIsV34() throws IOException {
        List<Integer> versions;
        try (Stream<Path> s = Files.list(MIGRATIONS)) {
            Pattern p = Pattern.compile("V(\\d+)__.*\\.sql");
            versions = s.map(f -> p.matcher(f.getFileName().toString()))
                    .filter(Matcher::matches)
                    .map(m -> Integer.parseInt(m.group(1)))
                    .sorted()
                    .toList();
        }

        assertFalse(versions.isEmpty());
        assertEquals(versions.size(), new HashSet<>(versions).size(),
                "migration versions must be unique");

        assertTrue(versions.contains(34));
        assertTrue(Files.exists(MIGRATIONS.resolve("V34__stokvel.sql")));

        Path guards = Paths.get("src/main/resources/db/postgresql-guards");
        assertTrue(Files.exists(guards.resolve("V41__ledger_postgresql_guards.sql")));
        assertTrue(Files.exists(guards.resolve("V44__ledger_guard_business_owner.sql")));
    }

    @Test
    void stokvelMigrationCreatesNoWalletBalanceOrLedger() throws IOException {
        String sql = Files.readString(MIGRATIONS.resolve("V34__stokvel.sql")).toLowerCase();
        String noComments = sql.replaceAll("(?m)^\\s*--.*$", "");
        assertFalse(Pattern.compile("create\\s+table\\s+\\w*(wallet|balance|ledger)\\w*").matcher(noComments).find());
        assertFalse(noComments.contains("alter table circles"), "ordinary circles must not gain stokvel columns");
        assertFalse(noComments.contains("alter table circle_memberships"));
    }

    @Test
    void requestAmountIsImmutableAtTheMappingLevel() throws IOException {
        String src = Files.readString(Paths.get("src/main/java/com/momocircle/domain/StokvelRequest.java"));
        assertTrue(src.contains("updatable = false"), "request amount must be mapped updatable=false");
    }

    @Test
    void settlementMigrationAddsNoWalletLedgerOrDuplicatePayoutSystem() throws IOException {
        String sql = Files.readString(MIGRATIONS.resolve("V37__marketplace_settlement.sql")).toLowerCase().replaceAll("(?m)^\\s*--.*$", "");
        assertFalse(Pattern.compile("create\\s+table\\s+\\w*(wallet|balance|ledger|food_driver|stock_driver|service_driver)\\w*").matcher(sql).find());
        // the existing settlement table is generalised, not copied four times
        assertTrue(sql.contains("alter table restaurant_settlements"));
        assertFalse(Pattern.compile("create\\s+table\\s+(retail|stock|service|food)_settlements").matcher(sql).find());
        try (Stream<Path> files = Files.walk(Paths.get("src/main/java"))) {
            List<String> bad = files
                    .filter(Files::isRegularFile)
                    .map(f -> f.getFileName().toString())
                    .filter(n -> n.matches(
                            "(?i)(Food|Stock|Service|Retail)DriverPayout.*\\.java"
                            + "|(Retail|Stock|Service)SettlementService\\.java"))
                    .toList();

            assertTrue(bad.isEmpty(),
                    "duplicate settlement/payout infrastructure: " + bad);
        }
    }

    @Test
    void everyDomainSettlesThroughOneEngineWithOnePolicyEach() throws IOException {
        for (String domain : new String[] {"Retail", "Food", "Stock", "Service"}) {
            String src = Files.readString(Paths.get("src/main/java/com/momocircle/settlement/" + domain + "SettlementPolicy.java"));
            assertTrue(src.contains("implements SettlementPolicy"), domain + " must plug into the shared engine");
        }
        String engine = Files.readString(Paths.get("src/main/java/com/momocircle/settlement/MarketplaceSettlementService.java"));
        assertTrue(engine.contains("\"settlement:\" + type.name() + \":\" + sourceId"), "deterministic idempotency key");
    }
}
