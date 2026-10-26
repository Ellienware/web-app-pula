package com.momocircle.dto;

import com.momocircle.branch.BranchPermission;
import com.momocircle.branch.BranchRole;
import com.momocircle.branch.OperatingModel;
import com.momocircle.branch.BranchStatus;
import com.momocircle.domain.enums.FulfilmentOption;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** General multi-branch management (Phase 9C). Food-specific settings stay on the /api/market/food/branches DTOs. */
public final class BranchDtos {

    private BranchDtos() {}

    public record CreateBranchRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 40) String branchCode,
            @Size(max = 500) String address,
            @Size(max = 255) String suburb,
            @Size(max = 100) String city,
            @Size(max = 100) String province,
            @Size(max = 10) String postalCode,
            BigDecimal latitude,
            BigDecimal longitude,
            @Size(max = 20) String phone,
            @Size(max = 255) String email,
            @Size(max = 14) List<@Size(max = 255) String> operatingHours,
            /** Franchise modelling (owner only, descriptive, NOT verified by PULA). */
            @Size(max = 255) String brandName,
            OperatingModel operatingModel,
            @Size(max = 255) String operatorName
    ) {
        public CreateBranchRequest(String name, String branchCode, String address, String suburb, String city, String province,
                                   String postalCode, BigDecimal latitude, BigDecimal longitude, String phone, String email,
                                   List<String> operatingHours) {
            this(name, branchCode, address, suburb, city, province, postalCode, latitude, longitude, phone, email, operatingHours,
                    null, null, null);
        }
    }

    /** Every field optional: only supplied fields change. A branch code can be changed only by the business owner. */
    public record UpdateBranchRequest(
            @Size(max = 255) String name,
            @Size(max = 40) String branchCode,
            @Size(max = 500) String address,
            @Size(max = 255) String suburb,
            @Size(max = 100) String city,
            @Size(max = 100) String province,
            @Size(max = 10) String postalCode,
            BigDecimal latitude,
            BigDecimal longitude,
            @Size(max = 20) String phone,
            @Size(max = 255) String email,
            @Size(max = 14) List<@Size(max = 255) String> operatingHours,
            /** Franchise modelling: owner only. */
            @Size(max = 255) String brandName,
            OperatingModel operatingModel,
            @Size(max = 255) String operatorName
    ) {
        public UpdateBranchRequest(String name, String branchCode, String address, String suburb, String city, String province,
                                   String postalCode, BigDecimal latitude, BigDecimal longitude, String phone, String email,
                                   List<String> operatingHours) {
            this(name, branchCode, address, suburb, city, province, postalCode, latitude, longitude, phone, email, operatingHours,
                    null, null, null);
        }
    }

    public record ChangeStatusRequest(@NotNull BranchStatus status) {}

    /** {@code identifier} is the person's registered email address or phone number. */
    public record AssignStaffRequest(@NotBlank @Size(max = 255) String identifier, @NotNull BranchRole role,
                                     /** Optional. Omitted: a MANAGER gets every permission, STAFF get none. */
                                     Set<BranchPermission> permissions) {
        public AssignStaffRequest(String identifier, BranchRole role) { this(identifier, role, null); }
    }

    public record SetPermissionsRequest(@NotNull Set<BranchPermission> permissions) {}

    public record BranchView(
            String id, String businessId, String businessName, String name, String branchCode,
            String address, String suburb, String city, String province, String postalCode,
            BigDecimal latitude, BigDecimal longitude, String phone, String email,
            List<String> operatingHours, Set<FulfilmentOption> fulfilmentOptions,
            BranchStatus status, boolean temporarilyClosed, String verificationStatus,
            /** The caller's relation to this branch: OWNER, MANAGER or STAFF. */
            String yourRole,
            /** What the caller may do here: all for the owner, the stored set for an assignee. */
            Set<BranchPermission> yourPermissions,
            String brandName, OperatingModel operatingModel, String operatorName,
            Instant createdAt, Instant updatedAt
    ) {}

    public record StaffView(String userId, String fullName, String email, String phone, BranchRole role,
                            Set<BranchPermission> permissions, Instant assignedAt) {}

    /** A branch the caller works at (manager or staff) - used to find the businesses a non-owner can act for. */
    public record MyAssignmentView(String branchId, String branchName, String businessId, String businessName,
                                   BranchRole role, BranchStatus status) {}
}
