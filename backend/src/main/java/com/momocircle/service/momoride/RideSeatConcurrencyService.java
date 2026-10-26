package com.momocircle.service.momoride;

import com.momocircle.domain.momoride.Ride;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.momoride.RideRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes ride-seat booking safe under concurrent requests for the last seat —
 * the mobility-domain equivalent of {@link com.momocircle.service.PaymentIdempotencyService}
 * and {@link com.momocircle.service.AssistantProposalConcurrencyService}.
 *
 * <p>This MUST be a separate Spring bean from {@link MoMoRideService}, not a
 * private/protected method on it: {@code @Transactional(REQUIRES_NEW)} only
 * takes effect through Spring's proxy, and a same-class method call (e.g.
 * {@code MoMoRideService.book()} calling {@code this.reserveSeat()}) bypasses
 * that proxy entirely, silently running in whatever transaction the caller
 * is already in. Routing the call through this separate bean is what makes
 * each reservation attempt commit in its own, independent transaction so a
 * losing writer's {@link OptimisticLockingFailureException} is a clean,
 * retryable conflict rather than something that can only be caught by
 * poisoning the caller's own (possibly unrelated) transaction.
 *
 * <p>Relies on {@link Ride#getVersion()} (JPA {@code @Version}) — a real,
 * database-enforced optimistic lock that works correctly even if this app is
 * ever scaled to more than one instance, unlike a JVM-local
 * {@code synchronized} block.
 */
@Service
public class RideSeatConcurrencyService {

    private final RideRepository rides;

    public RideSeatConcurrencyService(RideRepository rides) {
        this.rides = rides;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Ride reserveSeat(String rideId, String requestingUserId) {
        Ride r = rides.findById(rideId).orElseThrow(() -> ApiException.notFound("Ride not found"));
        if (r.getStatus() != Ride.Status.OPEN || r.getAvailableSeats() <= 0) {
            throw ApiException.conflict("Ride is full or unavailable");
        }
        if (r.getDriver().getUser().getId().equals(requestingUserId)) {
            throw ApiException.badRequest("You cannot book your own ride");
        }
        r.setAvailableSeats(r.getAvailableSeats() - 1);
        if (r.getAvailableSeats() == 0) {
            r.setStatus(Ride.Status.FULL);
        }
        return rides.saveAndFlush(r);
    }

    /** Retries {@link #reserveSeat} against a fresh read when it loses the optimistic-lock race. */
    public Ride reserveSeatWithRetry(String rideId, String requestingUserId) {
        int attempts = 0;
        while (true) {
            try {
                return reserveSeat(rideId, requestingUserId);
            } catch (OptimisticLockingFailureException e) {
                if (++attempts >= 5) {
                    throw ApiException.conflict("Ride is full or unavailable");
                }
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseSeat(String rideId) {
        Ride r = rides.findById(rideId).orElseThrow();
        r.setAvailableSeats(r.getAvailableSeats() + 1);
        if (r.getStatus() == Ride.Status.FULL) {
            r.setStatus(Ride.Status.OPEN);
        }
        rides.saveAndFlush(r);
    }
}
