package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.momocircle.domain.Business;
import com.momocircle.domain.Order;
import com.momocircle.domain.OrderItem;
import com.momocircle.domain.Product;
import com.momocircle.domain.Review;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.OrderKind;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.ReviewTargetType;
import com.momocircle.dto.ReviewDtos.CreateReviewRequest;
import com.momocircle.dto.ReviewDtos.ReviewResponse;
import com.momocircle.dto.ReviewDtos.ReviewSummaryResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.ProductRepository;
import com.momocircle.repository.ReviewRepository;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * NOT VERIFIED IN THIS SANDBOX (no Maven) — run locally. Covers review eligibility (completed own
 * order), the retail vs food duplicate rules, food sub-ratings, server-set verification, the
 * database-race fallback, aggregates, and the rating breakdown.
 */
class ReviewServiceTest {

    private ReviewRepository reviews;
    private OrderRepository orders;
    private BusinessRepository businesses;
    private ProductRepository products;
    private ReviewService service;

    private User buyer;
    private Business business;
    private Product stew;

    @BeforeEach
    void setUp() {
        reviews = mock(ReviewRepository.class);
        orders = mock(OrderRepository.class);
        businesses = mock(BusinessRepository.class);
        products = mock(ProductRepository.class);
        service = new ReviewService(reviews, orders, businesses, products);

        buyer = new User();
        buyer.setId("buyer-1");
        buyer.setFullName("Thandi");
        business = new Business();
        business.setId("biz-1");
        stew = new Product();
        stew.setId("p-stew");
        stew.setBusiness(business);

        when(reviews.saveAndFlush(any(Review.class))).thenAnswer(i -> i.getArgument(0));
        when(reviews.save(any(Review.class))).thenAnswer(i -> i.getArgument(0));
        when(businesses.findById("biz-1")).thenReturn(Optional.of(business));
        when(products.findById("p-stew")).thenReturn(Optional.of(stew));
        when(reviews.averageRating(any(), anyString())).thenReturn(new BigDecimal("4.5"));
        when(reviews.countByTargetTypeAndTargetIdAndHiddenFalse(any(), anyString())).thenReturn(2L);
    }

    private Order order(String id, OrderKind kind, OrderStatus status, FulfilmentOption fulfilment) {
        Order o = new Order();
        o.setId(id);
        o.setBuyer(buyer);
        o.setBusiness(business);
        o.setKind(kind);
        o.setStatus(status);
        o.setFulfilmentMethod(fulfilment);
        OrderItem item = new OrderItem();
        item.setOrder(o);
        item.setProduct(stew);
        o.getItems().add(item);
        when(orders.findById(id)).thenReturn(Optional.of(o));
        return o;
    }

    private CreateReviewRequest simple(ReviewTargetType type, String targetId, String orderId) {
        return new CreateReviewRequest(type, targetId, orderId, 5, "great");
    }

    private CreateReviewRequest detailed(ReviewTargetType type, String targetId, String orderId, Integer taste, Integer delivery) {
        return new CreateReviewRequest(type, targetId, orderId, 5, "great", taste, 4, 5, 4, 3, delivery);
    }

    // ------------------------------------------------------------------ eligibility

    @Test
    void onlyTheBuyerOfACompletedOrderCanReview() {
        order("o1", OrderKind.FOOD, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        User stranger = new User();
        stranger.setId("someone-else");
        assertThrows(ApiException.class, () -> service.create(stranger, simple(ReviewTargetType.BUSINESS, "biz-1", "o1")));

        order("o2", OrderKind.FOOD, OrderStatus.PROCESSING, FulfilmentOption.CUSTOMER_PICKUP);
        assertThrows(ApiException.class, () -> service.create(buyer, simple(ReviewTargetType.BUSINESS, "biz-1", "o2")));
        order("o3", OrderKind.FOOD, OrderStatus.REFUNDED, FulfilmentOption.CUSTOMER_PICKUP);
        assertThrows(ApiException.class, () -> service.create(buyer, simple(ReviewTargetType.BUSINESS, "biz-1", "o3")));
        verify(reviews, never()).saveAndFlush(any());
    }

    @Test
    void theTargetMustBelongToTheOrder() {
        order("o1", OrderKind.FOOD, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        assertThrows(ApiException.class, () -> service.create(buyer, simple(ReviewTargetType.BUSINESS, "other-biz", "o1")));
        assertThrows(ApiException.class, () -> service.create(buyer, simple(ReviewTargetType.PRODUCT, "p-other", "o1")));
    }

    // ------------------------------------------------------------------ restaurant vs item reviews

    @Test
    void restaurantAndItemReviewsAreSeparateAndUpdateTheirOwnAggregates() {
        order("o1", OrderKind.FOOD, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);

        service.create(buyer, simple(ReviewTargetType.BUSINESS, "biz-1", "o1"));
        service.create(buyer, simple(ReviewTargetType.PRODUCT, "p-stew", "o1"));

        assertEquals(new BigDecimal("4.50"), business.getRatingAverage());
        assertEquals(2, business.getRatingCount());
        assertEquals(new BigDecimal("4.50"), stew.getRatingAverage());
        assertEquals(2, stew.getRatingCount());
    }

    // ------------------------------------------------------------------ duplicates

    @Test
    void foodAllowsOneReviewPerOrderAndTargetButNotTwoOnTheSameOrder() {
        order("o1", OrderKind.FOOD, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        when(reviews.existsByDedupeKey("buyer-1|o1|PRODUCT|p-stew")).thenReturn(true);
        ApiException ex = assertThrows(ApiException.class, () -> service.create(buyer, simple(ReviewTargetType.PRODUCT, "p-stew", "o1")));
        assertEquals("You have already reviewed this", ex.getMessage());
    }

    @Test
    void orderingTheSameDishAgainLetsYouReviewItAgain() {
        order("o1", OrderKind.FOOD, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        order("o2", OrderKind.FOOD, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        // the first order's review exists; the legacy global (author, target) check must NOT block a food re-order
        when(reviews.existsByDedupeKey("buyer-1|o1|PRODUCT|p-stew")).thenReturn(true);
        when(reviews.existsByAuthorIdAndTargetTypeAndTargetId("buyer-1", ReviewTargetType.PRODUCT, "p-stew")).thenReturn(true);

        ReviewResponse r = service.create(buyer, simple(ReviewTargetType.PRODUCT, "p-stew", "o2"));

        assertEquals(5, r.rating());
    }

    @Test
    void retailKeepsItsOriginalOncePerAuthorAndTargetRule() {
        order("o2", OrderKind.RETAIL, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        when(reviews.existsByAuthorIdAndTargetTypeAndTargetId("buyer-1", ReviewTargetType.PRODUCT, "p-stew")).thenReturn(true);
        assertThrows(ApiException.class, () -> service.create(buyer, simple(ReviewTargetType.PRODUCT, "p-stew", "o2")));
        verify(reviews, never()).saveAndFlush(any());
    }

    @Test
    void aSimultaneousDuplicateCaughtByTheDatabaseBecomesAConflict() {
        order("o1", OrderKind.FOOD, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        when(reviews.saveAndFlush(any(Review.class))).thenThrow(new DataIntegrityViolationException("uq_review_dedupe"));
        ApiException ex = assertThrows(ApiException.class, () -> service.create(buyer, simple(ReviewTargetType.PRODUCT, "p-stew", "o1")));
        assertEquals("You have already reviewed this", ex.getMessage());
    }

    @Test
    void theDedupeKeyStoredMatchesTheRule() {
        order("f1", OrderKind.FOOD, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        order("r1", OrderKind.RETAIL, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        org.mockito.ArgumentCaptor<Review> cap = org.mockito.ArgumentCaptor.forClass(Review.class);

        service.create(buyer, simple(ReviewTargetType.PRODUCT, "p-stew", "f1"));
        service.create(buyer, simple(ReviewTargetType.BUSINESS, "biz-1", "r1"));

        verify(reviews, org.mockito.Mockito.times(2)).saveAndFlush(cap.capture());
        assertEquals("buyer-1|f1|PRODUCT|p-stew", cap.getAllValues().get(0).getDedupeKey());
        assertEquals("buyer-1|BUSINESS|biz-1", cap.getAllValues().get(1).getDedupeKey());
    }

    // ------------------------------------------------------------------ sub-ratings and verification

    @Test
    void foodReviewsCarryOptionalSubRatingsAndAreMarkedVerifiedByTheServer() {
        order("o1", OrderKind.FOOD, OrderStatus.COMPLETED, FulfilmentOption.MOMORIDE_DELIVERY);
        ReviewResponse r = service.create(buyer, detailed(ReviewTargetType.BUSINESS, "biz-1", "o1", 5, 2));
        assertEquals(5, r.taste());
        assertEquals(4, r.portionSize());
        assertEquals(5, r.valueForMoney());
        assertEquals(4, r.foodQuality());
        assertEquals(3, r.packaging());
        assertEquals(2, r.deliveryExperience());
        assertTrue(r.verifiedOrder());
        assertEquals("PULA", r.source());
    }

    @Test
    void subRatingsAreRefusedOnRetailOrders() {
        order("o1", OrderKind.RETAIL, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        assertThrows(ApiException.class, () -> service.create(buyer, detailed(ReviewTargetType.BUSINESS, "biz-1", "o1", 5, null)));
        verify(reviews, never()).saveAndFlush(any());
    }

    @Test
    void deliveryExperienceNeedsADeliveredOrder() {
        order("o1", OrderKind.FOOD, OrderStatus.COMPLETED, FulfilmentOption.CUSTOMER_PICKUP);
        assertThrows(ApiException.class, () -> service.create(buyer, detailed(ReviewTargetType.BUSINESS, "biz-1", "o1", 5, 3)));
        // the same food sub-ratings without a delivery rating are fine for collection
        ReviewResponse r = service.create(buyer, detailed(ReviewTargetType.BUSINESS, "biz-1", "o1", 5, null));
        assertNull(r.deliveryExperience());
    }

    @Test
    void theRequestCannotChooseTheVerifiedFlagOrTheSource() {
        for (RecordComponent rc : CreateReviewRequest.class.getRecordComponents()) {
            String n = rc.getName().toLowerCase();
            assertFalse(n.contains("verified") || n.contains("source") || n.contains("external"), rc.getName());
        }
    }

    // ------------------------------------------------------------------ summary

    @Test
    void summaryAveragesEachDimensionAndKeepsDeliverySeparate() {
        Object[] row = {new BigDecimal("4.5"), 4L, new BigDecimal("4.0"), 4L, new BigDecimal("3.25"), 4L,
                null, 0L, new BigDecimal("3.0"), 2L, new BigDecimal("2.0"), 3L, 5L};
        when(reviews.dimensionAverages(ReviewTargetType.BUSINESS, "biz-1")).thenReturn(List.<Object[]>of(row));
        when(reviews.averageRating(ReviewTargetType.BUSINESS, "biz-1")).thenReturn(new BigDecimal("4.7"));
        when(reviews.countByTargetTypeAndTargetIdAndHiddenFalse(ReviewTargetType.BUSINESS, "biz-1")).thenReturn(5L);

        ReviewSummaryResponse s = service.summary(ReviewTargetType.BUSINESS, "biz-1");

        assertEquals(new BigDecimal("4.70"), s.overallAverage());
        assertEquals(5, s.overallCount());
        assertEquals(5, s.foodDimensions().size());
        assertEquals(new BigDecimal("4.50"), s.foodDimensions().get(0).average());
        assertNull(s.foodDimensions().get(3).average());           // nobody rated food quality
        assertEquals(0, s.foodDimensions().get(3).count());
        assertEquals("delivery experience", s.deliveryExperience().dimension());
        assertEquals(new BigDecimal("2.00"), s.deliveryExperience().average());
        assertTrue(s.foodDimensions().stream().noneMatch(d -> d.dimension().contains("delivery")));
        assertEquals(5, s.verifiedOrderCount());
        assertEquals("PULA", s.source());
    }

    @Test
    void summaryOfSomethingWithNoReviewsIsEmptyNotAnError() {
        when(reviews.dimensionAverages(ReviewTargetType.PRODUCT, "p-new")).thenReturn(List.of());
        when(reviews.averageRating(ReviewTargetType.PRODUCT, "p-new")).thenReturn(null);
        when(reviews.countByTargetTypeAndTargetIdAndHiddenFalse(ReviewTargetType.PRODUCT, "p-new")).thenReturn(0L);
        ReviewSummaryResponse s = service.summary(ReviewTargetType.PRODUCT, "p-new");
        assertEquals(BigDecimal.ZERO.setScale(2), s.overallAverage());
        assertEquals(0, s.overallCount());
        assertEquals(0, s.verifiedOrderCount());
    }
}
