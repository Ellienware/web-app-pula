package com.momocircle.service;

import com.momocircle.branch.BranchAccessPolicy;
import com.momocircle.branch.BranchAccessPolicy.Action;
import com.momocircle.branch.BranchAccessPolicy.Relation;
import com.momocircle.branch.BranchPermission;
import com.momocircle.branch.BranchRole;
import com.momocircle.branch.BranchRules;
import com.momocircle.branch.BranchStatus;
import com.momocircle.domain.BranchStaff;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.User;
import com.momocircle.dto.BranchDtos.AssignStaffRequest;
import com.momocircle.dto.BranchDtos.BranchView;
import com.momocircle.dto.BranchDtos.CreateBranchRequest;
import com.momocircle.dto.BranchDtos.MyAssignmentView;
import com.momocircle.dto.BranchDtos.SetPermissionsRequest;
import com.momocircle.dto.BranchDtos.StaffView;
import com.momocircle.dto.BranchDtos.UpdateBranchRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BranchStaffRepository;
import com.momocircle.repository.BusinessBranchRepository;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * General branch management for ANY business type, on top of the existing {@code business_branches} table (the same rows the
 * PULA Food endpoints use). Ownership comes from {@code businesses.owner_id}; manager/staff assignments from {@code branch_staff}.
 * Every request recomputes the caller's relation from the database, so removing an assignment takes effect immediately, and every
 * branch is always loaded THROUGH its parent business so a branch id from another business is simply "not found".
 *
 * <p>Not supported here (documented): branch-specific stock/prices for non-food businesses (products and inventory are still
 * business-level), inter-branch transfers, consolidated reporting, food-only settings (use /api/market/food/branches).
 * Nothing here verifies a business or an address: submitted information is stored as submitted (verification_status UNVERIFIED).
 */
@Service
public class BranchManagementService {

    private final BusinessRepository businesses;
    private final BusinessBranchRepository branches;
    private final BranchStaffRepository staff;
    private final UserRepository users;
    private final AuditLogService audit;

    public BranchManagementService(BusinessRepository businesses, BusinessBranchRepository branches, BranchStaffRepository staff,
                                   UserRepository users, AuditLogService audit) {
        this.businesses = businesses;
        this.branches = branches;
        this.staff = staff;
        this.users = users;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<BranchView> list(User caller, String businessId) {
        Business b = requireBusiness(businessId);
        List<BusinessBranch> all = branches.findByBusinessIdOrderByCreatedAtAsc(b.getId());
        List<BranchView> out = new ArrayList<>();
        boolean owner = isOwner(caller, b);
        for (BusinessBranch br : all) {
            if (owner) { out.add(view(br, Relation.OWNER)); continue; }
            BranchStaff mine = staff.findByBranchIdAndUserId(br.getId(), caller.getId()).orElse(null);
            if (mine != null) {
                out.add(view(br, mine.getRole() == BranchRole.MANAGER ? Relation.MANAGER : Relation.STAFF, mine.getPermissions()));
            }
        }
        if (!owner && out.isEmpty()) throw ApiException.notFound("Business not found"); // reveal nothing to outsiders
        return out;
    }

    @Transactional(readOnly = true)
    public BranchView get(User caller, String businessId, String branchId) {
        Context c = context(caller, businessId, branchId, Action.VIEW);
        return view(c.branch, c.relation, c.stored());
    }

    @Transactional(readOnly = true)
    public List<MyAssignmentView> myAssignments(User caller) {
        List<MyAssignmentView> out = new ArrayList<>();
        for (BranchStaff s : staff.findByUserId(caller.getId())) {
            BusinessBranch br = s.getBranch();
            out.add(new MyAssignmentView(br.getId(), br.getName(), br.getBusiness().getId(), br.getBusiness().getName(),
                    s.getRole(), br.getStatus()));
        }
        return out;
    }

    // ------------------------------------------------------------------ writes

    @Transactional
    public BranchView create(User caller, String businessId, CreateBranchRequest req) {
        Business b = requireBusiness(businessId);
        if (!isOwner(caller, b)) throw notFoundOrForbidden(caller, b);
        BusinessBranch br = new BusinessBranch();
        br.setBusiness(b);
        try {
            br.setName(BranchRules.requireValidName(req.name()));
            String code = BranchRules.requireValidCode(req.branchCode());
            if (code != null && branches.existsByBusinessIdAndBranchCode(b.getId(), code)) {
                throw ApiException.conflict("This business already has a branch with code " + code);
            }
            br.setBranchCode(code);
            applyDetails(br, req.address(), req.suburb(), req.city(), req.province(), req.postalCode(), req.latitude(), req.longitude(),
                    req.phone(), req.email(), req.operatingHours());
            applyFranchise(br, req.brandName(), req.operatingModel(), req.operatorName());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        BusinessBranch saved = saveUnique(br, b);
        audit.record(caller, "BRANCH_CREATED", "BusinessBranch", saved.getId(), "business=" + b.getId() + " code=" + saved.getBranchCode());
        return view(saved, Relation.OWNER);
    }

    @Transactional
    public BranchView update(User caller, String businessId, String branchId, UpdateBranchRequest req) {
        Context c = context(caller, businessId, branchId, Action.UPDATE_DETAILS);
        BusinessBranch br = c.branch;
        if (br.getStatus() == BranchStatus.CLOSED) throw ApiException.conflict("A closed branch cannot be edited");
        try {
            if (req.name() != null) br.setName(BranchRules.requireValidName(req.name()));
            if (req.branchCode() != null) {
                if (c.relation != Relation.OWNER) throw ApiException.forbidden("Only the business owner can change a branch code");
                String code = BranchRules.requireValidCode(req.branchCode());
                if (code != null && !code.equals(br.getBranchCode())
                        && branches.existsByBusinessIdAndBranchCodeAndIdNot(br.getBusiness().getId(), code, br.getId())) {
                    throw ApiException.conflict("This business already has a branch with code " + code);
                }
                br.setBranchCode(code);
            }
            applyDetailsPartial(br, req);
            if (req.brandName() != null || req.operatingModel() != null || req.operatorName() != null) {
                if (c.relation != Relation.OWNER) throw ApiException.forbidden("Only the business owner can change franchise details");
                applyFranchise(br, req.brandName() != null ? req.brandName() : br.getBrandName(),
                        req.operatingModel() != null ? req.operatingModel() : br.getOperatingModel(),
                        req.operatorName() != null ? req.operatorName() : br.getOperatorName());
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
        BusinessBranch saved = saveUnique(br, br.getBusiness());
        audit.record(caller, "BRANCH_UPDATED", "BusinessBranch", saved.getId(), "by=" + c.relation);
        return view(saved, c.relation, c.stored());
    }

    @Transactional
    public BranchView changeStatus(User caller, String businessId, String branchId, BranchStatus to) {
        Context c = context(caller, businessId, branchId, Action.CHANGE_STATUS);
        BranchStatus from = c.branch.getStatus();
        if (!BranchStatus.canTransition(from, to)) {
            throw ApiException.conflict("A branch cannot move from " + from + " to " + to);
        }
        c.branch.applyStatus(to);
        BusinessBranch saved = branches.save(c.branch);
        audit.record(caller, "BRANCH_STATUS_CHANGED", "BusinessBranch", saved.getId(), from + "->" + to);
        return view(saved, c.relation, c.stored());
    }

    // ------------------------------------------------------------------ staff / managers

    @Transactional(readOnly = true)
    public List<StaffView> listStaff(User caller, String businessId, String branchId) {
        Context c = context(caller, businessId, branchId, Action.VIEW_ASSIGNMENTS);
        return staff.findByBranchIdOrderByCreatedAtAsc(c.branch.getId()).stream().map(this::staffView).toList();
    }

    @Transactional
    public StaffView assign(User caller, String businessId, String branchId, AssignStaffRequest req) {
        Context c = context(caller, businessId, branchId, Action.VIEW_ASSIGNMENTS);
        if (!BranchAccessPolicy.mayAssign(c.relation, req.role())) {
            throw ApiException.forbidden("You are not allowed to assign that role");
        }
        if (c.branch.getStatus() == BranchStatus.CLOSED) throw ApiException.conflict("A closed branch cannot take new assignments");
        java.util.Set<BranchPermission> requested = req.permissions() != null ? java.util.EnumSet.copyOf(req.permissions().isEmpty()
                ? java.util.EnumSet.noneOf(BranchPermission.class) : req.permissions())
                : req.role() == BranchRole.MANAGER ? java.util.EnumSet.copyOf(BranchPermission.MANAGER_DEFAULTS)
                : java.util.EnumSet.noneOf(BranchPermission.class);
        if (!BranchAccessPolicy.mayGrant(c.relation, c.stored(), req.role(), requested)) {
            throw ApiException.forbidden("You cannot grant permissions you do not hold yourself");
        }
        User target = findUser(req.identifier());
        if (target.getId().equals(caller.getId())) throw ApiException.forbidden("You cannot change your own assignment");
        if (target.getId().equals(c.branch.getBusiness().getOwner().getId())) {
            throw ApiException.badRequest("The business owner already has full access and does not need an assignment");
        }
        BranchStaff existing = staff.findByBranchIdAndUserId(c.branch.getId(), target.getId()).orElse(null);
        if (existing != null) {
            if (existing.getRole() == req.role() && (req.permissions() == null || existing.getPermissions().equals(requested))) {
                return staffView(existing); // idempotent
            }
            // Any change that touches a MANAGER (promote or demote) is owner-only, so a manager can never escalate anyone.
            if (existing.getRole() != req.role() && !BranchAccessPolicy.allows(c.relation, Action.MANAGE_MANAGERS)) {
                throw ApiException.forbidden("Only the business owner can change a manager's role");
            }
            requireMayEditExisting(c, existing);
            existing.setRole(req.role());
            existing.setPermissions(requested);
            BranchStaff saved = staff.save(existing);
            audit.record(caller, "BRANCH_STAFF_ROLE_CHANGED", "BusinessBranch", c.branch.getId(),
                    "user=" + target.getId() + " role=" + req.role() + " perms=" + requested);
            return staffView(saved);
        }
        BranchStaff s = new BranchStaff();
        s.setBranch(c.branch);
        s.setUser(target);
        s.setRole(req.role());
        s.setPermissions(requested);
        s.setAssignedBy(caller);
        BranchStaff saved;
        try {
            saved = staff.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) { // concurrent duplicate assignment
            throw ApiException.conflict("This person is already assigned to this branch");
        }
        audit.record(caller, "BRANCH_STAFF_ASSIGNED", "BusinessBranch", c.branch.getId(),
                "user=" + target.getId() + " role=" + req.role() + " perms=" + requested);
        return staffView(saved);
    }

    /** Replaces the permission set of an existing assignment. Nobody can change their own; a manager can only touch STAFF and only within their own authority. */
    @Transactional
    public StaffView setPermissions(User caller, String businessId, String branchId, String userId, SetPermissionsRequest req) {
        Context c = context(caller, businessId, branchId, Action.VIEW_ASSIGNMENTS);
        BranchStaff target = staff.findByBranchIdAndUserId(c.branch.getId(), userId == null ? "" : userId)
                .orElseThrow(() -> ApiException.notFound("Assignment not found"));
        if (userId.equals(caller.getId())) throw ApiException.forbidden("You cannot change your own permissions");
        if (c.branch.getStatus() == BranchStatus.CLOSED) throw ApiException.conflict("A closed branch cannot be changed");
        java.util.Set<BranchPermission> requested = req.permissions().isEmpty()
                ? java.util.EnumSet.noneOf(BranchPermission.class) : java.util.EnumSet.copyOf(req.permissions());
        if (!BranchAccessPolicy.mayGrant(c.relation, c.stored(), target.getRole(), requested)) {
            throw ApiException.forbidden("You cannot grant permissions you do not hold yourself");
        }
        requireMayEditExisting(c, target);
        target.setPermissions(requested);
        BranchStaff saved = staff.save(target);
        audit.record(caller, "BRANCH_STAFF_PERMISSIONS_CHANGED", "BusinessBranch", c.branch.getId(), "user=" + userId + " perms=" + requested);
        return staffView(saved);
    }

    /** A non-owner may not edit an assignment that currently holds permissions they do not hold (it would silently strip owner-granted access). */
    private void requireMayEditExisting(Context c, BranchStaff existing) {
        if (c.relation != Relation.OWNER && !c.effective().containsAll(existing.getPermissions())) {
            throw ApiException.forbidden("That assignment holds permissions beyond your own, so only the owner can change it");
        }
    }

    @Transactional
    public void removeStaff(User caller, String businessId, String branchId, String userId) {
        Context c = context(caller, businessId, branchId, Action.VIEW_ASSIGNMENTS);
        BranchStaff s = staff.findByBranchIdAndUserId(c.branch.getId(), userId)
                .orElseThrow(() -> ApiException.notFound("Assignment not found"));
        if (c.relation != Relation.OWNER && userId.equals(caller.getId())) {
            throw ApiException.forbidden("You cannot change your own assignment");
        }
        if (!BranchAccessPolicy.mayAssign(c.relation, s.getRole())) {
            throw ApiException.forbidden("You are not allowed to remove that assignment");
        }
        staff.delete(s);
        audit.record(caller, "BRANCH_STAFF_REMOVED", "BusinessBranch", c.branch.getId(), "user=" + userId + " role=" + s.getRole());
    }

    // ------------------------------------------------------------------ access for sibling services

    /** The caller's verified access to one branch of one business (owner, or an assignee). Anyone else gets 404. */
    public record Access(Business business, BusinessBranch branch, Relation relation, java.util.Set<BranchPermission> permissions) {
        public boolean isOwner() { return relation == Relation.OWNER; }
        public boolean has(BranchPermission p) { return permissions.contains(p); }
    }

    @Transactional(readOnly = true)
    public Access requireAccess(User caller, String businessId, String branchId) {
        Context c = context(caller, businessId, branchId, Action.VIEW);
        return new Access(c.business(), c.branch(), c.relation(), c.effective());
    }

    /** True when the caller is the authenticated owner of the business (404 if the business does not exist). */
    @Transactional(readOnly = true)
    public boolean isOwnerOf(User caller, String businessId) {
        return isOwner(caller, requireBusiness(businessId));
    }

    /** Branches of a business the caller may see reports for: all for the owner, otherwise only those where they hold VIEW_REPORTS. */
    @Transactional(readOnly = true)
    public List<Access> accessibleBranches(User caller, String businessId, BranchPermission needed) {
        Business b = requireBusiness(businessId);
        List<Access> out = new ArrayList<>();
        boolean owner = isOwner(caller, b);
        for (BusinessBranch br : branches.findByBusinessIdOrderByCreatedAtAsc(b.getId())) {
            if (owner) { out.add(new Access(b, br, Relation.OWNER, BranchAccessPolicy.effective(Relation.OWNER, null))); continue; }
            BranchStaff mine = staff.findByBranchIdAndUserId(br.getId(), caller.getId()).orElse(null);
            if (mine == null) continue;
            Relation r = mine.getRole() == BranchRole.MANAGER ? Relation.MANAGER : Relation.STAFF;
            java.util.Set<BranchPermission> eff = BranchAccessPolicy.effective(r, mine.getPermissions());
            if (needed == null || eff.contains(needed)) out.add(new Access(b, br, r, eff));
        }
        if (out.isEmpty() && !owner) throw ApiException.notFound("Business not found");
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private record Context(Business business, BusinessBranch branch, Relation relation, java.util.Set<BranchPermission> stored) {
        java.util.Set<BranchPermission> effective() { return BranchAccessPolicy.effective(relation, stored); }
    }

    /** Loads the branch THROUGH its business, computes the caller's relation, and enforces {@code action}. */
    private Context context(User caller, String businessId, String branchId, Action action) {
        Business b = requireBusiness(businessId);
        BusinessBranch br = branches.findById(branchId == null ? "" : branchId)
                .filter(x -> x.getBusiness().getId().equals(b.getId()))
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
        boolean owner = isOwner(caller, b);
        BranchStaff mine = owner ? null : staff.findByBranchIdAndUserId(br.getId(), caller.getId()).orElse(null);
        Relation r = owner ? Relation.OWNER : mine == null ? Relation.NONE
                : mine.getRole() == BranchRole.MANAGER ? Relation.MANAGER : Relation.STAFF;
        if (r == Relation.NONE) throw ApiException.notFound("Branch not found"); // no existence leak
        if (!BranchAccessPolicy.allows(r, action)) throw ApiException.forbidden("You do not have permission to do that at this branch");
        return new Context(b, br, r, mine == null ? java.util.Set.of() : mine.getPermissions());
    }

    private Business requireBusiness(String businessId) {
        return businesses.findById(businessId == null ? "" : businessId).orElseThrow(() -> ApiException.notFound("Business not found"));
    }

    private boolean isOwner(User caller, Business b) {
        return b.getOwner().getId().equals(caller.getId());
    }

    private Relation relationOf(User caller, BusinessBranch br) {
        return staff.findByBranchIdAndUserId(br.getId(), caller.getId())
                .map(s -> s.getRole() == BranchRole.MANAGER ? Relation.MANAGER : Relation.STAFF)
                .orElse(Relation.NONE);
    }

    private ApiException notFoundOrForbidden(User caller, Business b) {
        // Someone assigned to a branch of this business knows it exists, so 403; everyone else gets 404.
        boolean works = branches.findByBusinessIdOrderByCreatedAtAsc(b.getId()).stream().anyMatch(br -> relationOf(caller, br) != Relation.NONE);
        return works ? ApiException.forbidden("Only the business owner can do that") : ApiException.notFound("Business not found");
    }

    private User findUser(String identifier) {
        String id = identifier == null ? "" : identifier.trim();
        return (id.contains("@") ? users.findByEmail(id.toLowerCase(Locale.ROOT)) : users.findByPhoneNumber(id))
                .orElseThrow(() -> ApiException.notFound("No PULA user was found for that email address or phone number"));
    }

    private BusinessBranch saveUnique(BusinessBranch br, Business b) {
        try {
            return branches.saveAndFlush(br);
        } catch (DataIntegrityViolationException e) { // two concurrent requests with the same code: the unique index decides
            throw ApiException.conflict("This business already has a branch with that code");
        }
    }

    private void applyDetails(BusinessBranch br, String address, String suburb, String city, String province, String postal,
                              java.math.BigDecimal lat, java.math.BigDecimal lon, String phone, String email, List<String> hours) {
        BranchRules.requireValidCoordinates(lat, lon);
        br.setAddress(BranchRules.optionalText(address, 500, "Address"));
        br.setArea(BranchRules.optionalText(suburb, 255, "Suburb"));
        br.setCity(BranchRules.optionalText(city, 100, "City"));
        br.setProvince(BranchRules.optionalText(province, 100, "Province"));
        br.setPostalCode(BranchRules.optionalPostalCode(postal));
        br.setLatitude(lat);
        br.setLongitude(lon);
        br.setPhone(BranchRules.optionalPhone(phone));
        br.setEmail(BranchRules.optionalEmail(email));
        if (hours != null) br.setOperatingHours(new ArrayList<>(hours.stream().map(String::trim).filter(h -> !h.isEmpty()).toList()));
    }

    private void applyFranchise(BusinessBranch br, String brand, com.momocircle.branch.OperatingModel model, String operator) {
        br.setBrandName(BranchRules.optionalText(brand, 255, "Brand name"));
        br.setOperatingModel(model == null ? com.momocircle.branch.OperatingModel.OWNED : model);
        br.setOperatorName(BranchRules.optionalText(operator, 255, "Operator name"));
        if (br.getOperatingModel() == com.momocircle.branch.OperatingModel.OWNED && br.getOperatorName() != null) {
            throw new IllegalArgumentException("An operator name only applies to a franchised outlet");
        }
    }

    private void applyDetailsPartial(BusinessBranch br, UpdateBranchRequest r) {
        if (r.latitude() != null || r.longitude() != null) {
            BranchRules.requireValidCoordinates(r.latitude(), r.longitude());
            br.setLatitude(r.latitude());
            br.setLongitude(r.longitude());
        }
        if (r.address() != null) br.setAddress(BranchRules.optionalText(r.address(), 500, "Address"));
        if (r.suburb() != null) br.setArea(BranchRules.optionalText(r.suburb(), 255, "Suburb"));
        if (r.city() != null) br.setCity(BranchRules.optionalText(r.city(), 100, "City"));
        if (r.province() != null) br.setProvince(BranchRules.optionalText(r.province(), 100, "Province"));
        if (r.postalCode() != null) br.setPostalCode(BranchRules.optionalPostalCode(r.postalCode()));
        if (r.phone() != null) br.setPhone(BranchRules.optionalPhone(r.phone()));
        if (r.email() != null) br.setEmail(BranchRules.optionalEmail(r.email()));
        if (r.operatingHours() != null) {
            br.setOperatingHours(new ArrayList<>(r.operatingHours().stream().map(String::trim).filter(h -> !h.isEmpty()).toList()));
        }
    }

    private BranchView view(BusinessBranch br, Relation r) {
        return view(br, r, java.util.Set.of());
    }

    private BranchView view(BusinessBranch br, Relation r, java.util.Set<BranchPermission> stored) {
        return new BranchView(br.getId(), br.getBusiness().getId(), br.getBusiness().getName(), br.getName(), br.getBranchCode(),
                br.getAddress(), br.getArea(), br.getCity(), br.getProvince(), br.getPostalCode(), br.getLatitude(), br.getLongitude(),
                br.getPhone(), br.getEmail(), List.copyOf(br.getOperatingHours()), java.util.Set.copyOf(br.getFulfilmentOptions()),
                br.getStatus(), br.isTemporarilyClosed(), br.getVerificationStatus(), r.name(),
                BranchAccessPolicy.effective(r, stored), br.getBrandName(), br.getOperatingModel(), br.getOperatorName(),
                br.getCreatedAt(), br.getUpdatedAt());
    }

    private StaffView staffView(BranchStaff s) {
        User u = s.getUser();
        return new StaffView(u.getId(), u.getFullName(), u.getEmail(), u.getPhoneNumber(), s.getRole(),
                java.util.Set.copyOf(s.getPermissions()), s.getCreatedAt());
    }
}
