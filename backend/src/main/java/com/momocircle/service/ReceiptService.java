package com.momocircle.service;

import com.momocircle.domain.Payment;
import com.momocircle.domain.Receipt;
import com.momocircle.domain.User;
import com.momocircle.dto.ReceiptDtos.ReceiptResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ReceiptRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Issues one Receipt per successful Payment. Called as a side effect from
 * ContributionService and OrderService once a charge comes back SUCCESSFUL —
 * never from anywhere a client could trigger it directly, since a receipt is
 * proof a real charge happened.
 */
@Service
public class ReceiptService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ReceiptRepository receiptRepository;

    public ReceiptService(ReceiptRepository receiptRepository) {
        this.receiptRepository = receiptRepository;
    }

    @Transactional
    public Receipt issue(Payment payment, User payer, String payeeLabel, String description) {
        // Idempotent: a retried caller (e.g. webhook replay) must never mint a second receipt for one payment.
        return receiptRepository.findByPaymentId(payment.getId()).orElseGet(() -> {
            Receipt receipt = new Receipt();
            receipt.setReceiptNumber(generateNumber());
            receipt.setPayment(payment);
            receipt.setPayer(payer);
            receipt.setPayeeLabel(payeeLabel);
            receipt.setDescription(description);
            receipt.setAmount(payment.getAmount());
            receipt.setCurrency(payment.getCurrency());
            receipt.setStatus(payment.getStatus());
            receipt.setProviderReference(payment.getProviderReference());
            return receiptRepository.save(receipt);
        });
    }

    @Transactional(readOnly = true)
    public ReceiptResponse get(User viewer, String receiptId) {
        Receipt receipt = receiptRepository.findById(receiptId)
                .orElseThrow(() -> ApiException.notFound("Receipt not found"));
        if (!receipt.getPayer().getId().equals(viewer.getId())) {
            throw ApiException.forbidden("This is not your receipt");
        }
        return toResponse(receipt);
    }

    @Transactional(readOnly = true)
    public Page<ReceiptResponse> mine(User payer, Pageable pageable) {
        return receiptRepository.findByPayerIdOrderByCreatedAtDesc(payer.getId(), pageable).map(this::toResponse);
    }

    private String generateNumber() {
        String suffix = String.format("%05d", RANDOM.nextInt(100_000));
        return "RCT-" + DATE_FMT.format(java.time.Instant.now()) + "-" + suffix;
    }

    private ReceiptResponse toResponse(Receipt r) {
        return new ReceiptResponse(r.getId(), r.getReceiptNumber(), r.getPayer().getFullName(), r.getPayeeLabel(),
                r.getDescription(), r.getAmount(), r.getCurrency(), r.getStatus(), r.getProviderReference(),
                r.getCreatedAt());
    }
}
