package com.momocircle.dto;

import com.momocircle.branch.BranchMovementType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Branch stock, prices and availability for NON-FOOD products. Food keeps its own per-branch availability on the food API. */
public final class BranchStockDtos {

    private BranchStockDtos() {}

    public record StockItemView(String productId, String productName, String currency, BigDecimal catalogPrice, boolean available,
                                BigDecimal priceOverride, BigDecimal effectivePrice, int quantity,
                                /** Unallocated business stock. Only the business owner sees it (null for everyone else). */
                                Integer centralStock) {}

    /** Only supplied fields change. Set {@code clearPriceOverride} to go back to the catalogue price. */
    public record SetItemRequest(Boolean available, BigDecimal priceOverride, boolean clearPriceOverride) {}

    public record MoveRequest(@NotBlank String productId, @Min(1) @Max(1_000_000) int quantity, @Size(max = 255) String note) {}

    public record TransferRequest(@NotBlank String toBranchId, @NotBlank String productId, @Min(1) @Max(1_000_000) int quantity,
                                  @Size(max = 255) String note) {}

    public record MovementView(String id, BranchMovementType type, String productId, String fromBranchId, String toBranchId,
                               int quantity, String orderId, String actorUserId, String note, Instant createdAt) {}

    public record AvailabilityItem(@NotBlank String productId, @Min(1) @Max(10_000) int quantity) {}

    public record AvailabilityRequest(@NotNull @Size(min = 1, max = 100) List<AvailabilityItem> items) {}

    /** Whether one branch could fill the whole basket right now. Quantities are never exposed, only yes/no and the reason. */
    public record BranchAvailabilityView(String branchId, String branchName, String city, String suburb, boolean canFulfil,
                                         BigDecimal total, String currency, List<String> problems) {}
}
