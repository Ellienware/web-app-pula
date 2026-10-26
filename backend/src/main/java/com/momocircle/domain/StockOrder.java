package com.momocircle.domain;

import com.momocircle.domain.enums.StockFulfilmentOption;
import com.momocircle.domain.enums.StockOrderStatus;
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
 * A B2B stock procurement order from a buyer business to a single supplier
 * (brief §15). If the buyer's stock cart spans multiple suppliers, checkout
 * creates one StockOrder per supplier (matching the brief's "group into
 * separate supplier orders" instruction).
 *
 * Deliberately kept as a separate entity from {@link Order} (the consumer order)
 * rather than adding an order_type discriminator column. The state machines,
 * fulfilment options, and authorisation rules differ enough that sharing one
 * entity would require constant guard-clause branching throughout the service
 * layer — exactly what separate OrderStatus vs StockOrderStatus avoids.
 *
 * Payment: stock orders are paid through the same PaymentProvider abstraction
 * as consumer orders (Phase 13/14 will route them through Paystack). The
 * payment FK is nullable until checkout creates it, same pattern as Order.
 */
@Entity
@Table(name = "stock_orders")
public class StockOrder extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_business_id", nullable = false)
    private Business buyerBusiness;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_business_id", nullable = false)
    private Business supplierBusiness;

    @OneToMany(mappedBy = "stockOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    private java.util.List<StockOrderItem> items = new java.util.ArrayList<>();

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal deliveryFeeSnapshot;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, length = 3)
    private String currency = "ZAR";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StockOrderStatus status = StockOrderStatus.PENDING_PAYMENT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StockFulfilmentOption fulfilmentMethod;

    private String deliveryAddress;

    /**
     * Idempotency key supplied by the client at checkout — prevents duplicate
     * orders if the same request is retried (e.g. after a network timeout).
     * See PaymentIdempotencyService for the consumer-order equivalent.
     */
    @Column(unique = true, length = 100)
    private String idempotencyKey;

    /** Client checkout key, unique per (buyer, supplier) — see V20. */
    @Column(length = 100)
    private String checkoutGroupKey;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", unique = true)
    private Payment payment;

    public Business getBuyerBusiness() { return buyerBusiness; }
    public void setBuyerBusiness(Business buyerBusiness) { this.buyerBusiness = buyerBusiness; }
    public Business getSupplierBusiness() { return supplierBusiness; }
    public void setSupplierBusiness(Business supplierBusiness) { this.supplierBusiness = supplierBusiness; }
    public java.util.List<StockOrderItem> getItems() { return items; }
    public void setItems(java.util.List<StockOrderItem> items) { this.items = items; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getDeliveryFeeSnapshot() { return deliveryFeeSnapshot; }
    public void setDeliveryFeeSnapshot(BigDecimal deliveryFeeSnapshot) { this.deliveryFeeSnapshot = deliveryFeeSnapshot; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public StockOrderStatus getStatus() { return status; }
    public void setStatus(StockOrderStatus status) { this.status = status; }
    public StockFulfilmentOption getFulfilmentMethod() { return fulfilmentMethod; }
    public void setFulfilmentMethod(StockFulfilmentOption fulfilmentMethod) { this.fulfilmentMethod = fulfilmentMethod; }
    public String getDeliveryAddress() { return deliveryAddress; }
    public void setDeliveryAddress(String deliveryAddress) { this.deliveryAddress = deliveryAddress; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getCheckoutGroupKey() { return checkoutGroupKey; }
    public void setCheckoutGroupKey(String checkoutGroupKey) { this.checkoutGroupKey = checkoutGroupKey; }
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
