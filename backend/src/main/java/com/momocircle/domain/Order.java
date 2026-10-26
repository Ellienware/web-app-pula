package com.momocircle.domain;

import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A buyer's order against a single business, created atomically from the
 * buyer's cart at checkout. Every OrderItem freezes the product's price at
 * purchase time — later price changes on the Product must never alter this.
 */
@Entity
@Table(name = "orders")
public class Order extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "business_id", nullable = false)
    private Business business;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<OrderItem> items = new java.util.ArrayList<>();

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.PENDING_PAYMENT;

    /** How the buyer wants this order fulfilled. Null until the buyer/seller sets one. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private FulfilmentOption fulfilmentMethod;

    /** Required only when fulfilmentMethod is SELLER_DELIVERY or MOMORIDE_DELIVERY. */
    private String deliveryAddress;

    /**
     * Set once checkout creates the backing Payment — never set directly by a
     * client. DB-enforced via a unique FK (see V2__order_payment_fk.sql):
     * one order has at most one payment, and one payment backs at most one
     * order.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", unique = true)
    private Payment payment;

    // ---- PULA Food (all null/RETAIL for ordinary retail orders; see V28__food_ordering.sql) ----

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private com.momocircle.domain.enums.OrderKind kind = com.momocircle.domain.enums.OrderKind.RETAIL;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "branch_id")
    private BusinessBranch branch;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private com.momocircle.domain.enums.RestaurantDecision restaurantDecision;

    private java.time.Instant decisionAt;

    /** Set when payment is confirmed; a food order still PENDING after this is auto-rejected and refunded. */
    private java.time.Instant acceptDeadlineAt;

    @Column(length = 255)
    private String rejectionReason;

    /** Delivery fee as charged at checkout. Never recomputed; later steps allocate it from this snapshot. */
    @Column(precision = 19, scale = 2)
    private BigDecimal deliveryFeeSnapshot;

    @Column(length = 500)
    private String customerNote;

    public boolean isFood() { return kind == com.momocircle.domain.enums.OrderKind.FOOD; }
    public com.momocircle.domain.enums.OrderKind getKind() { return kind; }
    public void setKind(com.momocircle.domain.enums.OrderKind kind) { this.kind = kind; }
    public BusinessBranch getBranch() { return branch; }
    public void setBranch(BusinessBranch branch) { this.branch = branch; }
    public com.momocircle.domain.enums.RestaurantDecision getRestaurantDecision() { return restaurantDecision; }
    public void setRestaurantDecision(com.momocircle.domain.enums.RestaurantDecision d) { this.restaurantDecision = d; }
    public java.time.Instant getDecisionAt() { return decisionAt; }
    public void setDecisionAt(java.time.Instant decisionAt) { this.decisionAt = decisionAt; }
    public java.time.Instant getAcceptDeadlineAt() { return acceptDeadlineAt; }
    public void setAcceptDeadlineAt(java.time.Instant acceptDeadlineAt) { this.acceptDeadlineAt = acceptDeadlineAt; }
    /** Why a food order ended without completing; drives refund and settlement rules. Set explicitly, never inferred. */
    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @jakarta.persistence.Column(length = 32)
    private com.momocircle.domain.enums.CancellationCause cancellationCause;

    /** When the restaurant marked the food READY, i.e. when it was prepared. Null until then. */
    private java.time.Instant readyAt;

    public com.momocircle.domain.enums.CancellationCause getCancellationCause() { return cancellationCause; }
    public void setCancellationCause(com.momocircle.domain.enums.CancellationCause c) { this.cancellationCause = c; }
    public java.time.Instant getReadyAt() { return readyAt; }
    public void setReadyAt(java.time.Instant readyAt) { this.readyAt = readyAt; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }
    public BigDecimal getDeliveryFeeSnapshot() { return deliveryFeeSnapshot; }
    public void setDeliveryFeeSnapshot(BigDecimal deliveryFeeSnapshot) { this.deliveryFeeSnapshot = deliveryFeeSnapshot; }
    public String getCustomerNote() { return customerNote; }
    public void setCustomerNote(String customerNote) { this.customerNote = customerNote; }

    public User getBuyer() { return buyer; }
    public void setBuyer(User buyer) { this.buyer = buyer; }
    public Business getBusiness() { return business; }
    public void setBusiness(Business business) { this.business = business; }
    public java.util.List<OrderItem> getItems() { return items; }
    public void setItems(java.util.List<OrderItem> items) { this.items = items; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public FulfilmentOption getFulfilmentMethod() { return fulfilmentMethod; }
    public void setFulfilmentMethod(FulfilmentOption fulfilmentMethod) { this.fulfilmentMethod = fulfilmentMethod; }
    public String getDeliveryAddress() { return deliveryAddress; }
    public void setDeliveryAddress(String deliveryAddress) { this.deliveryAddress = deliveryAddress; }
    public Payment getPayment() { return payment; }
    public void setPayment(Payment payment) { this.payment = payment; }

    /** Set when this order was paid for under an approved stokvel request (tracking only; PULA holds no stokvel funds). */
    @Column(name = "stokvel_request_id", length = 36)
    private String stokvelRequestId;
    public String getStokvelRequestId() { return stokvelRequestId; }
    public void setStokvelRequestId(String stokvelRequestId) { this.stokvelRequestId = stokvelRequestId; }

    /** Set when this order was created from a Save-to-Buy request (tracking only; the order is a normal order). */
    @Column(name = "save_to_buy_request_id", length = 36)
    private String saveToBuyRequestId;
    public String getSaveToBuyRequestId() { return saveToBuyRequestId; }
    public void setSaveToBuyRequestId(String saveToBuyRequestId) { this.saveToBuyRequestId = saveToBuyRequestId; }
}
