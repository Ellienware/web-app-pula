package com.momocircle.services;

import static org.junit.jupiter.api.Assertions.*;

import com.momocircle.domain.enums.*;
import org.junit.jupiter.api.Test;

class ServiceLifecycleTest {

    @Test
    void happyPathRequestLifecycle() {
        ServiceRequestStatus[] path = {ServiceRequestStatus.DRAFT, ServiceRequestStatus.SUBMITTED, ServiceRequestStatus.QUOTED,
                ServiceRequestStatus.BOOKED, ServiceRequestStatus.IN_PROGRESS, ServiceRequestStatus.COMPLETED};
        for (int i = 0; i + 1 < path.length; i++) {
            assertTrue(ServiceLifecycle.canTransition(path[i], path[i + 1]), path[i] + " -> " + path[i + 1]);
        }
    }

    @Test
    void terminalRequestStatusesGoNowhereAndCannotBeSkippedInto() {
        for (ServiceRequestStatus t : new ServiceRequestStatus[] {ServiceRequestStatus.COMPLETED, ServiceRequestStatus.CANCELLED,
                ServiceRequestStatus.DECLINED, ServiceRequestStatus.EXPIRED}) {
            assertTrue(t.isTerminal());
            for (ServiceRequestStatus to : ServiceRequestStatus.values()) {
                assertFalse(ServiceLifecycle.canTransition(t, to), t + " must not move to " + to);
            }
        }
        // status tampering: skipping steps is refused
        assertFalse(ServiceLifecycle.canTransition(ServiceRequestStatus.DRAFT, ServiceRequestStatus.BOOKED));
        assertFalse(ServiceLifecycle.canTransition(ServiceRequestStatus.SUBMITTED, ServiceRequestStatus.COMPLETED));
        assertFalse(ServiceLifecycle.canTransition(ServiceRequestStatus.QUOTED, ServiceRequestStatus.IN_PROGRESS));
        assertFalse(ServiceLifecycle.canTransition(ServiceRequestStatus.BOOKED, ServiceRequestStatus.COMPLETED));
        assertFalse(ServiceLifecycle.canTransition(ServiceRequestStatus.IN_PROGRESS, ServiceRequestStatus.BOOKED));
    }

    @Test
    void bookingStatusesOnlyMoveForwardOrToCancelled() {
        assertTrue(ServiceLifecycle.canTransition(ServiceBookingStatus.BOOKED, ServiceBookingStatus.IN_PROGRESS));
        assertTrue(ServiceLifecycle.canTransition(ServiceBookingStatus.IN_PROGRESS, ServiceBookingStatus.COMPLETED));
        assertTrue(ServiceLifecycle.canTransition(ServiceBookingStatus.BOOKED, ServiceBookingStatus.CANCELLED));
        assertTrue(ServiceLifecycle.canTransition(ServiceBookingStatus.IN_PROGRESS, ServiceBookingStatus.CANCELLED));
        assertFalse(ServiceLifecycle.canTransition(ServiceBookingStatus.BOOKED, ServiceBookingStatus.COMPLETED));
        assertFalse(ServiceLifecycle.canTransition(ServiceBookingStatus.COMPLETED, ServiceBookingStatus.CANCELLED));
        assertFalse(ServiceLifecycle.canTransition(ServiceBookingStatus.CANCELLED, ServiceBookingStatus.BOOKED));
        assertFalse(ServiceLifecycle.canTransition(ServiceBookingStatus.COMPLETED, ServiceBookingStatus.IN_PROGRESS));
    }

    @Test
    void quoteStatusesCannotBeReopened() {
        assertTrue(ServiceLifecycle.canTransition(ServiceQuoteStatus.SUBMITTED, ServiceQuoteStatus.ACCEPTED));
        assertTrue(ServiceLifecycle.canTransition(ServiceQuoteStatus.SUBMITTED, ServiceQuoteStatus.CLARIFICATION_REQUESTED));
        assertTrue(ServiceLifecycle.canTransition(ServiceQuoteStatus.CLARIFICATION_REQUESTED, ServiceQuoteStatus.SUPERSEDED));
        assertFalse(ServiceLifecycle.canTransition(ServiceQuoteStatus.CLARIFICATION_REQUESTED, ServiceQuoteStatus.ACCEPTED));
        for (ServiceQuoteStatus t : new ServiceQuoteStatus[] {ServiceQuoteStatus.ACCEPTED, ServiceQuoteStatus.REJECTED,
                ServiceQuoteStatus.SUPERSEDED, ServiceQuoteStatus.EXPIRED}) {
            for (ServiceQuoteStatus to : ServiceQuoteStatus.values()) {
                assertFalse(ServiceLifecycle.canTransition(t, to));
            }
        }
    }
}
