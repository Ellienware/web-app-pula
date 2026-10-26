package com.momocircle.repository;

import com.momocircle.domain.StockCartItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockCartRepository extends JpaRepository<StockCartItem, String> {
    List<StockCartItem> findByBuyerBusinessIdOrderByCreatedAtAsc(String buyerBusinessId);
    Optional<StockCartItem> findByBuyerBusinessIdAndWholesaleProductId(String buyerBusinessId, String wholesaleProductId);
    void deleteByBuyerBusinessIdAndWholesaleProductId(String buyerBusinessId, String wholesaleProductId);
    void deleteByBuyerBusinessId(String buyerBusinessId);
}
