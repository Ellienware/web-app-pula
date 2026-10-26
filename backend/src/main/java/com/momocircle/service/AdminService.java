package com.momocircle.service;

import com.momocircle.domain.Order;
import com.momocircle.domain.Payment;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.PaymentStatus;
import com.momocircle.dto.AdminDtos.AdminUserResponse;
import com.momocircle.dto.AdminDtos.AuditLogResponse;
import com.momocircle.dto.AdminDtos.PlatformStatsResponse;
import com.momocircle.dto.MarketDtos.OrderResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.AuditLogRepository;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.CircleRepository;
import com.momocircle.repository.ContributionRepository;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Every write here is admin-authorization-gated (the controller resolves the
 * caller via CurrentUserService.requireAdmin() before any method runs) and
 * every write is audit-logged. Reuses ReceiptService's OrderResponse mapping
 * pattern rather than inventing a parallel one, to keep the shape identical
 * to what OrderService already returns to buyers/sellers.
 */
@Service
public class AdminService {

    private static final Set<OrderStatus> GMV_STATUSES = EnumSet.of(
            OrderStatus.PAID, OrderStatus.PROCESSING, OrderStatus.READY,
            OrderStatus.COMPLETED, OrderStatus.REFUND_PENDING, OrderStatus.REFUNDED);

    private final UserRepository userRepository;
    private final CircleRepository circleRepository;
    private final BusinessRepository businessRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ContributionRepository contributionRepository;
    private final AuditLogRepository auditLogRepository;
    private final AuditLogService auditLogService;
    private final ReviewService reviewService;
    private final OrderRefundService orderRefundService;

    public AdminService(UserRepository userRepository, CircleRepository circleRepository,
                         BusinessRepository businessRepository, OrderRepository orderRepository,
                         PaymentRepository paymentRepository, ContributionRepository contributionRepository,
                         AuditLogRepository auditLogRepository, AuditLogService auditLogService,
                         ReviewService reviewService, OrderRefundService orderRefundService) {
        this.userRepository = userRepository;
        this.circleRepository = circleRepository;
        this.businessRepository = businessRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.contributionRepository = contributionRepository;
        this.auditLogRepository = auditLogRepository;
        this.auditLogService = auditLogService;
        this.reviewService = reviewService;
        this.orderRefundService = orderRefundService;
    }

    @Transactional(readOnly = true)
    public Page<AdminUserResponse> listUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(this::toResponse);
    }

    @Transactional
    public void setUserActive(User admin, String userId, boolean active) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> ApiException.notFound("User not found"));
        target.setActive(active);
        userRepository.save(target);
        auditLogService.record(admin, active ? "USER_REACTIVATED" : "USER_SUSPENDED",
                "User", userId, "By " + admin.getFullName());
    }

    @Transactional
    public void setBusinessActive(User admin, String businessId, boolean active) {
        var business = businessRepository.findById(businessId)
                .orElseThrow(() -> ApiException.notFound("Business not found"));
        business.setActive(active);
        businessRepository.save(business);
        auditLogService.record(admin, active ? "BUSINESS_REACTIVATED" : "BUSINESS_DEACTIVATED",
                "Business", businessId, "By " + admin.getFullName());
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> listOrders(Pageable pageable) {
        // Admin oversight intentionally sees every order across every seller — unlike OrderService,
        // which always scopes to the caller's own buyer/seller relationship.
        return orderRepository.findAll(pageable).map(this::toOrderResponse);
    }

    /**
     * Admin refund of a paid order, now through the shared refund service: one idempotent refund operation
     * per order (a repeated click never refunds twice), Paystack payments really refunded through Paystack
     * (asynchronously: the order stays REFUND_PENDING until the provider confirms), and the restaurant
     * settlement rule applied. Refused when the restaurant has already been paid for the order.
     */
    @Transactional
    public OrderResponse forceRefund(User admin, String orderId, String reason) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> ApiException.notFound("Order not found"));
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.PROCESSING
                && order.getStatus() != OrderStatus.READY && order.getStatus() != OrderStatus.COMPLETED
                && order.getStatus() != OrderStatus.REFUND_PENDING) {
            throw ApiException.badRequest("Only paid orders can be refunded");
        }
        var refund = orderRefundService.adminFullRefund(order, reason);
        auditLogService.record(admin, "ORDER_REFUNDED", "Order", orderId,
                (reason != null ? reason + " — " : "") + "refund " + refund.getId() + " status: " + refund.getStatus());
        if (refund.getStatus() == com.momocircle.domain.enums.RefundStatus.FAILED
                || refund.getStatus() == com.momocircle.domain.enums.RefundStatus.NEEDS_ATTENTION) {
            throw ApiException.badRequest("Refund did not complete (" + refund.getStatus() + "). Order left as REFUND_PENDING.");
        }
        if (refund.getStatus() == com.momocircle.domain.enums.RefundStatus.PENDING
                && !"paystack".equals(refund.getProvider())) {
            throw ApiException.badRequest("Refund did not complete (provider status: " + refund.getProviderStatus()
                    + "). Order left as REFUND_PENDING.");
        }
        // Paystack refunds are asynchronous: PROCESSING is a normal, accepted outcome here.
        return toOrderResponse(orderRepository.findById(orderId).orElse(order));
    }

    @Transactional
    public void hideReview(User admin, String reviewId) {
        reviewService.moderateHide(reviewId);
        auditLogService.record(admin, "REVIEW_HIDDEN", "Review", reviewId, null);
    }

    @Transactional(readOnly = true)
    public Page<AuditLogResponse> listAuditLogs(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable).map(log -> new AuditLogResponse(
                log.getId(), log.getActor() != null ? log.getActor().getFullName() : "System",
                log.getAction(), log.getTargetType(), log.getTargetId(), log.getDetails(), log.getCreatedAt()));
    }

    @Transactional(readOnly = true)
    public PlatformStatsResponse stats() {
        return new PlatformStatsResponse(
                userRepository.count(),
                userRepository.countByActiveTrue(),
                circleRepository.count(),
                businessRepository.count(),
                orderRepository.count(),
                paymentRepository.countByStatus(PaymentStatus.SUCCESSFUL),
                paymentRepository.countByStatus(PaymentStatus.FAILED),
                contributionRepository.sumAllConfirmedAmount(PaymentStatus.SUCCESSFUL),
                orderRepository.sumTotalByStatusIn(GMV_STATUSES));
    }

    private AdminUserResponse toResponse(User u) {
        return new AdminUserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getPhoneNumber(),
                u.getRole(), u.isActive(), u.getCreatedAt());
    }

    private OrderResponse toOrderResponse(Order order) {
        List<com.momocircle.dto.MarketDtos.OrderItemResponse> items = order.getItems().stream()
                .map(i -> new com.momocircle.dto.MarketDtos.OrderItemResponse(
                        i.getProduct().getId(), i.getProductNameSnapshot(),
                        i.getUnitPriceSnapshot(), i.getQuantity(), i.getLineTotal()))
                .toList();
        return new OrderResponse(
                order.getId(), order.getBuyer().getId(), order.getBuyer().getFullName(),
                order.getBusiness().getId(), order.getBusiness().getName(),
                items, order.getSubtotal(), order.getTotal(), order.getCurrency(),
                order.getStatus(), order.getFulfilmentMethod(), order.getDeliveryAddress(), order.getCreatedAt());
    }
}
