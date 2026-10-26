package com.momocircle.service.wallet;

import com.momocircle.domain.Business;
import com.momocircle.domain.Order;
import com.momocircle.domain.Payment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.ledger.Account;
import com.momocircle.ledger.Actor;
import com.momocircle.ledger.LedgerEngine;
import com.momocircle.ledger.LedgerException;
import com.momocircle.ledger.LedgerTransaction;
import com.momocircle.ledger.Money;
import com.momocircle.ledger.TxStatus;
import com.momocircle.ledger.TxType;
import com.momocircle.payment.PaymentRails;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.StockPaymentService;
import com.momocircle.service.ledger.LedgerAccountService;
import com.momocircle.service.ledger.LedgerOperationsService;
import com.momocircle.wallet.WalletException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Orchestration rules of {@link WalletCheckoutService} with the ledger, repositories and confirmation path mocked. The money
 * rules themselves (balances, idempotency, concurrency, reversal) are covered against the real engine in
 * {@code WalletCheckoutLedgerTest}. Everything here is SIMULATED wallet value.
 */
class WalletCheckoutServiceTest {

    private OrderRepository orders;
    private PaymentRepository payments;
    private LedgerAccountService accounts;
    private LedgerOperationsService ledger;
    private LedgerTransactionRepository txRepo;
    private StockPaymentService confirmer;
    private AuditLogService audit;
    private WalletCheckoutService service;

    private User buyer;
    private Order order;
    private Payment payment;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        payments = mock(PaymentRepository.class);
        accounts = mock(LedgerAccountService.class);
        ledger = mock(LedgerOperationsService.class);
        txRepo = mock(LedgerTransactionRepository.class);
        confirmer = mock(StockPaymentService.class);
        audit = mock(AuditLogService.class);
        service = new WalletCheckoutService(orders, payments, accounts, ledger, txRepo, confirmer, audit,
                mock(PlatformTransactionManager.class));

        buyer = new User();
        buyer.setId("buyer");
        Business shop = new Business();
        shop.setId("shop");
        shop.setActive(true);
        payment = new Payment();
        payment.setId("pay-1");
        payment.setProvider(PaymentRails.WALLET);
        payment.setAmount(new BigDecimal("25.50"));
        payment.setCurrency("ZAR");
        payment.setProviderReference("PULA-OD-order-1");
        payment.transitionTo(PaymentStatus.PROCESSING);
        order = new Order();
        order.setId("order-1");
        order.setBuyer(buyer);
        order.setBusiness(shop);
        order.setStatus(OrderStatus.PAYMENT_PROCESSING);
        order.setPayment(payment);
        when(orders.findById("order-1")).thenReturn(Optional.of(order));
        when(payments.findById("pay-1")).thenReturn(Optional.of(payment));
        when(accounts.getOrCreateWallet("buyer", "ZAR")).thenReturn(new Account("w-buyer", Account.Kind.USER_WALLET, "buyer", "ZAR", false));
        when(accounts.getOrCreateBusinessWallet("shop", "ZAR")).thenReturn(new Account("b-shop", Account.Kind.BUSINESS_WALLET, null, "shop", "ZAR", false));
    }

    private static LedgerEngine.Result posted(boolean replayed) {
        LedgerTransaction tx = new LedgerTransaction("tx-1", TxType.INTERNAL_TRANSFER, TxStatus.POSTED, "buyer", PaymentRails.ledgerKey("pay-1"),
                "fp", "ZAR", List.of(new com.momocircle.ledger.Posting("w-buyer", com.momocircle.ledger.Posting.Direction.DEBIT, 2_550, "ZAR"),
                new com.momocircle.ledger.Posting("b-shop", com.momocircle.ledger.Posting.Direction.CREDIT, 2_550, "ZAR")),
                null, "SIM-ABC", null, Instant.parse("2026-10-21T00:00:00Z"));
        return new LedgerEngine.Result(tx, replayed);
    }

    private static String codeOf(Runnable r) { return assertThrows(WalletException.class, r::run).code(); }

    @Test
    void confirmationAndAmountAreRequiredBeforeAnyLedgerCall() {
        assertEquals("CONFIRMATION_REQUIRED", codeOf(() -> service.pay(buyer, "order-1", false, new BigDecimal("25.50"))));
        assertEquals("AMOUNT_REQUIRED", codeOf(() -> service.pay(buyer, "order-1", true, null)));
        verify(ledger, never()).transfer(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void anotherUsersOrderIsReportedAsNotFoundAndNeverCharged() {
        User mallory = new User();
        mallory.setId("mallory");
        assertEquals("ORDER_NOT_FOUND", codeOf(() -> service.pay(mallory, "order-1", true, new BigDecimal("25.50"))));
        assertEquals("ORDER_NOT_FOUND", codeOf(() -> service.pay(buyer, "missing", true, new BigDecimal("25.50"))));
        verify(ledger, never()).transfer(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void anAmountThatDiffersFromTheStoredTotalIsRefusedNotCharged() {
        assertEquals("AMOUNT_CHANGED", codeOf(() -> service.pay(buyer, "order-1", true, new BigDecimal("1.00"))));
        verify(ledger, never()).transfer(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void anOrderNotCreatedForWalletPaymentCannotBePaidThisWay() {
        payment.setProvider("paystack-mock");
        assertEquals("NOT_WALLET_ORDER", codeOf(() -> service.pay(buyer, "order-1", true, new BigDecimal("25.50"))));
        verify(ledger, never()).transfer(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void aClosedOrFailedPaymentCannotBePaid() {
        order.setStatus(OrderStatus.CANCELLED);
        assertEquals("PAYMENT_CLOSED", codeOf(() -> service.pay(buyer, "order-1", true, new BigDecimal("25.50"))));
        verify(ledger, never()).transfer(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void anInactiveSellerBlocksPayment() {
        order.getBusiness().setActive(false);
        assertEquals("SELLER_UNAVAILABLE", codeOf(() -> service.pay(buyer, "order-1", true, new BigDecimal("25.50"))));
        verify(ledger, never()).transfer(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void insufficientBalancePropagatesAndNeverConfirmsOrFulfilsTheOrder() {
        when(ledger.transfer(any(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new LedgerException(LedgerException.Code.INSUFFICIENT_FUNDS, "Insufficient funds"));
        LedgerException e = assertThrows(LedgerException.class, () -> service.pay(buyer, "order-1", true, new BigDecimal("25.50")));
        assertEquals(LedgerException.Code.INSUFFICIENT_FUNDS, e.code());
        verify(confirmer, never()).confirmByReference(anyString());
        assertEquals(PaymentStatus.PROCESSING, payment.getStatus());
        assertEquals(OrderStatus.PAYMENT_PROCESSING, order.getStatus());
    }

    @Test
    void aSuccessfulDebitUsesTheDeterministicKeyTheBuyerAsActorAndThenConfirmsThroughTheSharedPath() {
        when(ledger.transfer(any(), anyString(), anyString(), any(), anyString())).thenReturn(posted(false));
        WalletCheckoutService.WalletPaymentResult r = service.pay(buyer, "order-1", true, new BigDecimal("25.50"));
        verify(ledger).transfer(eq(Actor.ofUser("buyer")), eq("w-buyer"), eq("b-shop"), eq(new Money(2_550, "ZAR")), eq("mkt:pay-1"));
        verify(confirmer).confirmByReference("PULA-OD-order-1");
        assertEquals("tx-1", payment.getLedgerTransactionId());
        assertTrue(r.simulated());
        assertEquals("SIM-ABC", r.ledgerReference());
    }

    @Test
    void paidIsReportedOnlyWhenTheBackendHasActuallyMarkedThePaymentSuccessful() {
        when(ledger.transfer(any(), anyString(), anyString(), any(), anyString())).thenReturn(posted(false));
        // The confirmation path did nothing (for example it was flagged for review): the UI must not be told "paid".
        WalletCheckoutService.WalletPaymentResult stillOpen = service.pay(buyer, "order-1", true, new BigDecimal("25.50"));
        assertFalse(stillOpen.paid());
        assertEquals(PaymentStatus.PROCESSING, stillOpen.paymentStatus());
    }

    @Test
    void aFailureAfterTheDebitDoesNotFailTheCallerBecauseTheSweepFinishesTheOrder() {
        when(ledger.transfer(any(), anyString(), anyString(), any(), anyString())).thenReturn(posted(false));
        org.mockito.Mockito.doThrow(new RuntimeException("notification down")).when(confirmer).confirmByReference(anyString());
        WalletCheckoutService.WalletPaymentResult r = service.pay(buyer, "order-1", true, new BigDecimal("25.50"));
        assertFalse(r.paid());
    }

    @Test
    void anAlreadyPaidOrderIsReportedWithoutChargingAgain() {
        payment.transitionTo(PaymentStatus.SUCCESSFUL);
        order.setStatus(OrderStatus.PAID);
        WalletCheckoutService.WalletPaymentResult r = service.pay(buyer, "order-1", true, new BigDecimal("25.50"));
        assertTrue(r.paid());
        assertTrue(r.replayed());
        verify(ledger, never()).transfer(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void orderStatusesMapToTheReconciliationViews() {
        assertEquals(com.momocircle.payment.WalletPaymentAudit.OrderView.AWAITING_PAYMENT, WalletCheckoutService.orderView(OrderStatus.PAYMENT_PROCESSING));
        assertEquals(com.momocircle.payment.WalletPaymentAudit.OrderView.PAID, WalletCheckoutService.orderView(OrderStatus.COMPLETED));
        assertEquals(com.momocircle.payment.WalletPaymentAudit.OrderView.REFUND_IN_PROGRESS, WalletCheckoutService.orderView(OrderStatus.REFUND_PENDING));
        assertEquals(com.momocircle.payment.WalletPaymentAudit.OrderView.REFUNDED, WalletCheckoutService.orderView(OrderStatus.REFUNDED));
    }
}
