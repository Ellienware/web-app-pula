package com.momocircle.domain;

import com.momocircle.branch.BranchRole;
import com.momocircle.branch.BranchPermission;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.EnumSet;
import java.util.Set;

/** One user's assignment (MANAGER or STAFF) to one branch. The owner of the business is never stored here. */
@Entity
@Table(name = "branch_staff")
public class BranchStaff extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "branch_id", nullable = false)
    private BusinessBranch branch;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BranchRole role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assigned_by", nullable = false)
    private User assignedBy;

    /** The permissions this assignment holds (the owner is never stored here and always has all of them). */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "branch_staff_permissions", joinColumns = @JoinColumn(name = "branch_staff_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", length = 24)
    private Set<BranchPermission> permissions = EnumSet.noneOf(BranchPermission.class);

    public BusinessBranch getBranch() { return branch; }
    public void setBranch(BusinessBranch branch) { this.branch = branch; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public BranchRole getRole() { return role; }
    public void setRole(BranchRole role) { this.role = role; }
    public Set<BranchPermission> getPermissions() { return permissions; }
    public void setPermissions(Set<BranchPermission> permissions) {
        this.permissions = permissions == null || permissions.isEmpty() ? EnumSet.noneOf(BranchPermission.class) : EnumSet.copyOf(permissions);
    }
    public User getAssignedBy() { return assignedBy; }
    public void setAssignedBy(User assignedBy) { this.assignedBy = assignedBy; }
}
