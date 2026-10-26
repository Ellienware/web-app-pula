package com.momocircle.future;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.*;

@Entity
@Table(name = "allocation_calculation_lines")
public class AllocationCalculationLine extends BaseEntity {
    @Column(name = "calculation_id", nullable = false, length = 36) private String calculationId;
    @Column(name = "goal_id", length = 36) private String goalId;
    @Column(nullable = false, length = 120) private String label;
    @Column(name = "allocated_minor", nullable = false) private long allocatedMinor;
    @Column(nullable = false) private int position;

    public String getCalculationId() { return calculationId; } public void setCalculationId(String v) { calculationId = v; }
    public String getGoalId() { return goalId; } public void setGoalId(String v) { goalId = v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public long getAllocatedMinor() { return allocatedMinor; } public void setAllocatedMinor(long v) { allocatedMinor = v; }
    public int getPosition() { return position; } public void setPosition(int v) { position = v; }
}
