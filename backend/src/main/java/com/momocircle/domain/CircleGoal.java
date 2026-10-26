package com.momocircle.domain;

import com.momocircle.domain.enums.GoalStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A named savings target within a circle. A circle can hold several goals over
 * its lifetime (e.g. "December gift", "Emergency fund"). Contributions may be
 * earmarked to a specific goal to drive its progress.
 */
@Entity
@Table(name = "circle_goals")
public class CircleGoal extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "circle_id", nullable = false)
    private Circle circle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(nullable = false)
    private String title;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal targetAmount;

    /** Optional target date for reaching the goal. */
    @Column
    private LocalDate deadline;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private GoalStatus status = GoalStatus.ACTIVE;

    public Circle getCircle() {
        return circle;
    }

    public void setCircle(Circle circle) {
        this.circle = circle;
    }

    public User getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(User createdBy) {
        this.createdBy = createdBy;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getTargetAmount() {
        return targetAmount;
    }

    public void setTargetAmount(BigDecimal targetAmount) {
        this.targetAmount = targetAmount;
    }

    public LocalDate getDeadline() {
        return deadline;
    }

    public void setDeadline(LocalDate deadline) {
        this.deadline = deadline;
    }

    public GoalStatus getStatus() {
        return status;
    }

    public void setStatus(GoalStatus status) {
        this.status = status;
    }

    /** Save-to-Buy: set when the goal is designated as saving toward a purchase in this marketplace vertical. */
    @Enumerated(EnumType.STRING)
    @Column(name = "purchase_vertical", length = 16)
    private com.momocircle.domain.enums.SaveToBuyVertical purchaseVertical;

    @Column(name = "purchase_purpose", length = 200)
    private String purchasePurpose;

    public com.momocircle.domain.enums.SaveToBuyVertical getPurchaseVertical() { return purchaseVertical; }
    public void setPurchaseVertical(com.momocircle.domain.enums.SaveToBuyVertical v) { this.purchaseVertical = v; }
    public String getPurchasePurpose() { return purchasePurpose; }
    public void setPurchasePurpose(String p) { this.purchasePurpose = p; }

    /** PULA Future descriptive metadata (nullable; does not affect contributions or progress). */
    @Column(length = 40)
    private String category;
    @Column(length = 10)
    private String priority;
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
}
