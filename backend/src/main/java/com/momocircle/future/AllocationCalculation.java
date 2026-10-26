package com.momocircle.future;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.*;
import java.time.LocalDate;

/** Immutable simulation result. No reference to payments, wallets, ledger or contributions by design. */
@Entity
@Table(name = "allocation_calculations")
public class AllocationCalculation extends BaseEntity {
    @Column(name = "plan_id", nullable = false, length = 36) private String planId;
    @Column(name = "idempotency_key", nullable = false, length = 80) private String idempotencyKey;
    @Column(name = "income_basis_minor", nullable = false) private long incomeBasisMinor;
    @Enumerated(EnumType.STRING) @Column(name = "income_source", nullable = false, length = 30) private IncomeSource incomeSource;
    @Column(name = "income_as_of", nullable = false) private LocalDate incomeAsOf;
    @Column(name = "reserves_minor", nullable = false) private long reservesMinor;
    @Column(name = "commitments_minor", nullable = false) private long commitmentsMinor;
    @Column(name = "allocatable_minor", nullable = false) private long allocatableMinor;
    @Column(name = "total_allocated_minor", nullable = false) private long totalAllocatedMinor;
    @Column(name = "remaining_minor", nullable = false) private long remainingMinor;
    @Column(nullable = false) private boolean valid;
    @Column(length = 2000) private String warnings;
    @Column(name = "income_notes", length = 2000) private String incomeNotes;

    public String getPlanId() { return planId; } public void setPlanId(String v) { planId = v; }
    public String getIdempotencyKey() { return idempotencyKey; } public void setIdempotencyKey(String v) { idempotencyKey = v; }
    public long getIncomeBasisMinor() { return incomeBasisMinor; } public void setIncomeBasisMinor(long v) { incomeBasisMinor = v; }
    public IncomeSource getIncomeSource() { return incomeSource; } public void setIncomeSource(IncomeSource v) { incomeSource = v; }
    public LocalDate getIncomeAsOf() { return incomeAsOf; } public void setIncomeAsOf(LocalDate v) { incomeAsOf = v; }
    public long getReservesMinor() { return reservesMinor; } public void setReservesMinor(long v) { reservesMinor = v; }
    public long getCommitmentsMinor() { return commitmentsMinor; } public void setCommitmentsMinor(long v) { commitmentsMinor = v; }
    public long getAllocatableMinor() { return allocatableMinor; } public void setAllocatableMinor(long v) { allocatableMinor = v; }
    public long getTotalAllocatedMinor() { return totalAllocatedMinor; } public void setTotalAllocatedMinor(long v) { totalAllocatedMinor = v; }
    public long getRemainingMinor() { return remainingMinor; } public void setRemainingMinor(long v) { remainingMinor = v; }
    public boolean isValid() { return valid; } public void setValid(boolean v) { valid = v; }
    public String getIncomeNotes() { return incomeNotes; } public void setIncomeNotes(String v) { incomeNotes = v; }
    public String getWarnings() { return warnings; } public void setWarnings(String v) { warnings = v; }
}
