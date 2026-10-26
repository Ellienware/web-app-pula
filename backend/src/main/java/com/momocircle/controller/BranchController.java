package com.momocircle.controller;

import com.momocircle.dto.BranchDtos.AssignStaffRequest;
import com.momocircle.dto.BranchDtos.BranchView;
import com.momocircle.dto.BranchDtos.ChangeStatusRequest;
import com.momocircle.dto.BranchDtos.CreateBranchRequest;
import com.momocircle.dto.BranchDtos.MyAssignmentView;
import com.momocircle.dto.BranchDtos.SetPermissionsRequest;
import com.momocircle.dto.BranchDtos.StaffView;
import com.momocircle.dto.BranchDtos.UpdateBranchRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.BranchManagementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * General branch management for any business type. All routes need authentication (they are NOT in the public permit-list);
 * the service decides per request whether the caller owns the business or is assigned to the branch. The older
 * /api/market/food/branches routes are unchanged and operate on the same rows.
 */
@RestController
public class BranchController {

    private final BranchManagementService service;
    private final CurrentUserService currentUser;

    public BranchController(BranchManagementService service, CurrentUserService currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/businesses/{businessId}/branches")
    public List<BranchView> list(@PathVariable String businessId) {
        return service.list(currentUser.require(), businessId);
    }

    @PostMapping("/api/businesses/{businessId}/branches")
    public ResponseEntity<BranchView> create(@PathVariable String businessId, @Valid @RequestBody CreateBranchRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(currentUser.require(), businessId, req));
    }

    @GetMapping("/api/businesses/{businessId}/branches/{branchId}")
    public BranchView get(@PathVariable String businessId, @PathVariable String branchId) {
        return service.get(currentUser.require(), businessId, branchId);
    }

    @PatchMapping("/api/businesses/{businessId}/branches/{branchId}")
    public BranchView update(@PathVariable String businessId, @PathVariable String branchId, @Valid @RequestBody UpdateBranchRequest req) {
        return service.update(currentUser.require(), businessId, branchId, req);
    }

    @PostMapping("/api/businesses/{businessId}/branches/{branchId}/status")
    public BranchView changeStatus(@PathVariable String businessId, @PathVariable String branchId, @Valid @RequestBody ChangeStatusRequest req) {
        return service.changeStatus(currentUser.require(), businessId, branchId, req.status());
    }

    @GetMapping("/api/businesses/{businessId}/branches/{branchId}/staff")
    public List<StaffView> staff(@PathVariable String businessId, @PathVariable String branchId) {
        return service.listStaff(currentUser.require(), businessId, branchId);
    }

    @PostMapping("/api/businesses/{businessId}/branches/{branchId}/staff")
    public StaffView assign(@PathVariable String businessId, @PathVariable String branchId, @Valid @RequestBody AssignStaffRequest req) {
        return service.assign(currentUser.require(), businessId, branchId, req);
    }

    @org.springframework.web.bind.annotation.PutMapping("/api/businesses/{businessId}/branches/{branchId}/staff/{userId}/permissions")
    public StaffView setPermissions(@PathVariable String businessId, @PathVariable String branchId, @PathVariable String userId,
                                    @Valid @RequestBody SetPermissionsRequest req) {
        return service.setPermissions(currentUser.require(), businessId, branchId, userId, req);
    }

    @DeleteMapping("/api/businesses/{businessId}/branches/{branchId}/staff/{userId}")
    public ResponseEntity<Void> removeStaff(@PathVariable String businessId, @PathVariable String branchId, @PathVariable String userId) {
        service.removeStaff(currentUser.require(), businessId, branchId, userId);
        return ResponseEntity.noContent().build();
    }

    /** Branches the caller works at as manager or staff (empty for a plain owner/customer). */
    @GetMapping("/api/me/branch-assignments")
    public List<MyAssignmentView> myAssignments() {
        return service.myAssignments(currentUser.require());
    }
}
