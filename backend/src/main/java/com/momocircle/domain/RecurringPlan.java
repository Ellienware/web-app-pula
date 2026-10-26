package com.momocircle.domain;

import com.momocircle.domain.enums.RecurringFrequency;
import com.momocircle.domain.enums.RecurringStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * A schedule under which every member of a circle is expected to contribute a
 * fixed amount each period (e.g. a monthly stokvel of R500). Generating a
 * period materialises a {@link RecurringDue} for each current member.
 *
 * <p>The platform never auto-charges a wallet: dues are collected only when a
 * member explicitly authorises the payment.
 */
@Entity
@Table(name = "recurring_plans")
public class RecurringPlan extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "circle_id", nullable = false)
    private Circle circle;

    /** Optional goal that collected dues are earmarked toward. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goal_id")
    private CircleGoal goal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amountPerMember;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurringFrequency frequency;

    /** First scheduled due date. */
    @Column(nullable = false)
    private LocalDate anchorDate;

    /** Next date a period should be generated for. */
    @Column(nullable = false)
    private LocalDate nextDueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecurringStatus status = RecurringStatus.ACTIVE;

    @Column(length = 500)
    private String note;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("dueDate asc")
    private List<RecurringPeriod> periods = new ArrayList<>();

    public Circle getCircle() {
        return circle;
    }

    public void setCircle(Circle circle) {
        this.circle = circle;
    }

    public CircleGoal getGoal() {
        return goal;
    }

    public void setGoal(CircleGoal goal) {
        this.goal = goal;
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

    public BigDecimal getAmountPerMember() {
        return amountPerMember;
    }

    public void setAmountPerMember(BigDecimal amountPerMember) {
        this.amountPerMember = amountPerMember;
    }

    public RecurringFrequency getFrequency() {
        return frequency;
    }

    public void setFrequency(RecurringFrequency frequency) {
        this.frequency = frequency;
    }

    public LocalDate getAnchorDate() {
        return anchorDate;
    }

    public void setAnchorDate(LocalDate anchorDate) {
        this.anchorDate = anchorDate;
    }

    public LocalDate getNextDueDate() {
        return nextDueDate;
    }

    public void setNextDueDate(LocalDate nextDueDate) {
        this.nextDueDate = nextDueDate;
    }

    public RecurringStatus getStatus() {
        return status;
    }

    public void setStatus(RecurringStatus status) {
        this.status = status;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public List<RecurringPeriod> getPeriods() {
        return periods;
    }

    public void setPeriods(List<RecurringPeriod> periods) {
        this.periods = periods;
    }
}
