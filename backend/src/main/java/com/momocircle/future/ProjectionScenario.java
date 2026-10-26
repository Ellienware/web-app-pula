package com.momocircle.future;

import com.momocircle.domain.BaseEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

/** Saved hypothetical projection. Not an account, not a transaction. */
@Entity
@Table(name = "projection_scenarios")
public class ProjectionScenario extends BaseEntity {
    @Column(name = "owner_user_id", nullable = false, length = 36) private String ownerUserId;
    @Column(nullable = false, length = 120) private String label;
    @Column(name = "starting_minor", nullable = false) private long startingMinor;
    @Column(name = "monthly_minor", nullable = false) private long monthlyMinor;
    @Column(nullable = false) private int years;
    @Column(name = "annual_return_pct", nullable = false, precision = 7, scale = 2) private BigDecimal annualReturnPct;
    @Column(name = "annual_inflation_pct", nullable = false, precision = 7, scale = 2) private BigDecimal annualInflationPct;
    @Column(name = "annual_fee_pct", nullable = false, precision = 7, scale = 2) private BigDecimal annualFeePct;
    @Column(name = "contribution_growth_pct", nullable = false, precision = 7, scale = 2) private BigDecimal contributionGrowthPct;
    @Column(name = "total_contributions_minor", nullable = false) private long totalContributionsMinor;
    @Column(name = "future_value_minor", nullable = false) private long futureValueMinor;
    @Column(name = "future_value_real_minor", nullable = false) private long futureValueRealMinor;

    public String getOwnerUserId() { return ownerUserId; } public void setOwnerUserId(String v) { ownerUserId = v; }
    public String getLabel() { return label; } public void setLabel(String v) { label = v; }
    public long getStartingMinor() { return startingMinor; } public void setStartingMinor(long v) { startingMinor = v; }
    public long getMonthlyMinor() { return monthlyMinor; } public void setMonthlyMinor(long v) { monthlyMinor = v; }
    public int getYears() { return years; } public void setYears(int v) { years = v; }
    public BigDecimal getAnnualReturnPct() { return annualReturnPct; } public void setAnnualReturnPct(BigDecimal v) { annualReturnPct = v; }
    public BigDecimal getAnnualInflationPct() { return annualInflationPct; } public void setAnnualInflationPct(BigDecimal v) { annualInflationPct = v; }
    public BigDecimal getAnnualFeePct() { return annualFeePct; } public void setAnnualFeePct(BigDecimal v) { annualFeePct = v; }
    public BigDecimal getContributionGrowthPct() { return contributionGrowthPct; } public void setContributionGrowthPct(BigDecimal v) { contributionGrowthPct = v; }
    public long getTotalContributionsMinor() { return totalContributionsMinor; } public void setTotalContributionsMinor(long v) { totalContributionsMinor = v; }
    public long getFutureValueMinor() { return futureValueMinor; } public void setFutureValueMinor(long v) { futureValueMinor = v; }
    public long getFutureValueRealMinor() { return futureValueRealMinor; } public void setFutureValueRealMinor(long v) { futureValueRealMinor = v; }
}
