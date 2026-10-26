package com.momocircle.future;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.*;

/** A declared reserve or commitment. A planning input only: it does not reserve, hold or move any money. */
@Entity
@Table(name = "business_plan_items")
public class BusinessPlanItem extends BaseEntity {
    @Column(name = "plan_id", nullable = false, length = 36) private String planId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30) private PlanItemKind kind;
    @Column(nullable = false, length = 120) private String label;
    @Column(name = "amount_minor", nullable = false) private long amountMinor;

    public String getPlanId() { return planId; } public void setPlanId(String v) { planId = v; }
    public PlanItemKind getKind() { return kind; } public void setKind(PlanItemKind v) { kind = v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public long getAmountMinor() { return amountMinor; } public void setAmountMinor(long v) { amountMinor = v; }
}
