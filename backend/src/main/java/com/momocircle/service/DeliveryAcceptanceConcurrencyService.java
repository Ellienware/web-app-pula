package com.momocircle.service;

import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.enums.DeliveryStatus;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.DeliveryRequestRepository;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Makes a driver accepting an open delivery safe under concurrent requests
 * for the same job - the MoMoMarket-delivery equivalent of
 * RideSeatConcurrencyService, following that class's exact, already-proven
 * shape rather than inventing a new one.
 *
 * This MUST be a separate Spring bean from DeliveryRequestService, not a
 * method on it: @Transactional(REQUIRES_NEW) only takes effect through
 * Spring's proxy, and a same-class call bypasses that proxy entirely - see
 * RideSeatConcurrencyService's own javadoc for the full explanation, which
 * applies identically here.
 */
@Service
public class DeliveryAcceptanceConcurrencyService {

    private final DeliveryRequestRepository deliveryRequestRepository;

    public DeliveryAcceptanceConcurrencyService(DeliveryRequestRepository deliveryRequestRepository) {
        this.deliveryRequestRepository = deliveryRequestRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DeliveryRequest accept(String deliveryRequestId, String driverId) {
        DeliveryRequest delivery = deliveryRequestRepository.findById(deliveryRequestId)
                .orElseThrow(() -> ApiException.notFound("Delivery request not found"));
        if (delivery.getStatus() != DeliveryStatus.REQUESTED) {
            throw ApiException.conflict("This delivery is no longer available.");
        }
        // Real protection against two drivers racing for the same job is the
        // @Version check inside saveAndFlush below, not this status check -
        // both could read REQUESTED before either commits. Whichever save
        // loses that race gets OptimisticLockingFailureException.
        Driver driverRef = new Driver();
        driverRef.setId(driverId);
        delivery.setDriver(driverRef);
        delivery.setStatus(DeliveryStatus.ACCEPTED);
        delivery.setAcceptedAt(Instant.now());
        return deliveryRequestRepository.saveAndFlush(delivery);
    }

    /** Retries accept() against a fresh read when it loses the optimistic-lock race. */
    public DeliveryRequest acceptWithRetry(String deliveryRequestId, String driverId) {
        int attempts = 0;
        while (true) {
            try {
                return accept(deliveryRequestId, driverId);
            } catch (OptimisticLockingFailureException e) {
                if (++attempts >= 5) {
                    throw ApiException.conflict("Someone else already accepted this delivery.");
                }
            }
        }
    }
}
