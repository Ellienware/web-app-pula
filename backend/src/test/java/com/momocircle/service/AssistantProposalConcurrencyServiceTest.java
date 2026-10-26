package com.momocircle.service;

import com.momocircle.domain.AssistantProposal;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.ProposalStatus;
import com.momocircle.dto.AssistantDtos.ActionType;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.AssistantProposalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the exact property Phase D.1.1 Task 1 asks for: two simultaneous
 * confirmation requests for the same proposal must not both execute — one
 * must win (claims EXECUTING) and the other must be rejected, never both
 * silently proceeding to run the underlying action twice.
 *
 * <p>{@code AssistantProposalRepository} is a Mockito mock, but
 * {@code findById}/{@code saveAndFlush} are stubbed with real, stateful,
 * synchronized behavior against a small in-memory map — not canned return
 * values — so the stub actually enforces the same optimistic-locking
 * semantics a real JPA provider + database would: a write whose
 * {@code version} doesn't match the currently-stored version is rejected,
 * exactly like a real {@code UPDATE ... WHERE id = ? AND version = ?} that
 * matched zero rows. This is what makes the test meaningful rather than
 * tautological — it exercises the same compare-and-swap logic the real
 * database would enforce, not just "the mock returns what I told it to."
 * (A Mockito mock rather than a hand-written fake of the full
 * {@code JpaRepository} interface specifically to avoid the risk of an
 * unverifiable, uncompiled fake silently missing/mismatching one of that
 * interface's many inherited methods.)
 *
 * <p><b>Three tests:</b> a deterministic interleaved simulation (guaranteed
 * reproducible, not subject to thread-scheduling flakiness) proving the raw
 * repository-level mechanism, a service-level test proving
 * {@link AssistantProposalConcurrencyService#claim} surfaces this correctly
 * as an {@code ApiException}, and a real multi-threaded stress test using
 * an actual thread pool + barrier for additional confidence under genuine
 * concurrency. All three must hold.
 *
 * <p><b>Acknowledged limitation:</b> {@code @Transactional(REQUIRES_NEW)}
 * has no effect in a plain unit test (no Spring proxy is present), so this
 * verifies {@link AssistantProposalConcurrencyService}'s own claim logic
 * and the stub's simulated row-locking, not Spring's real transaction
 * propagation or a real database's actual row lock. True end-to-end
 * verification needs a {@code @SpringBootTest} against a real database,
 * which this sandbox cannot run (see the Phase D.1.1 report).
 *
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not be run here.
 */
class AssistantProposalConcurrencyServiceTest {

    /** In-memory "database" the mocked repository's stubs read/write against. */
    private final Map<String, AssistantProposal> store = new ConcurrentHashMap<>();

    private AssistantProposalRepository repository;
    private AssistantProposalConcurrencyService service;
    private User owner;

    private static AssistantProposal copyOf(AssistantProposal src) {
        AssistantProposal copy = new AssistantProposal();
        copy.setId(src.getId());
        copy.setUser(src.getUser());
        copy.setCircleId(src.getCircleId());
        copy.setIntent(src.getIntent());
        copy.setParametersJson(src.getParametersJson());
        copy.setStatus(src.getStatus());
        copy.setExpiresAt(src.getExpiresAt());
        copy.setVersion(src.getVersion());
        return copy;
    }

    @BeforeEach
    void setUp() {
        store.clear();
        repository = mock(AssistantProposalRepository.class);

        // A fresh copy every read — mimics a real DB query returning a distinct managed
        // instance per call, which is what makes the version race possible/meaningful to test.
        when(repository.findById(anyString())).thenAnswer(inv -> {
            AssistantProposal stored = store.get((String) inv.getArgument(0));
            return stored == null ? Optional.empty() : Optional.of(copyOf(stored));
        });

        when(repository.saveAndFlush(any(AssistantProposal.class))).thenAnswer(inv -> {
            AssistantProposal entity = inv.getArgument(0);
            synchronized (store) {
                AssistantProposal current = store.get(entity.getId());
                Long currentVersion = current == null ? null : current.getVersion();
                if (!Objects.equals(entity.getVersion(), currentVersion)) {
                    // Exactly what a real `UPDATE ... WHERE id = ? AND version = ?` matching zero
                    // rows produces via Spring Data JPA's exception translation.
                    throw new ObjectOptimisticLockingFailureException(AssistantProposal.class, entity.getId());
                }
                entity.setVersion(currentVersion == null ? 0L : currentVersion + 1);
                store.put(entity.getId(), copyOf(entity));
                return entity;
            }
        });

        when(repository.save(any(AssistantProposal.class))).thenAnswer(inv -> {
            AssistantProposal entity = inv.getArgument(0);
            synchronized (store) {
                AssistantProposal current = store.get(entity.getId());
                long next = current == null || current.getVersion() == null ? 0L : current.getVersion() + 1;
                entity.setVersion(next);
                store.put(entity.getId(), copyOf(entity));
                return entity;
            }
        });

        service = new AssistantProposalConcurrencyService(repository);
        owner = new User();
        owner.setId("user-1");
    }

    private AssistantProposal freshPendingProposal() {
        AssistantProposal proposal = new AssistantProposal();
        proposal.setUser(owner);
        proposal.setCircleId("circle-1");
        proposal.setIntent(ActionType.CONTRIBUTE);
        proposal.setParametersJson("{\"amount\":5000}");
        proposal.setStatus(ProposalStatus.PENDING);
        proposal.setExpiresAt(Instant.now().plusSeconds(600));
        proposal.setVersion(0L);
        store.put(proposal.getId(), copyOf(proposal));
        return proposal;
    }

    @Test
    void deterministicInterleavedRaceOnlyOneWriterWins() {
        AssistantProposal proposal = freshPendingProposal();

        // Simulate the exact race: two requests both read the proposal (both see PENDING,
        // version=0) before either has written anything back.
        AssistantProposal readByRequestA = repository.findById(proposal.getId()).orElseThrow();
        AssistantProposal readByRequestB = repository.findById(proposal.getId()).orElseThrow();
        assertEquals(0L, readByRequestA.getVersion());
        assertEquals(0L, readByRequestB.getVersion());

        // Request A claims first — succeeds, store version becomes 1.
        readByRequestA.setStatus(ProposalStatus.EXECUTING);
        AssistantProposal savedA = repository.saveAndFlush(readByRequestA);
        assertEquals(1L, savedA.getVersion());
        assertEquals(ProposalStatus.EXECUTING, store.get(proposal.getId()).getStatus());

        // Request B still holds its stale version=0 copy and now tries to claim too — must be
        // rejected, exactly like a real `UPDATE ... WHERE version = 0` matching zero rows.
        readByRequestB.setStatus(ProposalStatus.EXECUTING);
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> repository.saveAndFlush(readByRequestB));

        // The stored row reflects exactly one successful claim, not two.
        assertEquals(ProposalStatus.EXECUTING, store.get(proposal.getId()).getStatus());
        assertEquals(1L, store.get(proposal.getId()).getVersion());
    }

    @Test
    void serviceLevelClaimRejectsTheSecondCallerAfterTheFirstSucceeds() {
        AssistantProposal proposal = freshPendingProposal();

        AssistantProposal claimed = service.claim(proposal.getId(), owner, "circle-1");
        assertEquals(ProposalStatus.EXECUTING, claimed.getStatus());

        // Second confirm for the same proposal — the store now reflects EXECUTING, so this is
        // rejected by the ordinary status check before even reaching the version-conflict path
        // (which is exactly correct: EXECUTING is itself proof someone already claimed it).
        ApiException ex = assertThrows(ApiException.class, () -> service.claim(proposal.getId(), owner, "circle-1"));
        assertTrue(ex.getMessage().toLowerCase().contains("already being processed"));
    }

    @RepeatedTest(5)
    void realConcurrentClaimsOnlyOneSucceeds() throws Exception {
        AssistantProposal proposal = freshPendingProposal();
        int threadCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        service.claim(proposal.getId(), owner, "circle-1");
                        successes.incrementAndGet();
                    } catch (ApiException e) {
                        conflicts.incrementAndGet();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }));
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown(); // release all threads at once for maximum contention
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(1, successes.get(), "exactly one of " + threadCount + " simultaneous claims must succeed");
        assertEquals(threadCount - 1, conflicts.get(), "every other claim must be rejected, not silently no-op");
        assertEquals(ProposalStatus.EXECUTING, store.get(proposal.getId()).getStatus());
    }
}
