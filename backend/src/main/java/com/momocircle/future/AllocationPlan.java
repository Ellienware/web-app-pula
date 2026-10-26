package com.momocircle.future;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.*;

/** Planning rule only. Not a contribution, wallet transfer or payment. */
@Entity
@Table(name = "allocation_plans")
public class AllocationPlan extends BaseEntity {
    @Column(name = "owner_user_id", nullable = false, length = 36) private String ownerUserId;
    @Column(name = "business_id", length = 36) private String businessId;
    @Column(nullable = false, length = 120) private String name;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private PlanStatus status = PlanStatus.DRAFT;
    @Column(name = "reserves_minor", nullable = false) private long reservesMinor;
    @Column(name = "commitments_minor", nullable = false) private long commitmentsMinor;
    @Column(name = "min_buffer_minor", nullable = false) private long minBufferMinor;

    public String getOwnerUserId() { return ownerUserId; } public void setOwnerUserId(String v) { ownerUserId = v; }
    public String getBusinessId() { return businessId; } public void setBusinessId(String v) { businessId = v; }
    public String getName() { return name; } public void setName(String v) { name = v; }
    public PlanStatus getStatus() { return status; } public void setStatus(PlanStatus v) { status = v; }
    public long getReservesMinor() { return reservesMinor; } public void setReservesMinor(long v) { reservesMinor = v; }
    public long getCommitmentsMinor() { return commitmentsMinor; } public void setCommitmentsMinor(long v) { commitmentsMinor = v; }
    public long getMinBufferMinor() { return minBufferMinor; } public void setMinBufferMinor(long v) { minBufferMinor = v; }
}
