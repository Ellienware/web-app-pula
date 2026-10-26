package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * One line in a buyer's active cart. There is no separate Cart entity — a
 * user's cart is simply "their CartItem rows" — which keeps checkout a plain
 * read + recompute instead of a stateful object to keep in sync.
 */
@Entity
@Table(name = "cart_items", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "product_id", "modifier_signature"}))
public class CartItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    /** PULA Food: SHA-256 hex of the sorted modifier ids; '' for a plain item. Part of the line's identity. */
    @Column(name = "modifier_signature", nullable = false, length = 64)
    private String modifierSignature = "";

    @jakarta.persistence.OneToMany(mappedBy = "cartItem", cascade = jakarta.persistence.CascadeType.ALL,
            orphanRemoval = true, fetch = FetchType.EAGER)
    private java.util.List<CartItemModifier> modifiers = new java.util.ArrayList<>();

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Product getProduct() { return product; }
    public void setProduct(Product product) { this.product = product; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getModifierSignature() { return modifierSignature; }
    public void setModifierSignature(String modifierSignature) { this.modifierSignature = modifierSignature; }
    public java.util.List<CartItemModifier> getModifiers() { return modifiers; }
}
