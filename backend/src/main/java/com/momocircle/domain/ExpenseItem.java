package com.momocircle.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.LinkedHashSet;
import java.util.Set;

/** A line item of an item-based split: its amount in minor units and the participants who share it. */
@Entity
@Table(name = "expense_items")
public class ExpenseItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "expense_id", nullable = false)
    private Expense expense;

    @Column(name = "sort_order", nullable = false)
    private int position;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "expense_item_assignees", joinColumns = @JoinColumn(name = "item_id"))
    @Column(name = "user_id", length = 36, nullable = false)
    private Set<String> assigneeIds = new LinkedHashSet<>();

    public Expense getExpense() { return expense; }
    public void setExpense(Expense expense) { this.expense = expense; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public long getAmountMinor() { return amountMinor; }
    public void setAmountMinor(long amountMinor) { this.amountMinor = amountMinor; }

    public Set<String> getAssigneeIds() { return assigneeIds; }
    public void setAssigneeIds(Set<String> assigneeIds) { this.assigneeIds = assigneeIds; }
}
