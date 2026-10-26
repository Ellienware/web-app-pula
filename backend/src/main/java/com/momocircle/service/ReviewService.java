package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.Order;
import com.momocircle.domain.Product;
import com.momocircle.domain.Review;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.domain.enums.ReviewTargetType;
import com.momocircle.dto.ReviewDtos.CreateReviewRequest;
import com.momocircle.dto.ReviewDtos.DimensionSummary;
import com.momocircle.dto.ReviewDtos.ReviewResponse;
import com.momocircle.dto.ReviewDtos.ReviewSummaryResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.ProductRepository;
import com.momocircle.repository.ReviewRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Only a buyer with a COMPLETED order against the target may review it, and
 * only once per (author, target). Eligibility is re-checked here on every
 * write — never trust a client's claim to have purchased something.
 */
@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final BusinessRepository businessRepository;
    private final ProductRepository productRepository;

    public ReviewService(ReviewRepository reviewRepository, OrderRepository orderRepository,
                          BusinessRepository businessRepository, ProductRepository productRepository) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.businessRepository = businessRepository;
        this.productRepository = productRepository;
    }

    /** Optional (field-injected) so existing construction is unchanged. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.momocircle.repository.ServiceListingRepository serviceListingRepository;

    /**
     * A review that comes from a COMPLETED, PAID service booking (extends this review system; no separate
     * ServiceReview). The caller (ServiceBookingService) has already verified the booking and the customer; this method
     * stores the review with a booking-scoped duplicate key and refreshes the shared rating aggregates.
     */
    @Transactional
    public ReviewResponse createForServiceBooking(User author, com.momocircle.domain.ServiceBooking booking,
                                                  ReviewTargetType type, String targetId, int rating, String text) {
        if (type != ReviewTargetType.BUSINESS && type != ReviewTargetType.SERVICE) {
            throw ApiException.badRequest("A service booking can be reviewed for the provider or the service");
        }
        if (rating < 1 || rating > 5) {
            throw ApiException.badRequest("Rating must be between 1 and 5");
        }
        String dedupeKey = author.getId() + "|SERVICE_BOOKING|" + booking.getId() + "|" + type + "|" + targetId;
        if (reviewRepository.existsByDedupeKey(dedupeKey)) {
            throw ApiException.conflict("You have already reviewed this");
        }
        Review review = new Review();
        review.setAuthor(author);
        review.setTargetType(type);
        review.setTargetId(targetId);
        review.setServiceBooking(booking);
        review.setRating(rating);
        review.setText(text);
        review.setVerifiedOrder(true);
        review.setSource("PULA");
        review.setDedupeKey(dedupeKey);
        try {
            review = reviewRepository.saveAndFlush(review);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict("You have already reviewed this");
        }
        refreshAggregate(type, targetId);
        return toResponse(review);
    }

    @Transactional
    public ReviewResponse create(User author, CreateReviewRequest req) {
        Order order = orderRepository.findById(req.orderId())
                .orElseThrow(() -> ApiException.notFound("Order not found"));

        if (!order.getBuyer().getId().equals(author.getId())) {
            throw ApiException.forbidden("You can only review your own orders");
        }
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw ApiException.badRequest("You can only review completed orders");
        }
        if (req.targetType() == ReviewTargetType.BUSINESS && !order.getBusiness().getId().equals(req.targetId())) {
            throw ApiException.badRequest("This order was not placed with that business");
        }
        if (req.targetType() == ReviewTargetType.PRODUCT) {
            boolean purchased = order.getItems().stream().anyMatch(i -> i.getProduct().getId().equals(req.targetId()));
            if (!purchased) {
                throw ApiException.badRequest("This order does not contain that product");
            }
        }
        boolean food = order.isFood();
        boolean hasSubRatings = req.taste() != null || req.portionSize() != null || req.valueForMoney() != null
                || req.foodQuality() != null || req.packaging() != null || req.deliveryExperience() != null;
        if (hasSubRatings && !food) {
            throw ApiException.badRequest("Detailed ratings are only available for food orders");
        }
        if (req.deliveryExperience() != null && (order.getFulfilmentMethod() == null
                || order.getFulfilmentMethod() == FulfilmentOption.CUSTOMER_PICKUP)) {
            throw ApiException.badRequest("You can only rate the delivery on an order that was delivered");
        }
        // Duplicate rule. Retail: once per (author, target), exactly as before. Food: once per
        // (author, order, target), so ordering the same dish again lets you review it again.
        // The key is also a DB unique column, so two simultaneous requests can't both get through.
        String dedupeKey = food
                ? author.getId() + "|" + order.getId() + "|" + req.targetType() + "|" + req.targetId()
                : author.getId() + "|" + req.targetType() + "|" + req.targetId();
        if (reviewRepository.existsByDedupeKey(dedupeKey)
                || (!food && reviewRepository.existsByAuthorIdAndTargetTypeAndTargetId(author.getId(), req.targetType(), req.targetId()))) {
            throw ApiException.conflict("You have already reviewed this");
        }
        Review review = new Review();
        review.setAuthor(author);
        review.setTargetType(req.targetType());
        review.setTargetId(req.targetId());
        review.setOrder(order);
        review.setRating(req.rating());
        review.setText(req.text());
        review.setTaste(req.taste());
        review.setPortionSize(req.portionSize());
        review.setValueForMoney(req.valueForMoney());
        review.setFoodQuality(req.foodQuality());
        review.setPackaging(req.packaging());
        review.setDeliveryExperience(req.deliveryExperience());
        // Set by the server only; never accepted from the client. Every review here comes from a
        // completed PULA order, and external reviews are never stored in this table.
        review.setVerifiedOrder(true);
        review.setSource("PULA");
        review.setDedupeKey(dedupeKey);
        try {
            review = reviewRepository.saveAndFlush(review);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw ApiException.conflict("You have already reviewed this");
        }
        refreshAggregate(req.targetType(), req.targetId());
        return toResponse(review);
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> list(ReviewTargetType targetType, String targetId, Pageable pageable) {
        return reviewRepository
                .findByTargetTypeAndTargetIdAndHiddenFalseOrderByCreatedAtDesc(targetType, targetId, pageable)
                .map(this::toResponse);
    }

    /**
     * Rating breakdown for a restaurant or a menu item. Food dimensions are averaged over the reviews
     * that gave them; the delivery experience is reported on its own because it describes the
     * delivery, not the food. All figures are PULA verified-order reviews; nothing external.
     */
    @Transactional(readOnly = true)
    public ReviewSummaryResponse summary(ReviewTargetType type, String targetId) {
        long overallCount = reviewRepository.countByTargetTypeAndTargetIdAndHiddenFalse(type, targetId);
        BigDecimal overall = reviewRepository.averageRating(type, targetId);
        java.util.List<Object[]> rows = reviewRepository.dimensionAverages(type, targetId);
        Object[] r = rows.isEmpty() ? new Object[13] : rows.get(0);
        java.util.List<DimensionSummary> food = java.util.List.of(
                dimension("taste", r[0], r[1]), dimension("portion size", r[2], r[3]),
                dimension("value for money", r[4], r[5]), dimension("food quality", r[6], r[7]),
                dimension("packaging", r[8], r[9]));
        long verified = r[12] == null ? 0 : ((Number) r[12]).longValue();
        return new ReviewSummaryResponse(type, targetId,
                overall == null ? BigDecimal.ZERO.setScale(2) : overall.setScale(2, RoundingMode.HALF_UP), overallCount,
                food, dimension("delivery experience", r[10], r[11]), verified, "PULA");
    }

    private static DimensionSummary dimension(String name, Object avg, Object count) {
        long n = count == null ? 0 : ((Number) count).longValue();
        BigDecimal a = avg == null ? null : new BigDecimal(((Number) avg).doubleValue()).setScale(2, RoundingMode.HALF_UP);
        return new DimensionSummary(name, a, n);
    }

    @Transactional
    public void moderateHide(String reviewId) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("Review not found"));
        review.setHidden(true);
        reviewRepository.save(review);
        refreshAggregate(review.getTargetType(), review.getTargetId());
    }

    private void refreshAggregate(ReviewTargetType type, String targetId) {
        BigDecimal avg = reviewRepository.averageRating(type, targetId);
        long count = reviewRepository.countByTargetTypeAndTargetIdAndHiddenFalse(type, targetId);
        BigDecimal rounded = avg == null ? BigDecimal.ZERO : avg.setScale(2, RoundingMode.HALF_UP);

        if (type == ReviewTargetType.BUSINESS) {
            Business business = businessRepository.findById(targetId).orElse(null);
            if (business != null) {
                business.setRatingAverage(rounded);
                business.setRatingCount((int) count);
                businessRepository.save(business);
            }
        } else if (type == ReviewTargetType.SERVICE) {
            if (serviceListingRepository != null) {
                com.momocircle.domain.ServiceListing listing = serviceListingRepository.findById(targetId).orElse(null);
                if (listing != null) {
                    listing.setRatingAverage(rounded);
                    listing.setRatingCount((int) count);
                    serviceListingRepository.save(listing);
                }
            }
        } else {
            Product product = productRepository.findById(targetId).orElse(null);
            if (product != null) {
                product.setRatingAverage(rounded);
                product.setRatingCount((int) count);
                productRepository.save(product);
            }
        }
    }

    private ReviewResponse toResponse(Review r) {
        return new ReviewResponse(r.getId(), r.getAuthor().getFullName(), r.getTargetType(), r.getTargetId(),
                r.getRating(), r.getText(), r.getCreatedAt(), r.getTaste(), r.getPortionSize(), r.getValueForMoney(),
                r.getFoodQuality(), r.getPackaging(), r.getDeliveryExperience(), r.isVerifiedOrder(), r.getSource());
    }
}
