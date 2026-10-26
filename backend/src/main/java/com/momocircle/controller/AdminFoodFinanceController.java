package com.momocircle.controller;

import com.momocircle.domain.Order;
import com.momocircle.domain.enums.CancellationCause;
import com.momocircle.domain.enums.RefundStatus;
import com.momocircle.domain.enums.SettlementStatus;
import com.momocircle.dto.FoodFinanceDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRefundRepository;
import com.momocircle.repository.RestaurantSettlementRepository;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.AuditLogService;
import com.momocircle.service.FoodSettlementService;
import com.momocircle.service.OrderRefundService;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * PULA admin visibility over restaurant money: settlements, pending/failed payouts, refunds and refund
 * failures, merchant-protection compensation and PULA's fulfilment losses. Deliberately NO endpoint sets a
 * status by hand: PAID and REFUNDED only ever come from the payout / refund provider. Admins can hold or
 * release an unpaid settlement, retry a refund the provider failed, and issue a (partial) refund.
 */
@RestController
@RequestMapping("/api/admin/food-finance")
public class AdminFoodFinanceController {

    private final CurrentUserService currentUser;
    private final FoodSettlementService settlements;
    private final OrderRefundService refundService;
    private final RestaurantSettlementRepository settlementRepository;
    private final PaymentRefundRepository refundRepository;
    private final OrderRepository orderRepository;
    private final AuditLogService audit;

    public AdminFoodFinanceController(CurrentUserService currentUser, FoodSettlementService settlements, OrderRefundService refundService,
                                      RestaurantSettlementRepository settlementRepository, PaymentRefundRepository refundRepository,
                                      OrderRepository orderRepository, AuditLogService audit) {
        this.currentUser = currentUser;
        this.settlements = settlements;
        this.refundService = refundService;
        this.settlementRepository = settlementRepository;
        this.refundRepository = refundRepository;
        this.orderRepository = orderRepository;
        this.audit = audit;
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public FinanceSummary summary() {
        currentUser.requireAdmin();
        BigDecimal pending = nz(settlementRepository.sumNetByStatus(SettlementStatus.ELIGIBLE))
                .add(nz(settlementRepository.sumNetByStatus(SettlementStatus.PROCESSING)))
                .add(nz(settlementRepository.sumNetByStatus(SettlementStatus.FAILED)));
        long attention = refundRepository.findByStatusInOrderByCreatedAtDesc(List.of(RefundStatus.NEEDS_ATTENTION), PageRequest.of(0, 1000)).size();
        long failed = refundRepository.findByStatusInOrderByCreatedAtDesc(List.of(RefundStatus.FAILED), PageRequest.of(0, 1000)).size();
        long inProgress = refundRepository.findByStatusInOrderByCreatedAtDesc(List.of(RefundStatus.PENDING, RefundStatus.PROCESSING), PageRequest.of(0, 1000)).size();
        return new FinanceSummary(nz(settlementRepository.sumCommissionEarned()), nz(settlementRepository.sumPulaFulfilmentLoss()),
                pending, nz(settlementRepository.sumNetByStatus(SettlementStatus.PAID)),
                settlementRepository.countFoodByStatus(SettlementStatus.ELIGIBLE) + settlementRepository.countFoodByStatus(SettlementStatus.PROCESSING),
                settlementRepository.countFoodByStatus(SettlementStatus.FAILED), attention, failed, inProgress);
    }

    /** Optional status filter, e.g. ?status=FAILED,ON_HOLD for failed/held payouts. */
    @GetMapping("/settlements")
    @Transactional(readOnly = true)
    public List<SettlementView> settlements(@RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        currentUser.requireAdmin();
        List<SettlementStatus> statuses = status == null || status.isBlank() ? List.of()
                : Arrays.stream(status.split(",")).map(String::trim).map(SettlementStatus::valueOf).toList();
        return settlements.list(statuses, limit).stream().map(SettlementView::of).toList();
    }

    /** Merchant-protection compensation = PULA's fulfilment losses, one row each. */
    @GetMapping("/merchant-protection")
    @Transactional(readOnly = true)
    public List<SettlementView> merchantProtection(@RequestParam(defaultValue = "50") int limit) {
        currentUser.requireAdmin();
        return settlementRepository.findByTypeOrderByCreatedAtDesc(com.momocircle.domain.enums.SettlementType.MERCHANT_PROTECTION,
                PageRequest.of(0, Math.max(1, Math.min(limit, 200)))).stream().map(SettlementView::of).toList();
    }

    @GetMapping("/refunds")
    @Transactional(readOnly = true)
    public List<RefundView> refunds(@RequestParam(required = false) String status, @RequestParam(defaultValue = "50") int limit) {
        currentUser.requireAdmin();
        var page = PageRequest.of(0, Math.max(1, Math.min(limit, 200)));
        var rows = status == null || status.isBlank() ? refundRepository.findAllByOrderByCreatedAtDesc(page)
                : refundRepository.findByStatusInOrderByCreatedAtDesc(
                        Arrays.stream(status.split(",")).map(String::trim).map(RefundStatus::valueOf).toList(), page);
        return rows.stream().map(RefundView::of).toList();
    }

    @GetMapping("/orders/{orderId}/refunds")
    @Transactional(readOnly = true)
    public List<RefundView> orderRefunds(@PathVariable String orderId) {
        currentUser.requireAdmin();
        return refundRepository.findByOrderIdOrderByCreatedAtAsc(orderId).stream().map(RefundView::of).toList();
    }

    @PostMapping("/settlements/{id}/hold")
    public SettlementView hold(@PathVariable String id, @RequestBody(required = false) HoldRequest body) {
        var admin = currentUser.requireAdmin();
        var s = settlements.hold(id, body == null ? null : body.reason());
        audit.record(admin, "SETTLEMENT_HELD", "RestaurantSettlement", id, body == null ? "" : String.valueOf(body.reason()));
        return SettlementView.of(s);
    }

    @PostMapping("/settlements/{id}/release")
    public SettlementView release(@PathVariable String id) {
        var admin = currentUser.requireAdmin();
        var s = settlements.release(id);
        audit.record(admin, "SETTLEMENT_RELEASED", "RestaurantSettlement", id, "");
        return SettlementView.of(s);
    }

    /** A refund the provider reported FAILED can be retried as a new operation (the failed row stays as history). */
    @PostMapping("/refunds/{id}/retry")
    public RefundView retry(@PathVariable String id) {
        var admin = currentUser.requireAdmin();
        var r = refundService.retryFailed(id);
        audit.record(admin, "REFUND_RETRIED", "PaymentRefund", id, "new refund " + r.getId());
        return RefundView.of(r);
    }

    /** Admin (partial) refund. The operationKey makes a repeated click return the same refund, never a second one. */
    @PostMapping("/orders/{orderId}/refunds")
    @Transactional
    public RefundView partialRefund(@PathVariable String orderId, @RequestBody PartialRefundRequest body) {
        var admin = currentUser.requireAdmin();
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> ApiException.notFound("Order not found"));
        if (body == null || body.amount() == null) throw ApiException.badRequest("amount is required");
        var r = refundService.requestPartialRefund(order, body.amount(), CancellationCause.ADMIN_CANCELLED,
                null, body.note(), body.operationKey());
        audit.record(admin, "ORDER_PARTIAL_REFUND", "Order", orderId, body.amount() + " — refund " + r.getId() + " " + r.getStatus());
        return RefundView.of(r);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
}
