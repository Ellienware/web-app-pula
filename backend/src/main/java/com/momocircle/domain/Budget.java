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
import jakarta.persistence.UniqueConstraint;

import java.util.ArrayList;
import java.util.List;

/**
 * A user's spending plan for a period, made up of {@link BudgetCategory}
 * allocations. One active budget per user in this slice (see the unique
 * constraint on user_id) — a history-of-budgets model is a reasonable
 * future extension once there's a real need to compare past periods, not
 * something to build ahead of that need.
 */
@Entity
@Table(name = "budgets", uniqueConstraints = @UniqueConstraint(columnNames = "user_id"))
public class Budget extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, length = 3)
    private String currency;

    /** e.g. "Monthly budget", "March budget". Free text so it doesn't need its own enum. */
    @Column(nullable = false)
    private String label;

    @OneToMany(mappedBy = "budget", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("createdAt asc")
    private List<BudgetCategory> categories = new ArrayList<>();

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public List<BudgetCategory> getCategories() { return categories; }
    public void setCategories(List<BudgetCategory> categories) { this.categories = categories; }
}
