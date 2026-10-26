package com.momocircle.controller;

import com.momocircle.domain.User;
import com.momocircle.dto.WalletDtos.AmountRequest;
import com.momocircle.dto.WalletDtos.TransferRequest;
import com.momocircle.dto.WalletDtos.TransactionView;
import com.momocircle.exception.ApiException;
import com.momocircle.security.CurrentUserService;
import com.momocircle.security.ServiceRateLimiter;
import com.momocircle.service.wallet.PaymentRequestService;
import com.momocircle.service.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Controller-level unit tests with mocks: the acting user always comes from the server-side principal. */
class WalletControllerTest {

    private CurrentUserService current;
    private WalletService wallet;
    private PaymentRequestService requests;
    private WalletController controller;
    private User me;

    @BeforeEach
    void setUp() {
        current = mock(CurrentUserService.class);
        wallet = mock(WalletService.class);
        requests = mock(PaymentRequestService.class);
        controller = new WalletController(current, wallet, requests, new ServiceRateLimiter());
        me = new User();
        me.setId("user-me");
    }

    @Test
    void unauthenticatedCallsNeverReachTheServices() {
        when(current.require()).thenThrow(ApiException.unauthorized("Not authenticated"));
        assertThrows(ApiException.class, () -> controller.overview());
        assertThrows(ApiException.class, () -> controller.transfer(new TransferRequest("PW-X", BigDecimal.ONE, null), "key-12345678"));
        assertThrows(ApiException.class, () -> controller.demoFunding(new AmountRequest(BigDecimal.ONE, null), "key-12345678"));
        verify(wallet, never()).transfer(
            any(com.momocircle.domain.User.class),
            anyString(),
            any(BigDecimal.class),
            anyString(),
            anyString()
        );

        verify(wallet, never()).fund(
            any(com.momocircle.domain.User.class),
            any(BigDecimal.class),
            anyString(),
            anyString()
        );
    }

    @Test
    void transfersAreExecutedAsThePrincipalAndSignalReplays() {
        when(current.require()).thenReturn(me);
        TransactionView view = new TransactionView("SIM-1", "INTERNAL_TRANSFER", "POSTED", "OUT", "5.00", "ZAR", "Bo N.", null, null, null, true, "n");
        when(wallet.transfer(eq(me), eq("PW-BOB"), any(), any(), eq("key-12345678"))).thenReturn(new WalletService.Posted(view, false));
        ResponseEntity<TransactionView> created = controller.transfer(new TransferRequest("PW-BOB", new BigDecimal("5.00"), "ZAR"), "key-12345678");
        assertEquals(201, created.getStatusCode().value());
        when(wallet.transfer(eq(me), eq("PW-BOB"), any(), any(), eq("key-12345678"))).thenReturn(new WalletService.Posted(view, true));
        assertEquals(200, controller.transfer(new TransferRequest("PW-BOB", new BigDecimal("5.00"), "ZAR"), "key-12345678").getStatusCode().value());
    }


    @Test
    void transferRateLimitStopsAbuseBeforeAnyMoneyMoves() {
        when(current.require()).thenReturn(me);

        TransactionView view = new TransactionView(
                "SIM-1", "INTERNAL_TRANSFER", "POSTED", "OUT",
                "1.00", "ZAR", "Bo N.", null, null, null, true, "n"
        );

        when(wallet.transfer(
                eq(me),
                eq("PW-BOB"),
                any(BigDecimal.class),
                org.mockito.ArgumentMatchers.isNull(),
                anyString()
        )).thenReturn(new WalletService.Posted(view, false));

        for (int i = 0; i < 30; i++) {
            controller.transfer(
                    new TransferRequest("PW-BOB", BigDecimal.ONE, null),
                    "key-" + i + "-12345678"
            );
        }

        ApiException ex = assertThrows(
                ApiException.class,
                () -> controller.transfer(
                        new TransferRequest("PW-BOB", BigDecimal.ONE, null),
                        "key-final-12345678"
                )
        );

        assertEquals(429, ex.getStatus().value());

        verify(wallet, org.mockito.Mockito.times(30)).transfer(
                eq(me),
                eq("PW-BOB"),
                any(BigDecimal.class),
                org.mockito.ArgumentMatchers.isNull(),
                anyString()
        );
    }

    @Test
    void recipientLookupIsRateLimited() {
        when(current.require()).thenReturn(me);
        for (int i = 0; i < 15; i++) {
            try { controller.lookup(new com.momocircle.dto.WalletDtos.RecipientLookupRequest("a" + i + "@x.y")); } catch (RuntimeException ignored) { }
        }
        assertEquals(429, assertThrows(ApiException.class,
                () -> controller.lookup(new com.momocircle.dto.WalletDtos.RecipientLookupRequest("z@x.y"))).getStatus().value());
    }

    @Test
    void adminEndpointsRequireTheAdminRole() {
        CurrentUserService c = mock(CurrentUserService.class);
        when(c.requireAdmin()).thenThrow(ApiException.forbidden("Admin access required"));
        WalletAdminController admin = new WalletAdminController(c, wallet);
        assertThrows(ApiException.class, () -> admin.complete("SIM-1"));
        assertThrows(ApiException.class, () -> admin.reconcile("PW-X"));
        verify(wallet, never()).completeWithdrawal(any(), any());
    }
}
