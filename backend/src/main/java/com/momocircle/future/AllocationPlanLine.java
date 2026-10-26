package com.momocircle.future;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "allocation_plan_lines")
public class AllocationPlanLine extends BaseEntity {
    @Column(name = "plan_id", nullable = false, length = 36) private String planId;
    @Column(name = "goal_id", length = 36) private String goalId;
    @Column(nullable = false, length = 120) private String label;
    @Enumerated(EnumType.STRING) @Column(name = "line_type", nullable = false, length = 10) private AllocationCalculator.LineType lineType;
    @Column(name = "fixed_minor", nullable = false) private long fixedMinor;
    @Column(name = "percent_bp", nullable = false) private int percentBp;
    @Column(nullable = false) private int position;

    public String getPlanId() { return planId; } public void setPlanId(String v) { planId = v; }
    public String getGoalId() { return goalId; } public void setGoalId(String v) { goalId = v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public AllocationCalculator.LineType getLineType() { return lineType; } public void setLineType(AllocationCalculator.LineType v) { lineType = v; }
    public long getFixedMinor() { return fixedMinor; } public void setFixedMinor(long v) { fixedMinor = v; }
    public int getPercentBp() { return percentBp; } public void setPercentBp(int v) { percentBp = v; }
    public int getPosition() { return position; } public void setPosition(int v) { position = v; }
}
