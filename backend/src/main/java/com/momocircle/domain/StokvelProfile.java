package com.momocircle.domain;

import jakarta.persistence.*;
import com.momocircle.domain.enums.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "stokvel_profiles")
public class StokvelProfile extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "circle_id", nullable = false, unique = true)
    private Circle circle;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32)
    private StokvelType stokvelType;
    @Column(precision = 19, scale = 2)
    private BigDecimal contributionAmount;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private RecurringFrequency contributionFrequency = RecurringFrequency.MONTHLY;
    @Column(nullable = false)
    private LocalDate startDate;
    private LocalDate targetDate;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goal_id")
    private CircleGoal goal;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16)
    private ApprovalRuleType approvalRule = ApprovalRuleType.SINGLE;
    private Integer approvalThreshold;
    @Column(nullable = false)
    private boolean membersCanVote = false;
    @Column(nullable = false)
    private int requestValidityDays = 14;
    @Column(nullable = false)
    private boolean rotationLocked = false;
    private Instant rotationLockedAt;
    /** Opt-in. A stokvel is private unless an owner/admin sets this; only discoverable stokvels appear in Discover. */
    @Column(nullable = false)
    private boolean discoverable = false;
    @Column(length = 80)
    private String area;
    @Column(length = 300)
    private String publicSummary;
    @Column(length = 500)
    private String membershipRequirements;
    private Integer maxMembers;
    /** Whether redeeming an invite link only creates a request for an admin to approve (default: the invite itself is the approval). */
    @Column(nullable = false)
    private boolean inviteRequiresApproval = false;

    public Circle getCircle() { return circle; }
    public void setCircle(Circle circle) { this.circle = circle; }
    public StokvelType getStokvelType() { return stokvelType; }
    public void setStokvelType(StokvelType stokvelType) { this.stokvelType = stokvelType; }
    public BigDecimal getContributionAmount() { return contributionAmount; }
    public void setContributionAmount(BigDecimal contributionAmount) { this.contributionAmount = contributionAmount; }
    public RecurringFrequency getContributionFrequency() { return contributionFrequency; }
    public void setContributionFrequency(RecurringFrequency contributionFrequency) { this.contributionFrequency = contributionFrequency; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getTargetDate() { return targetDate; }
    public void setTargetDate(LocalDate targetDate) { this.targetDate = targetDate; }
    public CircleGoal getGoal() { return goal; }
    public void setGoal(CircleGoal goal) { this.goal = goal; }
    public ApprovalRuleType getApprovalRule() { return approvalRule; }
    public void setApprovalRule(ApprovalRuleType approvalRule) { this.approvalRule = approvalRule; }
    public Integer getApprovalThreshold() { return approvalThreshold; }
    public void setApprovalThreshold(Integer approvalThreshold) { this.approvalThreshold = approvalThreshold; }
    public boolean isMembersCanVote() { return membersCanVote; }
    public void setMembersCanVote(boolean membersCanVote) { this.membersCanVote = membersCanVote; }
    public int getRequestValidityDays() { return requestValidityDays; }
    public void setRequestValidityDays(int requestValidityDays) { this.requestValidityDays = requestValidityDays; }
    public boolean isRotationLocked() { return rotationLocked; }
    public void setRotationLocked(boolean rotationLocked) { this.rotationLocked = rotationLocked; }
    public Instant getRotationLockedAt() { return rotationLockedAt; }
    public void setRotationLockedAt(Instant rotationLockedAt) { this.rotationLockedAt = rotationLockedAt; }
    public boolean isDiscoverable() { return discoverable; }
    public void setDiscoverable(boolean discoverable) { this.discoverable = discoverable; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public String getPublicSummary() { return publicSummary; }
    public void setPublicSummary(String publicSummary) { this.publicSummary = publicSummary; }
    public String getMembershipRequirements() { return membershipRequirements; }
    public void setMembershipRequirements(String membershipRequirements) { this.membershipRequirements = membershipRequirements; }
    public Integer getMaxMembers() { return maxMembers; }
    public void setMaxMembers(Integer maxMembers) { this.maxMembers = maxMembers; }
    public boolean isInviteRequiresApproval() { return inviteRequiresApproval; }
    public void setInviteRequiresApproval(boolean inviteRequiresApproval) { this.inviteRequiresApproval = inviteRequiresApproval; }
}
