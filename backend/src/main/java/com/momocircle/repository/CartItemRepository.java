package com.momocircle.repository;

import com.momocircle.domain.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, String> {
    List<CartItem> findByUserIdOrderByCreatedAtAsc(String userId);
    Optional<CartItem> findByUserIdAndProductId(String userId, String productId);
    Optional<CartItem> findByUserIdAndProductIdAndModifierSignature(String userId, String productId, String modifierSignature);
    List<CartItem> findByUserIdAndProductBusinessId(String userId, String businessId);
    void deleteByUserIdAndProductBusinessId(String userId, String businessId);
}
