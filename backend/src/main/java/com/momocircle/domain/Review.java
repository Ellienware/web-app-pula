package com.momocircle.domain;

import com.momocircle.domain.enums.ReviewTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * A 1-5 star review left by a buyer against a Business or a Product. Eligibility
 * (must have a COMPLETED order) is enforced in ReviewService, not here — the
 * entity itself only guarantees one review per (author, targetType, targetId).
 */
@Entity
@Table(name = "reviews", uniqueConstraints = @UniqueConstraint(columnNames = {"dedupe_key"}))
public class Review extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false)
    private ReviewTargetType targetType;

    /** Business id or Product id, depending on targetType. */
    @Column(name = "target_id", nullable = false)
    private String targetId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    /** Set (and order is null) when the review comes from a completed service booking. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "service_booking_id")
    private ServiceBooking serviceBooking;

    public ServiceBooking getServiceBooking() { return serviceBooking; }
    public void setServiceBooking(ServiceBooking serviceBooking) { this.serviceBooking = serviceBooking; }

    @Column(nullable = false)
    private int rating;

    @Column(length = 1000)
    private String text;

    /** Simple moderation flag — hidden reviews are excluded from public listings and averages. */
    @Column(nullable = false)
    private boolean hidden = false;

    // ---- PULA Food: optional 1-5 sub-ratings (null = not given) ----
    private Integer taste;
    @Column(name = "portion_size") private Integer portionSize;
    @Column(name = "value_for_money") private Integer valueForMoney;
    @Column(name = "food_quality") private Integer foodQuality;
    private Integer packaging;
    /** About the delivery, not the food. Reported separately and never part of the food rating. */
    @Column(name = "delivery_experience") private Integer deliveryExperience;

    /** Always true: every review here is tied to a completed PULA order. Set by the server only. */
    @Column(name = "verified_order", nullable = false)
    private boolean verifiedOrder = true;

    /** Always PULA. External reviews are never stored here or counted in PULA scores. */
    @Column(nullable = false, length = 16)
    private String source = "PULA";

    /** Portable uniqueness: retail = author|type|target, food = author|order|type|target. */
    @Column(name = "dedupe_key", nullable = false, length = 160)
    private String dedupeKey;

    public Integer getTaste() { return taste; }
    public void setTaste(Integer v) { this.taste = v; }
    public Integer getPortionSize() { return portionSize; }
    public void setPortionSize(Integer v) { this.portionSize = v; }
    public Integer getValueForMoney() { return valueForMoney; }
    public void setValueForMoney(Integer v) { this.valueForMoney = v; }
    public Integer getFoodQuality() { return foodQuality; }
    public void setFoodQuality(Integer v) { this.foodQuality = v; }
    public Integer getPackaging() { return packaging; }
    public void setPackaging(Integer v) { this.packaging = v; }
    public Integer getDeliveryExperience() { return deliveryExperience; }
    public void setDeliveryExperience(Integer v) { this.deliveryExperience = v; }
    public boolean isVerifiedOrder() { return verifiedOrder; }
    public void setVerifiedOrder(boolean v) { this.verifiedOrder = v; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getDedupeKey() { return dedupeKey; }
    public void setDedupeKey(String dedupeKey) { this.dedupeKey = dedupeKey; }

    public User getAuthor() { return author; }
    public void setAuthor(User author) { this.author = author; }
    public ReviewTargetType getTargetType() { return targetType; }
    public void setTargetType(ReviewTargetType targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
    public int getRating() { return rating; }
    public void setRating(int rating) { this.rating = rating; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public boolean isHidden() { return hidden; }
    public void setHidden(boolean hidden) { this.hidden = hidden; }
}
