package com.momocircle.dto;

import com.momocircle.branch.BranchReportMath;
import com.momocircle.domain.enums.OrderStatus;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Branch order visibility, fulfilment, parent-business reporting and the public branch directory. */
public final class BranchOpsDtos {

    private BranchOpsDtos() {}

    /** Deliberately small: no delivery address, phone or email of the customer. */
    public record BranchOrderView(String id, OrderStatus status, BigDecimal total, String currency, int itemCount, String buyerName,
                                  List<String> items, Instant createdAt) {}

    public record FulfilRequest(@NotNull OrderStatus status) {}

    /**
     * Net = gross minus COMPLETED refunds. These are order totals paid through the (simulated or provider) payment and are NOT
     * settled cash or bank deposits; settlement is a separate record.
     */
    public record BranchReportView(Instant from, Instant to, BranchReportMath.Report report, String notice) {}

    /** Public: only active branches, only information the business entered for customers. */
    public record DirectoryBranch(String id, String name, String branchCode, String address, String suburb, String city, String province,
                                  String postalCode, java.math.BigDecimal latitude, java.math.BigDecimal longitude, String phone,
                                  String email, List<String> operatingHours, boolean temporarilyClosed, String brandName,
                                  String operatingModel, String operatorName) {}

    /** Public: never exposes exact stock, only whether the item can be bought there. */
    public record DirectoryProduct(String productId, String name, String currency, BigDecimal price, boolean inStock) {}
}
