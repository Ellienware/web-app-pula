package com.momocircle.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

/** PULA Food: one chosen modifier on a cart line. Validated again, authoritatively, at checkout. */
@Entity
@Table(name = "cart_item_modifiers")
public class CartItemModifier {

    @Id
    @Column(length = 36, updatable = false, nullable = false)
    private String id = UUID.randomUUID().toString();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_item_id", nullable = false)
    private CartItem cartItem;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "modifier_id", nullable = false)
    private Modifier modifier;

    public String getId() { return id; }
    public CartItem getCartItem() { return cartItem; }
    public void setCartItem(CartItem cartItem) { this.cartItem = cartItem; }
    public Modifier getModifier() { return modifier; }
    public void setModifier(Modifier modifier) { this.modifier = modifier; }
}
