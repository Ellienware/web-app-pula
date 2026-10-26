package com.momocircle.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** One cycle of a {@link RecurringPlan} (e.g. "August 2026"). */
@Entity
@Table(name = "recurring_periods")
public class RecurringPeriod extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private RecurringPlan plan;

    @Column(nullable = false)
    private String label;

    @Column(nullable = false)
    private LocalDate dueDate;

    @OneToMany(mappedBy = "period", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt asc")
    private List<RecurringDue> dues = new ArrayList<>();

    public RecurringPlan getPlan() {
        return plan;
    }

    public void setPlan(RecurringPlan plan) {
        this.plan = plan;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public List<RecurringDue> getDues() {
        return dues;
    }

    public void setDues(List<RecurringDue> dues) {
        this.dues = dues;
    }
}
