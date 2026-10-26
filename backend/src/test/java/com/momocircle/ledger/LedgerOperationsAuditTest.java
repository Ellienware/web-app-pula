package com.momocircle.ledger;

import com.momocircle.domain.User;
import com.momocircle.ledger.LedgerException.Code;
import com.momocircle.repository.UserRepository;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.ledger.LedgerOperationsService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Audit behaviour of the simulated-operations facade (no database; in-memory ledger store, recording audit sink). */
class LedgerOperationsAuditTest {

    record Entry(String action, String targetType, String targetId, String details) { }

    static final class RecordingAudit extends AuditLogService {
        final List<Entry> entries = new ArrayList<>();
        boolean failWrites;

        RecordingAudit() {
            super(null);
        }

        @Override
        public void record(User actor, String action, String targetType, String targetId, String details) {
            if (failWrites) throw new IllegalStateException("audit store down");
            entries.add(new Entry(action, targetType, targetId, details));
        }
    }

    private final InMemoryLedgerStore store = new InMemoryLedgerStore();
    private final AtomicLong seq = new AtomicLong();
    private final RecordingAudit audit = new RecordingAudit();
    private final UserRepository users = (UserRepository) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{UserRepository.class}, (p, m, a) -> null);
    private final LedgerEngine engine = new LedgerEngine(store, LedgerLimits.demoDefaults(),
            () -> "tx-" + seq.incrementAndGet(), Clock.fixed(Instant.parse("2026-10-13T00:00:00Z"), ZoneOffset.UTC));
    private final LedgerOperationsService ops = new LedgerOperationsService(engine, audit, users);

    private final Actor alice = Actor.ofUser("alice");
    private final Actor bob = Actor.ofUser("bob");

    LedgerOperationsAuditTest() {
        store.add(new Account("fund", Account.Kind.DEMO_FUNDING, null, "ZAR", true));
        store.add(new Account("clear", Account.Kind.WITHDRAWAL_CLEARING, null, "ZAR", true));
        store.add(new Account("w-alice", Account.Kind.USER_WALLET, "alice", "ZAR", false));
        store.add(new Account("w-bob", Account.Kind.USER_WALLET, "bob", "ZAR", false));
    }

    private static Money zar(long minor) { return new Money(minor, "ZAR"); }

    @Test
    void aSuccessfulSimulatedOperationIsAuditedOnceAndLabelledSimulated() {
        LedgerEngine.Result r = ops.issueDemoValue(alice, "w-alice", zar(10_000), "k1");
        assertEquals(1, audit.entries.size());
        Entry e = audit.entries.get(0);
        assertEquals("LEDGER_DEMO_ISSUANCE", e.action());
        assertEquals("LEDGER_TRANSACTION", e.targetType());
        assertEquals(r.tx().id(), e.targetId());
        assertTrue(e.details().startsWith("SIMULATED"));
        assertTrue(e.details().contains("100.00 ZAR"));
        assertTrue(e.details().contains("ref=SIM-"));
    }

    @Test
    void anIdempotentReplayIsNotAuditedAgain() {
        ops.issueDemoValue(alice, "w-alice", zar(10_000), "k1");
        LedgerEngine.Result again = ops.issueDemoValue(alice, "w-alice", zar(10_000), "k1");
        assertTrue(again.replayed());
        assertEquals(1, audit.entries.size());
    }

    @Test
    void securityRelevantDenialsAreAuditedAndStillPropagate() {
        ops.issueDemoValue(alice, "w-alice", zar(10_000), "seed");
        audit.entries.clear();
        LedgerException notOwner = assertThrows(LedgerException.class,
                () -> ops.transfer(bob, "w-alice", "w-bob", zar(100), "steal"));
        assertEquals(Code.NOT_OWNER, notOwner.code());
        LedgerException limit = assertThrows(LedgerException.class,
                () -> ops.issueDemoValue(alice, "w-alice", zar(500_001), "big"));
        assertEquals(Code.LIMIT_EXCEEDED, limit.code());
        assertEquals(2, audit.entries.size());
        assertEquals("LEDGER_INTERNAL_TRANSFER_DENIED", audit.entries.get(0).action());
        assertEquals("LEDGER_DEMO_ISSUANCE_DENIED", audit.entries.get(1).action());
        assertTrue(audit.entries.get(0).details().startsWith("SIMULATED denied code=NOT_OWNER"));
    }

    @Test
    void ordinaryBusinessFailuresLikeInsufficientFundsAreNotAuditedAsDenials() {
        ops.issueDemoValue(alice, "w-alice", zar(100), "seed");
        audit.entries.clear();
        assertThrows(LedgerException.class, () -> ops.transfer(alice, "w-alice", "w-bob", zar(101), "nsf"));
        assertTrue(audit.entries.isEmpty());
    }

    @Test
    void anAuditFailureNeverFailsOrUndoesAnAlreadyCommittedLedgerOperation() {
        audit.failWrites = true;
        LedgerEngine.Result r = ops.issueDemoValue(alice, "w-alice", zar(10_000), "k1");
        assertFalse(r.replayed());
        assertEquals(10_000L, store.balances.get("w-alice"));
        assertTrue(audit.entries.isEmpty());
    }

    @Test
    void reversalsAndWithdrawalOutcomesAreAuditedWithTheirReason() {
        ops.issueDemoValue(alice, "w-alice", zar(10_000), "seed");
        LedgerTransaction t = ops.transfer(alice, "w-alice", "w-bob", zar(300), "t1").tx();
        audit.entries.clear();
        ops.reverse(Actor.asAdmin("ops"), t.id(), "support ticket 42");
        LedgerTransaction w = ops.requestWithdrawal(alice, "w-alice", zar(100), "w1").tx();
        ops.failWithdrawal(Actor.asSystem(), w.id(), "simulated decline");
        List<String> actions = audit.entries.stream().map(Entry::action).toList();
        assertEquals(List.of("LEDGER_REVERSED", "LEDGER_WITHDRAWAL_REQUESTED", "LEDGER_WITHDRAWAL_FAILED"), actions);
        assertTrue(audit.entries.get(0).details().contains("support ticket 42"));
        assertTrue(audit.entries.get(2).details().contains("simulated decline"));
        assertTrue(audit.entries.stream().allMatch(e -> e.details().startsWith("SIMULATED")));
    }
}
