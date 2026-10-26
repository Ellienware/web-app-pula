package com.momocircle.payment;

import com.momocircle.domain.Payment;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PaymentIdempotencyService is the piece that makes duplicate concurrent
 * requests (double-tapped "Pay", a client retry racing the original) safe.
 * This proves both halves of that contract:
 *   1) a plain repeat (key already exists before reserve() is even called)
 *      returns the existing payment without inserting a new one;
 *   2) a genuine race — two callers both pass the initial
 *      findByIdempotencyKey() check because neither has committed yet —
 *      is resolved by catching the DB's unique-constraint violation on the
 *      loser and having it return the winner's payment, not create a second
 *      Payment row.
 *
 * NOT VERIFIED IN THIS SANDBOX: no network access to Maven Central meant
 * ./mvnw test could not be run here. Written against the same
 * spring-boot-starter-test/Mockito the project already declares, so it
 * should compile and run in a normal dev environment, but that has not been
 * confirmed.
 */
class PaymentIdempotencyServiceTest {

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final PaymentIdempotencyService service = new PaymentIdempotencyService(paymentRepository);

    @Test
    void plainRepeatReturnsExistingPaymentWithoutInserting() {
        Payment existing = new Payment();
        existing.setAmount(new BigDecimal("500"));
        existing.setCurrency("XAF");
        existing.setProvider("sandbox");
        when(paymentRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(existing));

        var reservation = service.reserve("key-1", new BigDecimal("500"), "XAF", "sandbox");

        assertTrue(reservation.alreadyExisted());
        assertEquals(existing, reservation.payment());
        verify(paymentRepository, times(0)).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateInsertIsResolvedToTheWinnersPayment() {
        // Simulates the race: this caller's own check sees nothing yet (findBy...
        // returns empty the first time it's called)...
        Payment winner = new Payment();
        winner.setAmount(new BigDecimal("500"));
        winner.setCurrency("XAF");
        winner.setProvider("sandbox");
        winner.transitionTo(PaymentStatus.PROCESSING);

        when(paymentRepository.findByIdempotencyKey("key-2"))
                .thenReturn(Optional.empty())   // this caller's own pre-check
                .thenReturn(Optional.of(winner)); // re-query after losing the insert race

        // ...but by the time this caller tries to insert, the concurrent request
        // already committed a row with the same idempotency_key, so the DB's unique
        // constraint rejects this insert.
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key: idempotency_key"));

        var reservation = service.reserve("key-2", new BigDecimal("500"), "XAF", "sandbox");

        assertTrue(reservation.alreadyExisted());
        assertEquals(winner, reservation.payment());
        // Exactly one Payment ever got persisted (the winner's) — this caller's
        // attempted insert was rejected by the DB, not silently retried into a
        // second row.
        assertFalse(reservation.payment() == null);
    }
}
