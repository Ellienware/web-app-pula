package com.momocircle.services;

import com.momocircle.domain.enums.ServiceBookingStatus;
import com.momocircle.domain.enums.ServiceQuoteStatus;
import com.momocircle.domain.enums.ServiceRequestStatus;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.momocircle.domain.enums.ServiceRequestStatus.*;

/** Allowed status transitions. Every status change in the services code goes through these checks. */
public final class ServiceLifecycle {

    private ServiceLifecycle() {}

    private static final Map<ServiceRequestStatus, Set<ServiceRequestStatus>> REQUEST = new EnumMap<>(ServiceRequestStatus.class);
    static {
        REQUEST.put(DRAFT, EnumSet.of(SUBMITTED, CANCELLED));
        REQUEST.put(SUBMITTED, EnumSet.of(QUOTED, DECLINED, CANCELLED, EXPIRED));
        REQUEST.put(QUOTED, EnumSet.of(BOOKED, SUBMITTED, DECLINED, CANCELLED, EXPIRED));
        REQUEST.put(BOOKED, EnumSet.of(IN_PROGRESS, CANCELLED));
        REQUEST.put(IN_PROGRESS, EnumSet.of(COMPLETED, CANCELLED));
        REQUEST.put(COMPLETED, EnumSet.noneOf(ServiceRequestStatus.class));
        REQUEST.put(CANCELLED, EnumSet.noneOf(ServiceRequestStatus.class));
        REQUEST.put(DECLINED, EnumSet.noneOf(ServiceRequestStatus.class));
        REQUEST.put(EXPIRED, EnumSet.noneOf(ServiceRequestStatus.class));
    }

    private static final Map<ServiceBookingStatus, Set<ServiceBookingStatus>> BOOKING = new EnumMap<>(ServiceBookingStatus.class);
    static {
        BOOKING.put(ServiceBookingStatus.BOOKED, EnumSet.of(ServiceBookingStatus.IN_PROGRESS, ServiceBookingStatus.CANCELLED));
        BOOKING.put(ServiceBookingStatus.IN_PROGRESS, EnumSet.of(ServiceBookingStatus.COMPLETED, ServiceBookingStatus.CANCELLED));
        BOOKING.put(ServiceBookingStatus.COMPLETED, EnumSet.noneOf(ServiceBookingStatus.class));
        BOOKING.put(ServiceBookingStatus.CANCELLED, EnumSet.noneOf(ServiceBookingStatus.class));
    }

    private static final Map<ServiceQuoteStatus, Set<ServiceQuoteStatus>> QUOTE = new EnumMap<>(ServiceQuoteStatus.class);
    static {
        QUOTE.put(ServiceQuoteStatus.SUBMITTED, EnumSet.of(ServiceQuoteStatus.ACCEPTED, ServiceQuoteStatus.REJECTED,
                ServiceQuoteStatus.CLARIFICATION_REQUESTED, ServiceQuoteStatus.SUPERSEDED, ServiceQuoteStatus.EXPIRED));
        QUOTE.put(ServiceQuoteStatus.CLARIFICATION_REQUESTED, EnumSet.of(ServiceQuoteStatus.SUPERSEDED, ServiceQuoteStatus.REJECTED,
                ServiceQuoteStatus.EXPIRED));
        for (ServiceQuoteStatus s : new ServiceQuoteStatus[] {ServiceQuoteStatus.ACCEPTED, ServiceQuoteStatus.REJECTED,
                ServiceQuoteStatus.SUPERSEDED, ServiceQuoteStatus.EXPIRED}) {
            QUOTE.put(s, EnumSet.noneOf(ServiceQuoteStatus.class));
        }
    }

    public static boolean canTransition(ServiceRequestStatus from, ServiceRequestStatus to) {
        return REQUEST.get(from).contains(to);
    }

    public static boolean canTransition(ServiceBookingStatus from, ServiceBookingStatus to) {
        return BOOKING.get(from).contains(to);
    }

    public static boolean canTransition(ServiceQuoteStatus from, ServiceQuoteStatus to) {
        return QUOTE.get(from).contains(to);
    }
}
