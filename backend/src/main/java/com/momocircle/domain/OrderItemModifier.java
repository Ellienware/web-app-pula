package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.UUID;

/** PULA Food: price/name snapshot of a modifier on a placed order. Never recomputed after payment. */
@Entity
@Table(name = "order_item_modifiers")
public class OrderItemModifier {

    @Id
    @Column(length = 36, updatable = false, nullable = false)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_item_id", nullable = false)
    private OrderItem orderItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "modifier_id")
    private Modifier modifier;

    @Column(name = "modifier_name_snapshot", nullable = false, length = 100)
    private String nameSnapshot;

    @Column(name = "price_delta_snapshot", nullable = false, precision = 19, scale = 2)
    private BigDecimal priceDeltaSnapshot;

    public String getId() { return id; }
    public OrderItem getOrderItem() { return orderItem; }
    public void setOrderItem(OrderItem orderItem) { this.orderItem = orderItem; }
    public Modifier getModifier() { return modifier; }
    public void setModifier(Modifier modifier) { this.modifier = modifier; }
    public String getNameSnapshot() { return nameSnapshot; }
    public void setNameSnapshot(String nameSnapshot) { this.nameSnapshot = nameSnapshot; }
    public BigDecimal getPriceDeltaSnapshot() { return priceDeltaSnapshot; }
    public void setPriceDeltaSnapshot(BigDecimal priceDeltaSnapshot) { this.priceDeltaSnapshot = priceDeltaSnapshot; }
}
