package com.momocircle.controller;

import com.momocircle.dto.BranchOpsDtos.BranchOrderView;
import com.momocircle.dto.BranchOpsDtos.BranchReportView;
import com.momocircle.dto.BranchOpsDtos.DirectoryBranch;
import com.momocircle.dto.BranchOpsDtos.DirectoryProduct;
import com.momocircle.dto.BranchOpsDtos.FulfilRequest;
import com.momocircle.dto.BranchStockDtos.AvailabilityRequest;
import com.momocircle.dto.BranchStockDtos.BranchAvailabilityView;
import com.momocircle.dto.BranchStockDtos.MoveRequest;
import com.momocircle.dto.BranchStockDtos.MovementView;
import com.momocircle.dto.BranchStockDtos.SetItemRequest;
import com.momocircle.dto.BranchStockDtos.StockItemView;
import com.momocircle.dto.BranchStockDtos.TransferRequest;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.BranchDirectoryService;
import com.momocircle.service.BranchOrderService;
import com.momocircle.service.BranchReportService;
import com.momocircle.service.BranchStockService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Branch stock, branch orders, parent reporting and the customer branch directory. Only the two GET directory routes are public
 * (see SecurityConfig); everything else needs a logged-in user and is authorised per branch and per permission by the services.
 */
@RestController
public class BranchOpsController {

    private static final String B = "/api/businesses/{businessId}/branches/{branchId}";

    private final BranchStockService stock;
    private final BranchOrderService orders;
    private final BranchReportService reports;
    private final BranchDirectoryService directory;
    private final CurrentUserService currentUser;

    public BranchOpsController(BranchStockService stock, BranchOrderService orders, BranchReportService reports,
                               BranchDirectoryService directory, CurrentUserService currentUser) {
        this.stock = stock;
        this.orders = orders;
        this.reports = reports;
        this.directory = directory;
        this.currentUser = currentUser;
    }

    // ---- stock (non-food)
    @GetMapping(B + "/stock")
    public List<StockItemView> stock(@PathVariable String businessId, @PathVariable String branchId) {
        return stock.list(currentUser.require(), businessId, branchId);
    }

    @PatchMapping(B + "/stock/{productId}")
    public StockItemView setItem(@PathVariable String businessId, @PathVariable String branchId, @PathVariable String productId,
                                 @RequestBody SetItemRequest req) {
        return stock.setItem(currentUser.require(), businessId, branchId, productId, req);
    }

    @PostMapping(B + "/stock/allocate")
    public StockItemView allocate(@PathVariable String businessId, @PathVariable String branchId, @Valid @RequestBody MoveRequest req) {
        return stock.allocate(currentUser.require(), businessId, branchId, req);
    }

    @PostMapping(B + "/stock/return")
    public StockItemView returnToCentral(@PathVariable String businessId, @PathVariable String branchId, @Valid @RequestBody MoveRequest req) {
        return stock.returnToCentral(currentUser.require(), businessId, branchId, req);
    }

    @PostMapping(B + "/stock/transfer")
    public StockItemView transfer(@PathVariable String businessId, @PathVariable String branchId, @Valid @RequestBody TransferRequest req) {
        return stock.transfer(currentUser.require(), businessId, branchId, req);
    }

    @GetMapping(B + "/stock/movements")
    public List<MovementView> movements(@PathVariable String businessId, @PathVariable String branchId,
                                        @RequestParam(defaultValue = "50") int limit) {
        return stock.movements(currentUser.require(), businessId, branchId, limit);
    }

    // ---- branch orders
    @GetMapping(B + "/orders")
    public List<BranchOrderView> orders(@PathVariable String businessId, @PathVariable String branchId,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
        return orders.list(currentUser.require(), businessId, branchId, page, size);
    }

    @PostMapping(B + "/orders/{orderId}/status")
    public BranchOrderView fulfil(@PathVariable String businessId, @PathVariable String branchId, @PathVariable String orderId,
                                  @Valid @RequestBody FulfilRequest req) {
        return orders.fulfil(currentUser.require(), businessId, branchId, orderId, req.status());
    }

    // ---- parent-business report
    @GetMapping("/api/businesses/{businessId}/branch-report")
    public BranchReportView report(@PathVariable String businessId,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return reports.report(currentUser.require(), businessId, from, to);
    }

    // ---- public directory (the two GETs are permit-listed) and the authenticated basket check
    @GetMapping("/api/businesses/{businessId}/branch-directory")
    public List<DirectoryBranch> directory(@PathVariable String businessId) {
        return directory.list(businessId);
    }

    @GetMapping("/api/businesses/{businessId}/branch-directory/{branchId}/products")
    public List<DirectoryProduct> directoryProducts(@PathVariable String businessId, @PathVariable String branchId) {
        return directory.products(businessId, branchId);
    }

    @PostMapping("/api/businesses/{businessId}/branch-directory/check")
    public List<BranchAvailabilityView> check(@PathVariable String businessId, @Valid @RequestBody AvailabilityRequest req) {
        currentUser.require();
        return directory.check(businessId, req.items());
    }
}
