package com.momocircle.dto;

import com.momocircle.domain.enums.BulkAllocationStatus;
import com.momocircle.domain.enums.BulkGroupStatus;
import com.momocircle.domain.enums.BulkShortfallPolicy;
import com.momocircle.domain.enums.StockFulfilmentOption;
import com.momocircle.domain.enums.StockOrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * DTOs for bulk purchasing. Clients send QUANTITIES and the price they are ACKNOWLEDGING; the server computes every price,
 * discount, subtotal and total. Nothing here lets a client set a price.
 */
public class BulkDtos {

    // ---- Supplier tiers ----
    public record TierInput(@Min(1) int minPacks, @NotNull BigDecimal pricePerPack) {}
    public record SetTiersRequest(@NotNull @Size(max = 10) List<@Valid TierInput> tiers) {}
    public record TierView(int minPacks, BigDecimal pricePerPack) {}

    // ---- Group ----
    public record CreateGroupRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotBlank String wholesaleProductId,
            @Min(1) @Max(1_000_000) int targetPacks,
            @NotNull Instant deadline,
            BulkShortfallPolicy shortfallPolicy,
            @Min(1) @Max(168) Integer extensionHours,
            boolean allowSelfCollection,
            boolean allowSupplierDelivery,
            boolean allowPulaRide,
            @Size(max = 500) String collectionNote) {}

    public record UpdateGroupRequest(
            @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Min(1) @Max(1_000_000) Integer targetPacks,
            Instant deadline,
            BulkShortfallPolicy shortfallPolicy,
            @Size(max = 500) String collectionNote) {}

    public record CancelRequest(@Size(max = 500) String reason) {}

    /** What a business sees about the pricing of a group at a given quantity. Display only; the server re-computes on commit. */
    public record Quote(
            int packs,
            int expectedAggregatePacks,
            BigDecimal basePricePerPack,
            BigDecimal pricePerPack,
            BigDecimal discountPerPack,
            BigDecimal subtotal,
            BigDecimal deliveryFee,
            BigDecimal total,
            String currency,
            TierView nextTier,
            int packsToNextTier,
            boolean meetsMoq,
            boolean reachesTarget) {}

    public record GroupSummary(
            String id, String procurementReference, String name, String description,
            BulkGroupStatus status,
            String supplierBusinessId, String supplierBusinessName,
            String creatorBusinessId, String creatorBusinessName,
            String wholesaleProductId, String productName, int packSize,
            int moqPacks, int targetPacks, int aggregatePacks, int participantCount,
            BigDecimal basePricePerPack, BigDecimal currentPricePerPack, String currency,
            List<TierView> tiers, Instant deadline, BulkShortfallPolicy shortfallPolicy,
            boolean allowSelfCollection, boolean allowSupplierDelivery, boolean allowPulaRide, String collectionNote) {}

    /** Participant row visible to the creator and supplier: quantities and status only, never payments or addresses. */
    public record ParticipantView(String businessId, String businessName, BulkAllocationStatus status,
                                  int requestedPacks, Integer confirmedPacks) {}

    public record GroupDetail(
            GroupSummary group,
            /** The viewer's relationship to the group: CREATOR, PARTICIPANT, SUPPLIER or BROWSER. */
            String viewerRole,
            AllocationView myAllocation,
            /** Creator/supplier only. Null for everyone else, so one business cannot see another's position. */
            List<ParticipantView> participants,
            BigDecimal finalPricePerPack, Integer finalAggregatePacks,
            Integer supplierConfirmedPacks, String supplierReference, String supplierNote, String cancelReason,
            Instant finalisedAt, Instant orderPlacedAt) {}

    // ---- Allocation ----
    public record JoinRequest(
            @Min(1) @Max(1_000_000) int requestedPacks,
            @NotNull StockFulfilmentOption fulfilmentMethod,
            @Size(max = 500) String deliveryAddress,
            String saveToBuyRequestId) {}

    public record UpdateAllocationRequest(
            @Min(1) @Max(1_000_000) Integer requestedPacks,
            StockFulfilmentOption fulfilmentMethod,
            @Size(max = 500) String deliveryAddress) {}

    /** The business states the exact quantity and price it is agreeing to. A mismatch with the server's quote is rejected. */
    public record ConfirmRequest(
            @Min(1) int acceptedPacks,
            @NotNull BigDecimal acceptedPricePerPack) {}

    public record AllocationView(
            String id, String groupId, String procurementReference, String groupName,
            String businessId, String businessName,
            BulkAllocationStatus status,
            int requestedPacks, Integer confirmedPacks, Integer allocatedPacks, int shortagePacks, int issuePacks,
            BigDecimal agreedMaxPricePerPack,
            BigDecimal pricePerPack, BigDecimal discountPerPack, BigDecimal subtotal, BigDecimal deliveryFee,
            BigDecimal total, BigDecimal refundDueAmount, String currency,
            StockFulfilmentOption fulfilmentMethod, String deliveryAddress,
            String stockOrderId, StockOrderStatus fulfilmentStatus, String paymentStatus,
            /** True while the payment could not have moved real money (sandbox/mock provider). Never present sandbox as real. */
            boolean paymentSimulated,
            String saveToBuyRequestId,
            Quote quote) {}

    /** Outcome of a confirmation attempt. A changed price is reported as data, not as an error, so UIs can re-ask. */
    public record ConfirmOutcome(boolean confirmed, String reason, AllocationView allocation, Quote currentQuote) {}

    public record FinaliseRequest(boolean acceptBelowTarget, boolean dropUnconfirmed) {}

    /** outcome: FINALISED | UNCONFIRMED_PARTICIPANTS | RECONFIRMATION_REQUIRED | BELOW_MINIMUM | BELOW_TARGET */
    public record FinaliseOutcome(String outcome, String message, GroupDetail group, int unconfirmedCount, int reconfirmationCount) {}

    public record PlaceOrderRequest(boolean proceedWithPaidOnly, @Size(max = 500) String note) {}
    public record PlaceOrderOutcome(String outcome, String message, GroupDetail group, int unpaidCount) {}

    public record SupplierConfirmRequest(
            @Min(0) int confirmedPacks,
            @Size(max = 100) String supplierReference,
            @Size(max = 500) String note) {}

    /** type: DAMAGED | MISSING | PARTIAL_DELIVERY */
    public record ReportIssueRequest(@NotBlank String type, @Min(1) int packs, @Size(max = 500) String note) {}

    public record EventView(String id, String type, String message, Integer packs, BigDecimal amount,
                            String businessId, Instant createdAt) {}

    public record SupplierGroupView(
            GroupDetail group,
            int orderedPacks,
            int paidPacks,
            List<SupplierLine> lines) {}

    /** One business's order as the supplier needs it to fulfil: quantity, destination and the existing stock order id. */
    public record SupplierLine(String businessName, int packs, StockFulfilmentOption fulfilmentMethod, String deliveryAddress,
                               String stockOrderId, StockOrderStatus orderStatus, String paymentStatus, int shortagePacks, int issuePacks) {}
}
