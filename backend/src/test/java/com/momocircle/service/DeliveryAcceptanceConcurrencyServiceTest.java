package com.momocircle.service;

import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.Order;
import com.momocircle.domain.enums.DeliveryStatus;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.DeliveryRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves two drivers racing to accept the same delivery cannot both win —
 * the delivery equivalent of {@code RideSeatConcurrencyServiceTest}, whose
 * exact structure this mirrors (same repository-stubbing-with-real-
 * compare-and-swap-semantics approach, same real-threads stress test),
 * rather than inventing new test infrastructure for what's structurally
 * the same problem.
 *
 * <p>Same acknowledged limitation as its counterpart: {@code @Transactional
 * (REQUIRES_NEW)} has no effect in a plain unit test — this verifies
 * {@link DeliveryAcceptanceConcurrencyService}'s own claim/retry logic and
 * the stub's simulated row-locking, not Spring's real transaction
 * propagation. True end-to-end verification needs a {@code @SpringBootTest}.
 *
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class DeliveryAcceptanceConcurrencyServiceTest {

    private final Map<String, DeliveryRequest> store = new ConcurrentHashMap<>();
    private DeliveryRequestRepository repository;
    private DeliveryAcceptanceConcurrencyService service;

    private static DeliveryRequest copyOf(DeliveryRequest src) {
        DeliveryRequest copy = new DeliveryRequest();
        copy.setId(src.getId());
        copy.setOrder(src.getOrder());
        copy.setDriver(src.getDriver());
        copy.setStatus(src.getStatus());
        copy.setPickupLocation(src.getPickupLocation());
        copy.setDropoffLocation(src.getDropoffLocation());
        copy.setAcceptedAt(src.getAcceptedAt());
        copy.setVersion(src.getVersion());
        return copy;
    }

    @BeforeEach
    void setUp() {
        store.clear();
        repository = mock(DeliveryRequestRepository.class);

        when(repository.findById(anyString())).thenAnswer(inv -> {
            DeliveryRequest stored = store.get((String) inv.getArgument(0));
            return stored == null ? Optional.empty() : Optional.of(copyOf(stored));
        });

        when(repository.saveAndFlush(any(DeliveryRequest.class))).thenAnswer(inv -> {
            DeliveryRequest entity = inv.getArgument(0);
            synchronized (store) {
                DeliveryRequest current = store.get(entity.getId());
                long currentVersion = current == null ? 0L : current.getVersion();
                if (!Objects.equals(entity.getVersion(), currentVersion)) {
                    throw new ObjectOptimisticLockingFailureException(DeliveryRequest.class, entity.getId());
                }
                entity.setVersion(currentVersion + 1);
                store.put(entity.getId(), copyOf(entity));
                return entity;
            }
        });

        service = new DeliveryAcceptanceConcurrencyService(repository);
    }

    private DeliveryRequest requestedDelivery() {
        DeliveryRequest delivery = new DeliveryRequest();
        delivery.setOrder(new Order());
        delivery.setPickupLocation("Thandi's Spaza, Durban");
        delivery.setDropoffLocation("12 Main Road, Durban");
        delivery.setStatus(DeliveryStatus.REQUESTED);
        delivery.setVersion(0L);
        store.put(delivery.getId(), copyOf(delivery));
        return delivery;
    }

    @Test
    void deterministicRaceOnlyOneDriverWins() {
        DeliveryRequest delivery = requestedDelivery();

        DeliveryRequest readByDriverA = repository.findById(delivery.getId()).orElseThrow();
        DeliveryRequest readByDriverB = repository.findById(delivery.getId()).orElseThrow();

        readByDriverA.setStatus(DeliveryStatus.ACCEPTED);
        DeliveryRequest savedA = repository.saveAndFlush(readByDriverA);
        assertEquals(1L, savedA.getVersion());
        assertEquals(DeliveryStatus.ACCEPTED, store.get(delivery.getId()).getStatus());

        readByDriverB.setStatus(DeliveryStatus.ACCEPTED);
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> repository.saveAndFlush(readByDriverB));
    }

    @Test
    void serviceRejectsSecondDriverAfterRetries() {
        DeliveryRequest delivery = requestedDelivery();

        DeliveryRequest afterFirst = service.acceptWithRetry(delivery.getId(), "driver-a");
        assertEquals(DeliveryStatus.ACCEPTED, afterFirst.getStatus());

        assertThrows(ApiException.class, () -> service.acceptWithRetry(delivery.getId(), "driver-b"));
    }

    @RepeatedTest(5)
    void realConcurrencyStressOnlyOneDriverWins() throws Exception {
        DeliveryRequest delivery = requestedDelivery();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < threads; i++) {
                String driverId = "driver-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                        service.acceptWithRetry(delivery.getId(), driverId);
                        successes.incrementAndGet();
                    } catch (ApiException e) {
                        conflicts.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }

        assertEquals(1, successes.get(), "exactly one driver should win an open delivery");
        assertEquals(threads - 1, conflicts.get());
        assertEquals(DeliveryStatus.ACCEPTED, store.get(delivery.getId()).getStatus());
    }

    @Test
    void driverCannotAcceptADeliveryTheCustomerHasAlreadyCancelled() {
        DeliveryRequest delivery = requestedDelivery();
        // The customer's no-driver cancellation commits first.
        DeliveryRequest cancelled = repository.findById(delivery.getId()).orElseThrow();
        cancelled.setStatus(DeliveryStatus.CANCELLED);
        repository.saveAndFlush(cancelled);

        ApiException ex = assertThrows(ApiException.class,
                () -> service.acceptWithRetry(delivery.getId(), "driver-a"));
        assertEquals(409, ex.getStatus().value());
        assertEquals(DeliveryStatus.CANCELLED, store.get(delivery.getId()).getStatus());
        assertEquals(null, store.get(delivery.getId()).getDriver());
    }

    @Test
    void driverWhoReadTheDeliveryBeforeCancellationCannotOverwriteIt() {
        DeliveryRequest delivery = requestedDelivery();
        DeliveryRequest readByDriver = repository.findById(delivery.getId()).orElseThrow(); // sees REQUESTED

        DeliveryRequest cancelled = repository.findById(delivery.getId()).orElseThrow();
        cancelled.setStatus(DeliveryStatus.CANCELLED);
        repository.saveAndFlush(cancelled); // customer cancellation wins and bumps the version

        readByDriver.setStatus(DeliveryStatus.ACCEPTED);
        assertThrows(ObjectOptimisticLockingFailureException.class, () -> repository.saveAndFlush(readByDriver));
        assertEquals(DeliveryStatus.CANCELLED, store.get(delivery.getId()).getStatus());
    }
}
