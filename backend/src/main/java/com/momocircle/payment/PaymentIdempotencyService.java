package com.momocircle.payment;

import com.momocircle.domain.Payment;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.repository.PaymentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Makes "create a Payment for this idempotency key" safe under concurrent
 * duplicate requests (e.g. a double-tapped "Pay" button, or a client retry
 * racing the original request).
 *
 * <p>The naive pattern — {@code findByIdempotencyKey()} then insert if
 * absent — has a race window: two concurrent requests can both see "not
 * found" and both attempt to insert. The unique DB constraint on
 * {@code payments.idempotency_key} (see V1__init.sql) is what actually
 * prevents a double charge; this class exists to handle that constraint
 * violation gracefully instead of surfacing a raw 500.
 *
 * <p>Reservation runs in its own {@code REQUIRES_NEW} transaction so a
 * unique-constraint conflict here is caught and resolved locally, rather
 * than marking the caller's outer transaction (which may also be creating a
 * Contribution or Order) as rollback-only.
 */
@Service
public class PaymentIdempotencyService {

    private final PaymentRepository paymentRepository;

    public PaymentIdempotencyService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    public record Reservation(Payment payment, boolean alreadyExisted) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Reservation reserve(String idempotencyKey, BigDecimal amount, String currency, String provider) {
        var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new Reservation(existing.get(), true);
        }

        Payment payment = new Payment();
        payment.setAmount(amount);
        payment.setCurrency(currency);
        payment.setProvider(provider);
        payment.setIdempotencyKey(idempotencyKey);
        payment.transitionTo(PaymentStatus.PROCESSING);

        try {
            payment = paymentRepository.saveAndFlush(payment);
            return new Reservation(payment, false);
        } catch (DataIntegrityViolationException e) {
            // Lost the race: another request inserted this idempotency key between
            // our check above and our insert. That request is authoritative — return
            // its payment instead of failing this one.
            return paymentRepository.findByIdempotencyKey(idempotencyKey)
                    .map(winner -> new Reservation(winner, true))
                    .orElseThrow(() -> e);
        }
    }
}
