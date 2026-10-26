package com.momocircle.service;

import com.momocircle.domain.DeliveryRequest;
import com.momocircle.domain.DriverPayout;
import com.momocircle.domain.User;
import com.momocircle.domain.Order;
import com.momocircle.domain.enums.PayoutStatus;
import com.momocircle.domain.momoride.Driver;
import com.momocircle.payout.PayoutOrchestrator;
import com.momocircle.payout.PayoutProvider;
import com.momocircle.payout.SandboxPayoutProvider;
import com.momocircle.repository.DriverPayoutRepository;
import com.momocircle.repository.momoride.DriverRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** NOT VERIFIED IN THIS SANDBOX — no Maven network access. Run ./mvnw test locally. */
class PayoutServiceTest {

    private DriverPayoutRepository payoutRepo;
    private PayoutService service;
    private Driver driver;
    private DeliveryRequest delivery;

    @BeforeEach
    void setUp() {
        payoutRepo = mock(DriverPayoutRepository.class);
        when(payoutRepo.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(payoutRepo.save(any(DriverPayout.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new PayoutService(payoutRepo, mock(DriverRepository.class),
                new PayoutOrchestrator(new SandboxPayoutProvider()), mock(NotificationService.class));

        User u = new User();
        u.setId("drv-user");
        driver = new Driver();
        driver.setUser(u);

        Order o = new Order();
        o.setCurrency("ZAR");
        delivery = new DeliveryRequest();
        delivery.setOrder(o);
        delivery.setDriver(driver);
        delivery.setEstimatedFee(new BigDecimal("45.00"));
    }

    @Test
    void createsOnePayoutForServerSideFeeAndLabelsProviderSandbox() {
        service.createForCompletedDelivery(delivery);

        org.mockito.ArgumentCaptor<DriverPayout> cap = org.mockito.ArgumentCaptor.forClass(DriverPayout.class);
        verify(payoutRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        DriverPayout p = cap.getValue();
        assertEquals(0, new BigDecimal("45.00").compareTo(p.getAmount()));
        assertEquals("sandbox", p.getProvider());
        assertEquals(PayoutStatus.SUCCESSFUL, p.getStatus());
        assertEquals("payout:delivery:" + delivery.getId(), p.getIdempotencyKey());
    }

    @Test
    void explicitAmountPaysOnlyTheDriversShareUnderTheSameIdempotencyKey() {
        // PULA Food passes the 80% share of a R25 fee, not the full estimated fee.
        delivery.setEstimatedFee(new BigDecimal("25.00"));
        service.createForCompletedDelivery(delivery, new BigDecimal("20.00"));

        org.mockito.ArgumentCaptor<DriverPayout> cap = org.mockito.ArgumentCaptor.forClass(DriverPayout.class);
        verify(payoutRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        DriverPayout p = cap.getValue();
        assertEquals(0, new BigDecimal("20.00").compareTo(p.getAmount()));
        assertEquals("payout:delivery:" + delivery.getId(), p.getIdempotencyKey());
    }

    @Test
    void zeroExplicitAmountCreatesNoPayout() {
        service.createForCompletedDelivery(delivery, BigDecimal.ZERO);
        verify(payoutRepo, org.mockito.Mockito.never()).save(any(DriverPayout.class));
    }

    @Test
    void duplicatePayoutIsBlockedByIdempotencyKey() {
        when(payoutRepo.findByIdempotencyKey("payout:delivery:" + delivery.getId()))
                .thenReturn(Optional.of(new DriverPayout()));

        service.createForCompletedDelivery(delivery);

        verify(payoutRepo, never()).save(any());
    }

    @Test
    void noPayoutWhenThereIsNoDriverOrNoFee() {
        delivery.setEstimatedFee(BigDecimal.ZERO);
        service.createForCompletedDelivery(delivery);
        delivery.setEstimatedFee(new BigDecimal("10"));
        delivery.setDriver(null);
        service.createForCompletedDelivery(delivery);

        verify(payoutRepo, never()).save(any());
    }

    @Test
    void providerFailureIsRecordedAndNeverThrown() {
        PayoutProvider failing = new PayoutProvider() {
            public String name() { return "sandbox"; }
            public boolean sandbox() { return true; }
            public PayoutResult pay(PayoutRequest r) { throw new RuntimeException("boom"); }
        };
        PayoutService svc = new PayoutService(payoutRepo, mock(DriverRepository.class), new PayoutOrchestrator(failing), mock(NotificationService.class));

        svc.createForCompletedDelivery(delivery); // must not throw

        org.mockito.ArgumentCaptor<DriverPayout> cap = org.mockito.ArgumentCaptor.forClass(DriverPayout.class);
        verify(payoutRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        assertEquals(PayoutStatus.FAILED, cap.getValue().getStatus());
        assertTrue(cap.getValue().getFailureReason() != null);
    }

    @Test
    void payoutStateMachineRejectsIllegalTransitions() {
        DriverPayout p = new DriverPayout();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> p.transitionTo(PayoutStatus.SUCCESSFUL)); // PENDING -> SUCCESSFUL not allowed
    }
    @Test
    void failedPayoutIsRetriedWithTheSameIdempotencyKeyAndCanSucceed() {
        DriverPayout failed = new DriverPayout();
        failed.setDeliveryRequest(delivery);
        failed.setDriver(driver);
        failed.setAmount(new BigDecimal("45.00"));
        failed.setCurrency("ZAR");
        failed.setProvider("sandbox");
        failed.setIdempotencyKey("payout:delivery:" + delivery.getId());
        failed.transitionTo(PayoutStatus.PROCESSING);
        failed.transitionTo(PayoutStatus.FAILED);
        when(payoutRepo.findByStatusAndAttemptsLessThanAndUpdatedAtBefore(any(), org.mockito.ArgumentMatchers.anyInt(), any()))
                .thenReturn(java.util.List.of(failed));

        service.retryFailedPayouts();

        assertEquals(PayoutStatus.SUCCESSFUL, failed.getStatus());
        assertEquals(2, failed.getAttempts());
        assertEquals("payout:delivery:" + delivery.getId(), failed.getIdempotencyKey());
    }
}
