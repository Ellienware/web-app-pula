package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.momocircle.branch.BranchPermission;
import com.momocircle.branch.BranchRole;
import com.momocircle.branch.BranchStatus;
import com.momocircle.domain.BranchStaff;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.User;
import com.momocircle.dto.BranchDtos.AssignStaffRequest;
import com.momocircle.dto.BranchDtos.BranchView;
import com.momocircle.dto.BranchDtos.CreateBranchRequest;
import com.momocircle.dto.BranchDtos.SetPermissionsRequest;
import com.momocircle.dto.BranchDtos.UpdateBranchRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BranchStaffRepository;
import com.momocircle.repository.BusinessBranchRepository;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.UserRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NOT VERIFIED IN THIS SANDBOX: no Maven here, so this was written against the existing Mockito test style but has not been
 * compiled or run. Run `mvn -Dtest=BranchManagementServiceTest test`. Covers tenant isolation, role limits, no self-escalation
 * and code uniqueness for the general branch API.
 */
class BranchManagementServiceTest {

    private BusinessRepository businesses;
    private BusinessBranchRepository branches;
    private BranchStaffRepository staff;
    private UserRepository users;
    private AuditLogService audit;
    private BranchManagementService service;

    private User owner, manager, worker, stranger, otherOwner;
    private Business business, otherBusiness;
    private BusinessBranch branchA, branchB, foreign;

    private static User user(String id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    private static BranchStaff assignment(BusinessBranch br, User u, BranchRole role, User by) {
        BranchStaff s = new BranchStaff();
        s.setBranch(br);
        s.setUser(u);
        s.setRole(role);
        s.setAssignedBy(by);
        return s;
    }

    @BeforeEach
    void setUp() {
        businesses = mock(BusinessRepository.class);
        branches = mock(BusinessBranchRepository.class);
        staff = mock(BranchStaffRepository.class);
        users = mock(UserRepository.class);
        audit = mock(AuditLogService.class);
        service = new BranchManagementService(businesses, branches, staff, users, audit);

        owner = user("owner", "owner@example.com");
        manager = user("mgr", "mgr@example.com");
        worker = user("wrk", "wrk@example.com");
        stranger = user("str", "str@example.com");
        otherOwner = user("owner2", "owner2@example.com");

        business = new Business();
        business.setId("biz-1");
        business.setOwner(owner);
        otherBusiness = new Business();
        otherBusiness.setId("biz-2");
        otherBusiness.setOwner(otherOwner);

        branchA = new BusinessBranch(); branchA.setId("br-a"); branchA.setName("A"); branchA.setBusiness(business);
        branchB = new BusinessBranch(); branchB.setId("br-b"); branchB.setName("B"); branchB.setBusiness(business);
        foreign = new BusinessBranch(); foreign.setId("br-x"); foreign.setName("X"); foreign.setBusiness(otherBusiness);

        when(businesses.findById("biz-1")).thenReturn(Optional.of(business));
        when(businesses.findById("biz-2")).thenReturn(Optional.of(otherBusiness));
        when(branches.findById("br-a")).thenReturn(Optional.of(branchA));
        when(branches.findById("br-b")).thenReturn(Optional.of(branchB));
        when(branches.findById("br-x")).thenReturn(Optional.of(foreign));
        when(branches.findByBusinessIdOrderByCreatedAtAsc("biz-1")).thenReturn(List.of(branchA, branchB));
        when(branches.save(any(BusinessBranch.class))).thenAnswer(i -> i.getArgument(0));
        when(branches.saveAndFlush(any(BusinessBranch.class))).thenAnswer(i -> i.getArgument(0));
        when(staff.save(any(BranchStaff.class))).thenAnswer(i -> i.getArgument(0));
        when(staff.saveAndFlush(any(BranchStaff.class))).thenAnswer(i -> i.getArgument(0));
        // manager is assigned to branch A only; worker is STAFF at branch A
        when(staff.findByBranchIdAndUserId("br-a", "mgr")).thenReturn(Optional.of(assignment(branchA, manager, BranchRole.MANAGER, owner)));
        when(staff.findByBranchIdAndUserId("br-a", "wrk")).thenReturn(Optional.of(assignment(branchA, worker, BranchRole.STAFF, owner)));
        when(staff.findByBranchIdAndUserId("br-b", "mgr")).thenReturn(Optional.empty());
        when(users.findByEmail("wrk@example.com")).thenReturn(Optional.of(worker));
        when(users.findByEmail("str@example.com")).thenReturn(Optional.of(stranger));
        when(users.findByEmail("mgr@example.com")).thenReturn(Optional.of(manager));
    }

    private CreateBranchRequest create(String name, String code) {
        return new CreateBranchRequest(name, code, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void ownerCreatesBranchWithNormalisedCode() {
        BranchView v = service.create(owner, "biz-1", create("Pretoria CBD", " pta cbd "));
        assertEquals("PTA-CBD", v.branchCode());
        assertEquals(BranchStatus.ACTIVE, v.status());
        assertEquals("UNVERIFIED", v.verificationStatus());
    }

    @Test
    void duplicateCodeInSameBusinessIsRejectedWithConflict() {
        when(branches.existsByBusinessIdAndBranchCode("biz-1", "PTA")).thenReturn(true);
        ApiException e = assertThrows(ApiException.class, () -> service.create(owner, "biz-1", create("Dup", "pta")));
        assertEquals(409, e.getStatus().value());
        verify(branches, never()).saveAndFlush(any());
    }

    @Test
    void invalidCoordinatesAndNamesAreBadRequestsAndNothingIsSaved() {
        CreateBranchRequest badCoords = new CreateBranchRequest("X", null, null, null, null, null, null,
                new BigDecimal("95"), new BigDecimal("10"), null, null, null);
        assertEquals(400, assertThrows(ApiException.class, () -> service.create(owner, "biz-1", badCoords)).getStatus().value());
        assertEquals(400, assertThrows(ApiException.class, () -> service.create(owner, "biz-1", create("  ", null))).getStatus().value());
        verify(branches, never()).saveAndFlush(any());
    }

    @Test
    void strangerCannotCreateUnderAnotherBusinessAndLearnsNothing() {
        assertEquals(404, assertThrows(ApiException.class, () -> service.create(stranger, "biz-1", create("Hack", null))).getStatus().value());
        assertEquals(404, assertThrows(ApiException.class, () -> service.create(owner, "biz-2", create("Hack", null))).getStatus().value());
        verify(branches, never()).saveAndFlush(any());
    }

    @Test
    void managerCannotCreateBranches() {
        assertEquals(403, assertThrows(ApiException.class, () -> service.create(manager, "biz-1", create("New", null))).getStatus().value());
    }

    @Test
    void branchIdFromAnotherBusinessIsNotFound() {
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(owner, "biz-1", "br-x")).getStatus().value());
        assertEquals(404, assertThrows(ApiException.class, () -> service.changeStatus(owner, "biz-1", "br-x", BranchStatus.SUSPENDED)).getStatus().value());
    }

    @Test
    void managerSeesOnlyTheirAssignedBranch() {
        List<BranchView> mine = service.list(manager, "biz-1");
        assertEquals(1, mine.size());
        assertEquals("br-a", mine.get(0).id());
        assertEquals(404, assertThrows(ApiException.class, () -> service.get(manager, "biz-1", "br-b")).getStatus().value());
        assertEquals(404, assertThrows(ApiException.class, () -> service.list(stranger, "biz-1")).getStatus().value());
    }

    @Test
    void managerMayEditDetailsButNotCodeOrStatus() {
        UpdateBranchRequest phone = new UpdateBranchRequest(null, null, null, null, null, null, null, null, null, "012 345 6789", null, null);
        assertEquals("012 345 6789", service.update(manager, "biz-1", "br-a", phone).phone());
        UpdateBranchRequest code = new UpdateBranchRequest(null, "NEW", null, null, null, null, null, null, null, null, null, null);
        assertEquals(403, assertThrows(ApiException.class, () -> service.update(manager, "biz-1", "br-a", code)).getStatus().value());
        assertEquals(403, assertThrows(ApiException.class, () -> service.changeStatus(manager, "biz-1", "br-a", BranchStatus.SUSPENDED)).getStatus().value());
    }

    @Test
    void staffAreReadOnly() {
        UpdateBranchRequest r = new UpdateBranchRequest("Renamed", null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(403, assertThrows(ApiException.class, () -> service.update(worker, "biz-1", "br-a", r)).getStatus().value());
        assertEquals(403, assertThrows(ApiException.class, () -> service.listStaff(worker, "biz-1", "br-a")).getStatus().value());
    }

    @Test
    void closedBranchIsPermanentAndLegacyActiveFlagFollowsStatus() {
        BranchView suspended = service.changeStatus(owner, "biz-1", "br-a", BranchStatus.SUSPENDED);
        assertEquals(BranchStatus.SUSPENDED, suspended.status());
        assertTrue(!branchA.isActive());
        service.changeStatus(owner, "biz-1", "br-a", BranchStatus.CLOSED);
        assertEquals(409, assertThrows(ApiException.class, () -> service.changeStatus(owner, "biz-1", "br-a", BranchStatus.ACTIVE)).getStatus().value());
    }

    @Test
    void managerCanAssignStaffButCannotCreateManagersOrSelfPromote() {
        when(staff.findByBranchIdAndUserId("br-a", "str")).thenReturn(Optional.empty());
        assertEquals(BranchRole.STAFF, service.assign(manager, "biz-1", "br-a", new AssignStaffRequest("str@example.com", BranchRole.STAFF)).role());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.assign(manager, "biz-1", "br-a", new AssignStaffRequest("str@example.com", BranchRole.MANAGER))).getStatus().value());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.assign(manager, "biz-1", "br-a", new AssignStaffRequest("mgr@example.com", BranchRole.MANAGER))).getStatus().value());
    }

    @Test
    void staffCannotAssignAnyoneOrPromoteThemselves() {
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.assign(worker, "biz-1", "br-a", new AssignStaffRequest("str@example.com", BranchRole.STAFF))).getStatus().value());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.assign(worker, "biz-1", "br-a", new AssignStaffRequest("wrk@example.com", BranchRole.MANAGER))).getStatus().value());
        verify(staff, never()).saveAndFlush(any());
    }

    @Test
    void ownerCanAssignManagerAndOwnerCannotBeAssigned() {
        when(staff.findByBranchIdAndUserId("br-a", "str")).thenReturn(Optional.empty());
        assertEquals(BranchRole.MANAGER, service.assign(owner, "biz-1", "br-a", new AssignStaffRequest("str@example.com", BranchRole.MANAGER)).role());
        when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(owner));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.assign(owner, "biz-1", "br-a", new AssignStaffRequest("owner@example.com", BranchRole.STAFF))).getStatus().value());
    }

    @Test
    void managerCannotRemoveAManagerOrThemselvesButCanRemoveStaff() {
        assertEquals(403, assertThrows(ApiException.class, () -> service.removeStaff(manager, "biz-1", "br-a", "mgr")).getStatus().value());
        service.removeStaff(manager, "biz-1", "br-a", "wrk");
        verify(staff).delete(any(BranchStaff.class));
    }

    @Test
    void assignmentCannotCrossBusinessBoundaries() {
        // owner of biz-1 tries to use a branch of biz-2 through biz-1's URL: it is simply not found, nothing is written
        assertEquals(404, assertThrows(ApiException.class,
                () -> service.assign(owner, "biz-1", "br-x", new AssignStaffRequest("str@example.com", BranchRole.STAFF))).getStatus().value());
        verify(staff, never()).saveAndFlush(any());
    }

    // ---- per-staff permissions (Phase 9C part 2)

    private BranchStaff mgrWith(BranchPermission... perms) {
        BranchStaff m = assignment(branchA, manager, BranchRole.MANAGER, owner);
        m.setPermissions(perms.length == 0 ? java.util.EnumSet.noneOf(BranchPermission.class) : java.util.EnumSet.of(perms[0], perms));
        when(staff.findByBranchIdAndUserId("br-a", "mgr")).thenReturn(Optional.of(m));
        return m;
    }

    @Test
    void ownerGrantsAManagerOnlyTheListedPermissions() {
        when(staff.findByBranchIdAndUserId("br-a", "str")).thenReturn(Optional.empty());
        var v = service.assign(owner, "biz-1", "br-a", new AssignStaffRequest("str@example.com", BranchRole.MANAGER,
                java.util.Set.of(BranchPermission.VIEW_ORDERS)));
        assertEquals(java.util.Set.of(BranchPermission.VIEW_ORDERS), v.permissions());
    }

    @Test
    void managerCannotGrantStaffPermissionsTheyDoNotHold() {
        mgrWith(BranchPermission.VIEW_ORDERS);
        when(staff.findByBranchIdAndUserId("br-a", "str")).thenReturn(Optional.empty());
        assertEquals(403, assertThrows(ApiException.class, () -> service.assign(manager, "biz-1", "br-a",
                new AssignStaffRequest("str@example.com", BranchRole.STAFF, java.util.Set.of(BranchPermission.MANAGE_STOCK)))).getStatus().value());
        assertEquals(BranchRole.STAFF, service.assign(manager, "biz-1", "br-a",
                new AssignStaffRequest("str@example.com", BranchRole.STAFF, java.util.Set.of(BranchPermission.VIEW_ORDERS))).role());
    }

    @Test
    void nobodyCanChangeTheirOwnPermissions() {
        mgrWith(BranchPermission.VIEW_ORDERS);
        assertEquals(403, assertThrows(ApiException.class, () -> service.setPermissions(manager, "biz-1", "br-a", "mgr",
                new SetPermissionsRequest(java.util.Set.of(BranchPermission.MANAGE_STOCK)))).getStatus().value());
    }

    @Test
    void managerCannotChangeAnotherManagersOrAStaffMembersOwnerGrantedAccess() {
        mgrWith(BranchPermission.VIEW_ORDERS);
        BranchStaff w = assignment(branchA, worker, BranchRole.STAFF, owner);
        w.setPermissions(java.util.EnumSet.of(BranchPermission.MANAGE_STOCK)); // owner gave staff more than this manager holds
        when(staff.findByBranchIdAndUserId("br-a", "wrk")).thenReturn(Optional.of(w));
        assertEquals(403, assertThrows(ApiException.class, () -> service.setPermissions(manager, "biz-1", "br-a", "wrk",
                new SetPermissionsRequest(java.util.Set.of(BranchPermission.VIEW_ORDERS)))).getStatus().value());
        assertEquals(java.util.Set.of(BranchPermission.MANAGE_STOCK), w.getPermissions());
    }

    @Test
    void ownerCanReplaceAStaffMembersPermissions() {
        BranchStaff w = assignment(branchA, worker, BranchRole.STAFF, owner);
        when(staff.findByBranchIdAndUserId("br-a", "wrk")).thenReturn(Optional.of(w));
        var v = service.setPermissions(owner, "biz-1", "br-a", "wrk",
                new SetPermissionsRequest(java.util.Set.of(BranchPermission.VIEW_ORDERS, BranchPermission.FULFIL_ORDERS)));
        assertEquals(2, v.permissions().size());
    }

    @Test
    void franchiseDetailsAreOwnerOnlyAndAnOperatorNeedsAFranchisedModel() {
        mgrWith(BranchPermission.MANAGE_PRICES);
        UpdateBranchRequest brand = new UpdateBranchRequest(null, null, null, null, null, null, null, null, null, null, null, null,
                "Example Brand", null, null);
        assertEquals(403, assertThrows(ApiException.class, () -> service.update(manager, "biz-1", "br-a", brand)).getStatus().value());
        UpdateBranchRequest bad = new UpdateBranchRequest(null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, "Some Franchisee");
        assertEquals(400, assertThrows(ApiException.class, () -> service.update(owner, "biz-1", "br-a", bad)).getStatus().value());
        UpdateBranchRequest ok = new UpdateBranchRequest(null, null, null, null, null, null, null, null, null, null, null, null,
                "Example Brand", com.momocircle.branch.OperatingModel.FRANCHISED, "Some Franchisee");
        assertEquals("Some Franchisee", service.update(owner, "biz-1", "br-a", ok).operatorName());
    }
}
