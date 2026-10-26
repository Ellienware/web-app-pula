package com.momocircle.service.momoride;

import com.momocircle.domain.User;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.domain.momoride.Ride;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.momoride.RideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
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
 * Proves the property MOMORIDE_PHASEF.md §23 asks for: two passengers racing
 * for the last seat on a ride must not both win. This replaces the earlier
 * implementation's {@code synchronized(rideId.intern())} — a JVM-local lock
 * that could not have protected seat counts across more than one application
 * instance — with real JPA optimistic locking on {@link Ride#getVersion()}.
 *
 * <p>Mirrors {@code AssistantProposalConcurrencyServiceTest}'s approach:
 * {@link RideRepository} is a Mockito mock, but {@code findById}/
 * {@code saveAndFlush} are stubbed with real, stateful, synchronized
 * behavior against an in-memory map that enforces the same
 * compare-the-version-then-write semantics a real database would via
 * {@code UPDATE ... WHERE id = ? AND version = ?} — not canned return
 * values — so a losing writer genuinely gets rejected, not scripted to fail.
 *
 * <p><b>Acknowledged limitation:</b> {@code @Transactional(REQUIRES_NEW)} has
 * no effect in a plain unit test (no Spring proxy is present), so this
 * verifies {@link RideSeatConcurrencyService}'s own claim/retry logic and the
 * stub's simulated row-locking, not Spring's real transaction propagation or
 * an actual database's row lock. True end-to-end verification needs a
 * {@code @SpringBootTest} against a real (H2/Postgres) database.
 *
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not be run here — see MOMORIDE_PHASEF.md.
 */
class RideSeatConcurrencyServiceTest {

    private final Map<String, Ride> store = new ConcurrentHashMap<>();
    private RideRepository repository;
    private RideSeatConcurrencyService service;
    private Driver driver;

    private static Ride copyOf(Ride src) {
        Ride copy = new Ride();
        copy.setId(src.getId());
        copy.setDriver(src.getDriver());
        copy.setOrigin(src.getOrigin());
        copy.setDestination(src.getDestination());
        copy.setDepartureTime(src.getDepartureTime());
        copy.setAvailableSeats(src.getAvailableSeats());
        copy.setPricePerPassenger(src.getPricePerPassenger());
        copy.setCurrency(src.getCurrency());
        copy.setStatus(src.getStatus());
        copy.setVersion(src.getVersion());
        return copy;
    }

    @BeforeEach
    void setUp() {
        store.clear();
        repository = mock(RideRepository.class);

        when(repository.findById(anyString())).thenAnswer(inv -> {
            Ride stored = store.get((String) inv.getArgument(0));
            return stored == null ? Optional.empty() : Optional.of(copyOf(stored));
        });

        when(repository.saveAndFlush(any(Ride.class))).thenAnswer(inv -> {
            Ride entity = inv.getArgument(0);
            synchronized (store) {
                Ride current = store.get(entity.getId());
                long currentVersion = current == null ? 0L : current.getVersion();
                if (!Objects.equals(entity.getVersion(), currentVersion)) {
                    throw new ObjectOptimisticLockingFailureException(Ride.class, entity.getId());
                }
                entity.setVersion(currentVersion + 1);
                store.put(entity.getId(), copyOf(entity));
                return entity;
            }
        });

        service = new RideSeatConcurrencyService(repository);

        User driverUser = new User();
        driverUser.setId("driver-user-1");
        driver = new Driver();
        driver.setId("driver-1");
        driver.setUser(driverUser);
    }

    private Ride rideWithSeats(int seats) {
        Ride ride = new Ride();
        ride.setDriver(driver);
        ride.setOrigin("Durban");
        ride.setDestination("Umhlanga");
        ride.setDepartureTime(LocalDateTime.now().plusHours(2));
        ride.setAvailableSeats(seats);
        ride.setPricePerPassenger(new BigDecimal("25"));
        ride.setCurrency("ZAR");
        ride.setStatus(Ride.Status.OPEN);
        ride.setVersion(0L);
        store.put(ride.getId(), copyOf(ride));
        return ride;
    }

    @Test
    void deterministicLastSeatRaceOnlyOneWriterWins() {
        Ride ride = rideWithSeats(1);

        Ride readByPassengerA = repository.findById(ride.getId()).orElseThrow();
        Ride readByPassengerB = repository.findById(ride.getId()).orElseThrow();
        assertEquals(1, readByPassengerA.getAvailableSeats());
        assertEquals(1, readByPassengerB.getAvailableSeats());

        readByPassengerA.setAvailableSeats(0);
        readByPassengerA.setStatus(Ride.Status.FULL);
        Ride savedA = repository.saveAndFlush(readByPassengerA);
        assertEquals(1L, savedA.getVersion());
        assertEquals(Ride.Status.FULL, store.get(ride.getId()).getStatus());

        readByPassengerB.setAvailableSeats(0);
        assertThrows(ObjectOptimisticLockingFailureException.class,
                () -> repository.saveAndFlush(readByPassengerB));

        assertEquals(0, store.get(ride.getId()).getAvailableSeats());
    }

    @Test
    void serviceRejectsSecondBookerForTheLastSeatAfterRetries() {
        Ride ride = rideWithSeats(1);

        // First booking succeeds via the real service path.
        Ride afterFirst = service.reserveSeatWithRetry(ride.getId(), "passenger-a");
        assertEquals(0, afterFirst.getAvailableSeats());
        assertEquals(Ride.Status.FULL, afterFirst.getStatus());

        // A second attempt against the same (now full) ride must be rejected cleanly, not
        // silently double-book the last seat.
        assertThrows(ApiException.class, () -> service.reserveSeatWithRetry(ride.getId(), "passenger-b"));
    }

    @Test
    void driverCannotBookTheirOwnRide() {
        Ride ride = rideWithSeats(3);
        assertThrows(ApiException.class,
                () -> service.reserveSeatWithRetry(ride.getId(), driver.getUser().getId()));
    }

    @RepeatedTest(5)
    void realConcurrencyStressOnlyOneWinsForTheLastSeat() throws Exception {
        Ride ride = rideWithSeats(1);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();

        try {
            var futures = new java.util.ArrayList<Future<?>>();
            for (int i = 0; i < threads; i++) {
                String passengerId = "passenger-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                        service.reserveSeatWithRetry(ride.getId(), passengerId);
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

        assertEquals(1, successes.get(), "exactly one passenger should win the single remaining seat");
        assertEquals(threads - 1, conflicts.get());
        assertEquals(0, store.get(ride.getId()).getAvailableSeats());
        assertTrue(store.get(ride.getId()).getVersion() >= 1);
    }
}
