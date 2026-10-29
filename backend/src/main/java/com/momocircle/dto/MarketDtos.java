package com.momocircle.dto;

import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.domain.enums.BusinessDocumentType;
import com.momocircle.domain.enums.BusinessRegistrationStatus;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.ProductStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class MarketDtos {

    private MarketDtos() {}

    // ---- Business ----

    public record CreateBusinessRequest(
            @NotBlank String name,
            String description,
            String logoUrl,
            @NotBlank String category,
            /** Required on creation: the owner must deliberately choose a type; there is no implicit default. */
            @NotNull(message = "Choose a business type") BusinessType businessType,
            String location,
            String contactPhone,
            String contactEmail,
            List<String> operatingHours,
            List<FulfilmentOption> fulfilmentOptions,
            /** What this business can transact (brief §6). Null/empty defaults to
             *  [CAN_SELL_TO_CONSUMERS] — every business can at least sell until it
             *  explicitly opts into procurement/supply/fleet capabilities. */
            List<BusinessCapability> capabilities,
            /** Self-declared only — never verified against CIPC. Null defaults to NOT_REGISTERED. */
            BusinessRegistrationStatus registrationStatus,
            /** Optional self-reported registration number. Not validated against any registry. */
            String registrationNumber,
            /** Optional. Null means no minimum. */
            BigDecimal minimumOrderAmount,
            /** Optional flat fee for SELLER_DELIVERY. Null means not configured. */
            BigDecimal deliveryFee
    ) {}

    public record UpdateBusinessRequest(
            String name,
            String description,
            String logoUrl,
            String category,
            BusinessType businessType,
            String location,
            String contactPhone,
            String contactEmail,
            List<String> operatingHours,
            List<FulfilmentOption> fulfilmentOptions,
            /** Null leaves capabilities unchanged; a non-null list replaces the whole set. */
            List<BusinessCapability> capabilities,
            /** Null leaves registration status unchanged. */
            BusinessRegistrationStatus registrationStatus,
            /** Null leaves registration number unchanged. Send "" to clear it. */
            String registrationNumber,
            Boolean active,
            BigDecimal minimumOrderAmount,
            BigDecimal deliveryFee
    ) {}

    public record BusinessResponse(
            String id,
            String ownerId,
            String ownerName,
            String name,
            String description,
            String logoUrl,
            String category,
            BusinessType businessType,
            String location,
            String contactPhone,
            String contactEmail,
            List<String> operatingHours,
            List<FulfilmentOption> fulfilmentOptions,
            List<BusinessCapability> capabilities,
            BusinessRegistrationStatus registrationStatus,
            String registrationNumber,
            boolean active,
            BigDecimal ratingAverage,
            int ratingCount,
            BigDecimal minimumOrderAmount,
            BigDecimal deliveryFee,
            Instant createdAt
    ) {}

    /**
     * What anyone (including anonymous callers) may see of a business: storefront fields only. Deliberately has NO owner id or
     * name, NO registration status or number, and no other internal fields. The business contact phone and email appear because
     * the public storefront has always shown them (BusinessProfilePage); the owner enters them as the business's trading contacts.
     * The full {@link BusinessResponse} is returned only to the owner and to admins.
     */
    public record PublicBusinessResponse(
            String id,
            String name,
            String description,
            String logoUrl,
            String category,
            BusinessType businessType,
            String location,
            String contactPhone,
            String contactEmail,
            List<String> operatingHours,
            List<FulfilmentOption> fulfilmentOptions,
            List<BusinessCapability> capabilities,
            boolean active,
            BigDecimal ratingAverage,
            int ratingCount,
            BigDecimal minimumOrderAmount,
            BigDecimal deliveryFee,
            Instant createdAt
    ) {}

    /**
     * Lightweight profile-completeness / registration-readiness checklist
     * (brief §8) — deliberately NOT a credit score. Every field reflects
     * real recorded data; nothing here is predictive or a lending signal.
     */
    public record BusinessProfileCompleteness(
            boolean businessName,
            boolean ownerInformation,
            boolean location,
            boolean businessType,
            boolean hasProducts,
            boolean registrationDetails,
            boolean supportingDocuments,
            int completedCount,
            int totalCount
    ) {}

    // ---- Business documents (brief §7-8) ----

    public record UploadBusinessDocumentRequest(
            @NotNull BusinessDocumentType documentType,
            @NotBlank String fileUrl,
            String note
    ) {}

    public record BusinessDocumentResponse(
            String id,
            String businessId,
            BusinessDocumentType documentType,
            String fileUrl,
            String note,
            Instant createdAt
    ) {}

    // ---- Product ----

    public record CreateProductRequest(
            @NotBlank String name,
            String description,
            List<String> imageUrls,
            @NotBlank String category,
            @NotNull @Positive BigDecimal price,
            String currency,
            boolean isService,
            @Min(0) int stock
    ) {}

    public record UpdateProductRequest(
            String name,
            String description,
            List<String> imageUrls,
            String category,
            BigDecimal price,
            Integer stock,
            ProductStatus status
    ) {}

    public record ProductResponse(
            String id,
            String businessId,
            String businessName,
            String name,
            String description,
            List<String> imageUrls,
            String category,
            BigDecimal price,
            String currency,
            boolean isService,
            int stock,
            ProductStatus status,
            String canonicalProductId,
            MatchClassification matchClassification,
            BigDecimal ratingAverage,
            int ratingCount,
            Instant createdAt
    ) {}

    // ---- Canonical products / price comparison ----

    public record CreateCanonicalProductRequest(
            @NotBlank String name,
            String brand,
            @NotBlank String category,
            String subcategory,
            String size,
            String unit,
            String barcode
    ) {}

    public record CanonicalProductResponse(
            String id,
            String name,
            String brand,
            String category,
            String subcategory,
            String size,
            String unit,
            String barcode,
            Instant createdAt
    ) {}

    public record LinkCanonicalProductRequest(
            /** Null clears the link (product reverts to UNIQUE_PRODUCT). */
            String canonicalProductId,
            /** EXACT_MATCH or SIMILAR_ALTERNATIVE. Null (with a non-null canonicalProductId) defaults to EXACT_MATCH. */
            com.momocircle.domain.enums.MatchClassification classification
    ) {}

    public record CanonicalProductSuggestionResponse(
            CanonicalProductResponse canonicalProduct,
            double score
    ) {}

    /** One seller's price for a canonical product, as shown in a comparison list. */
    public record ProductPriceComparisonEntry(
            String productId,
            String businessId,
            String businessName,
            BigDecimal price,
            String currency,
            boolean available
    ) {}

    public record ProductPriceComparisonResponse(
            String canonicalProductId,
            String canonicalProductName,
            List<ProductPriceComparisonEntry> exactMatches,
            /** Never mixed into exactMatches — see PriceComparisonService's own javadoc for why. */
            List<ProductPriceComparisonEntry> similarAlternatives
    ) {}

    /**
     * Wholesale comparison entry. normalizedPricePerItem is derived from the
     * pack price only when packSize is positive; the original pack price
     * remains authoritative for ordering.
     */
    public record WholesalePriceComparisonEntry(
            String wholesaleProductId,
            String supplierBusinessId,
            String supplierBusinessName,
            String name,
            BigDecimal pricePerPack,
            String currency,
            int packSize,
            int moq,
            int availableStock,
            String sellingMode,
            BigDecimal pricePerUnit,
            BigDecimal normalizedPricePerItem,
            boolean available
    ) {}

    /**
     * Exact matches and similar alternatives are deliberately separate lists.
     */
    public record WholesalePriceComparisonResponse(
            String canonicalProductId,
            String canonicalProductName,
            List<WholesalePriceComparisonEntry> exactMatches,
            List<WholesalePriceComparisonEntry> similarAlternatives
    ) {}

    // ---- Basket comparison ----

    public record BasketItemRequest(
            @NotBlank String canonicalProductId,
            @jakarta.validation.constraints.Min(1) int quantity
    ) {}

    public record CompareBasketRequest(
            @jakarta.validation.constraints.NotEmpty
            @jakarta.validation.constraints.Size(max = 50)
            List<BasketItemRequest> items
    ) {}

    /**
     * One seller's estimated total for the requested basket. "Estimated"
     * because, per the ecosystem brief's own language guidance for
     * projections, this sums listed prices at the requested quantities but
     * does not verify stock covers the full quantity, and includes no
     * delivery/service fees — see BasketComparisonService's javadoc.
     */
    public record StoreBasketTotal(
            String businessId,
            String businessName,
            String currency,
            BigDecimal estimatedSubtotal,
            int itemsFound,
            int itemsRequested,
            boolean completeBasket,
            List<String> missingCanonicalProductIds,
            /** Seller-configured flat delivery fee, if any — real data, never estimated. Null = not configured. */
            BigDecimal deliveryFee,
            /** estimatedSubtotal + deliveryFee (0 if unset). What "Smart Basket Optimization" actually compares on. */
            BigDecimal estimatedTotal,
            /** Seller-configured minimum order subtotal, if any. Null = no minimum. */
            BigDecimal minimumOrderAmount,
            /** False only when minimumOrderAmount is set and estimatedSubtotal falls short of it. */
            boolean meetsMinimumOrder
    ) {}

    /** One store's role in a multi-store plan that covers the whole basket using as few stores as possible. */
    public record FewestDeliveriesStop(
            String businessId,
            String businessName,
            List<String> canonicalProductIdsCoveredHere,
            BigDecimal subtotal,
            BigDecimal deliveryFee
    ) {}

    public record BasketComparisonResponse(
            List<StoreBasketTotal> storeTotals,
            /** Null if no single store carries every requested item. */
            StoreBasketTotal cheapestCompleteStore,
            /** Null unless at least two stores carry the complete basket. */
            BigDecimal potentialSavings,
            String currency,
            /**
             * A combination of stores that together cover every requested item using
             * as few stores as possible — a greedy set-cover, not a guaranteed
             * mathematically optimal one (documented honestly rather than implying
             * more precision than a greedy heuristic actually has). Null if no
             * combination of the stores that stock at least one item can cover the
             * whole basket at all.
             */
            List<FewestDeliveriesStop> fewestDeliveriesPlan,
            BigDecimal fewestDeliveriesPlanTotal
    ) {}

    // ---- Cart ----

    public record AddCartItemRequest(
            @NotBlank String productId,
            @Positive int quantity,
            /** PULA Food: optional modifier ids (sides/extras). Validated here and again at checkout. */
            List<String> modifierIds
    ) {
        public AddCartItemRequest(String productId, int quantity) {
            this(productId, quantity, null);
        }
    }

    public record UpdateCartItemRequest(
            @Positive int quantity
    ) {}

    public record CartModifierResponse(String modifierId, String name, BigDecimal priceDelta) {}

    public record CartItemResponse(
            String id,
            ProductResponse product,
            int quantity,
            BigDecimal lineTotal,
            /** PULA Food: chosen modifiers; empty for retail. lineTotal already includes them. */
            List<CartModifierResponse> modifiers
    ) {}

    public record CartResponse(
            List<CartItemResponse> items,
            BigDecimal total,
            String currency
    ) {}

    // ---- Orders ----

    /** Checkout is scoped to one business at a time; a multi-seller cart yields one order per business. */
    public record CheckoutRequest(
            @NotBlank String businessId,
            /** PULA Food: required when the business has branches, otherwise must be omitted. */
            String branchId,
            /** PULA Food: the customer's choice, required for food orders (CUSTOMER_PICKUP / MOMORIDE_DELIVERY / ...). */
            FulfilmentOption fulfilmentMethod,
            /** Required for delivery fulfilment. */
            String deliveryAddress,
            @jakarta.validation.constraints.Size(max = 500) String customerNote,
            /**
             * PULA Food: the total the customer reviewed in the quote. REQUIRED for food checkout (a missing
             * value is rejected). If the backend's price now differs (a price, fee or extra changed in
             * between) checkout is refused with a conflict instead of charging an amount the customer
             * hasn't seen. Ignored for non-food checkout.
             */
            BigDecimal expectedTotal,
            /**
             * Save-to-Buy: optional id of an APPROVED Save-to-Buy request naming this buyer. The backend validates it
             * and the order only REFERENCES it; the charge is always the backend-computed checkout total.
             */
            String saveToBuyRequestId
    ) {
        public CheckoutRequest(String businessId) {
            this(businessId, null, null, null, null, null, null);
        }

        public CheckoutRequest(String businessId, String branchId, FulfilmentOption fulfilmentMethod,
                               String deliveryAddress, String customerNote) {
            this(businessId, branchId, fulfilmentMethod, deliveryAddress, customerNote, null, null);
        }

        public CheckoutRequest(String businessId, String branchId, FulfilmentOption fulfilmentMethod,
                               String deliveryAddress, String customerNote, BigDecimal expectedTotal) {
            this(businessId, branchId, fulfilmentMethod, deliveryAddress, customerNote, expectedTotal, null);
        }
    }

    public record OrderModifierResponse(String name, BigDecimal priceDelta) {}

    public record OrderItemResponse(
            String productId,
            String productName,
            BigDecimal unitPrice,
            int quantity,
            BigDecimal lineTotal,
            List<OrderModifierResponse> modifiers
    ) {
        public OrderItemResponse(String productId, String productName, BigDecimal unitPrice, int quantity, BigDecimal lineTotal) {
            this(productId, productName, unitPrice, quantity, lineTotal, List.of());
        }
    }

    public record OrderResponse(
            String id,
            String buyerId,
            String buyerName,
            String businessId,
            String businessName,
            List<OrderItemResponse> items,
            BigDecimal subtotal,
            BigDecimal total,
            String currency,
            OrderStatus status,
            FulfilmentOption fulfilmentMethod,
            String deliveryAddress,
            Instant createdAt,
            /** PULA Food fields — null/RETAIL for ordinary orders. */
            com.momocircle.domain.enums.OrderKind kind,
            String branchId,
            com.momocircle.domain.enums.RestaurantDecision restaurantDecision,
            String rejectionReason,
            Instant acceptDeadlineAt,
            BigDecimal deliveryFee,
            String customerNote
    ) {
        public OrderResponse(String id, String buyerId, String buyerName, String businessId, String businessName,
                             List<OrderItemResponse> items, BigDecimal subtotal, BigDecimal total, String currency,
                             OrderStatus status, FulfilmentOption fulfilmentMethod, String deliveryAddress,
                             Instant createdAt) {
            this(id, buyerId, buyerName, businessId, businessName, items, subtotal, total, currency, status,
                    fulfilmentMethod, deliveryAddress, createdAt,
                    com.momocircle.domain.enums.OrderKind.RETAIL, null, null, null, null, null, null);
        }
    }

    public record UpdateOrderStatusRequest(
            @NotNull OrderStatus status
    ) {}

    /** PULA Food: one priced line in a checkout quote. lineTotal includes the chosen modifiers. */
    public record CheckoutQuoteLine(String productId, String name, int quantity, BigDecimal unitPrice,
                                    List<OrderModifierResponse> modifiers, BigDecimal lineTotal) {}

    /**
     * PULA Food: what checkout WOULD charge, computed by the same backend rules as the real checkout,
     * so the customer can review the full total before confirming. Nothing is reserved, claimed or
     * charged by asking for a quote, and the real checkout recomputes everything again.
     */
    public record CheckoutQuoteResponse(String businessId, String businessName, String branchId, String branchName,
                                        FulfilmentOption fulfilmentMethod, String deliveryAddress,
                                        List<CheckoutQuoteLine> lines, BigDecimal subtotal, BigDecimal deliveryFee,
                                        BigDecimal total, String currency, String customerNote) {}

    /** PULA Food: restaurant rejects a paid order. The reason is shown to the customer. */
    public record RejectOrderRequest(@jakarta.validation.constraints.Size(max = 255) String reason) {}

    public record SetOrderFulfilmentRequest(
            @NotNull FulfilmentOption fulfilmentMethod,
            /** Required when fulfilmentMethod is SELLER_DELIVERY or MOMORIDE_DELIVERY. */
            String deliveryAddress
    ) {}
}
