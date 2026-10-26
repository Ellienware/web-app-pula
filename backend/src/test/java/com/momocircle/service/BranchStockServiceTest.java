package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.momocircle.branch.BranchAccessPolicy.Relation;
import com.momocircle.branch.BranchMovementType;
import com.momocircle.branch.BranchPermission;
import com.momocircle.branch.BranchStatus;
import com.momocircle.domain.BranchProductStock;
import com.momocircle.domain.BranchStockMovement;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.Order;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.dto.BranchStockDtos.MoveRequest;
import com.momocircle.dto.BranchStockDtos.TransferRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BranchProductStockRepository;
import com.momocircle.repository.BranchStockMovementRepository;
import com.momocircle.repository.BusinessBranchRepository;
import com.momocircle.repository.ProductRepository;
import com.momocircle.service.BranchManagementService.Access;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NOT VERIFIED IN THIS SANDBOX: written in the style of the existing Mockito tests but never compiled or run (no Maven here).
 * Run `mvn -Dtest=BranchStockServiceTest test`. Covers owner-only central moves, two-ended transfer permission, conservation of units,
 * the checkout reserve/release pair and the non-food-only rule.
 */
class BranchStockServiceTest {

    private BranchManagementService branches;
    private BusinessBranchRepository branchRepo;
    private BranchProductStockRepository stock;
    private BranchStockMovementRepository moves;
    private ProductRepository products;
    private AuditLogService audit;
    private BranchStockService service;

    private User owner, manager;
    private Business business, foodBusiness;
    private BusinessBranch a, b;
    private Product product;
    private BranchProductStock rowA, rowB;

    @BeforeEach
    void setUp() {
        branches = mock(BranchManagementService.class);
        branchRepo = mock(BusinessBranchRepository.class);
        stock = mock(BranchProductStockRepository.class);
        moves = mock(BranchStockMovementRepository.class);
        products = mock(ProductRepository.class);
        audit = mock(AuditLogService.class);
        service = new BranchStockService(branches, branchRepo, stock, moves, products, audit);

        owner = new User(); owner.setId("owner");
        manager = new User(); manager.setId("mgr");
        business = new Business(); business.setId("biz"); business.setOwner(owner); business.setBusinessType(BusinessType.FORMAL_RETAILER);
        foodBusiness = new Business(); foodBusiness.setId("food"); foodBusiness.setOwner(owner); foodBusiness.setBusinessType(BusinessType.RESTAURANT);
        a = new BusinessBranch(); a.setId("br-a"); a.setBusiness(business);
        b = new BusinessBranch(); b.setId("br-b"); b.setBusiness(business);
        product = new Product(); product.setId("p1"); product.setBusiness(business); product.setName("Maize meal");
        product.setPrice(new BigDecimal("50.00")); product.setCurrency("ZAR"); product.setStock(100);

        rowA = new BranchProductStock(); rowA.setBranch(a); rowA.setProduct(product); rowA.setQuantity(10);
        rowB = new BranchProductStock(); rowB.setBranch(b); rowB.setProduct(product); rowB.setQuantity(0);

        when(products.findByIdForUpdate("p1")).thenReturn(Optional.of(product));
        when(products.findById("p1")).thenReturn(Optional.of(product));
        when(stock.findForUpdate("br-a", "p1")).thenReturn(Optional.of(rowA));
        when(stock.findForUpdate("br-b", "p1")).thenReturn(Optional.of(rowB));
        when(stock.save(any(BranchProductStock.class))).thenAnswer(i -> i.getArgument(0));
        when(products.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));
        when(moves.save(any(BranchStockMovement.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Access ownerAccess(BusinessBranch br) {
        return new Access(business, br, Relation.OWNER, EnumSet.allOf(BranchPermission.class));
    }

    private Access managerAccess(BusinessBranch br, BranchPermission... perms) {
        return new Access(business, br, Relation.MANAGER, perms.length == 0 ? EnumSet.noneOf(BranchPermission.class) : EnumSet.of(perms[0], perms));
    }

    @Test
    void ownerAllocatesFromCentralPoolAndUnitsAreConserved() {
        when(branches.requireAccess(owner, "biz", "br-a")).thenReturn(ownerAccess(a));
        service.allocate(owner, "biz", "br-a", new MoveRequest("p1", 30, "opening stock"));
        assertEquals(70, product.getStock());
        assertEquals(40, rowA.getQuantity());
        assertEquals(110, product.getStock() + rowA.getQuantity()); // 100 + 10 before, nothing created or lost
        verify(moves).save(any(BranchStockMovement.class));
    }

    @Test
    void cannotAllocateMoreThanTheCentralPool() {
        when(branches.requireAccess(owner, "biz", "br-a")).thenReturn(ownerAccess(a));
        assertEquals(400, assertThrows(ApiException.class, () -> service.allocate(owner, "biz", "br-a", new MoveRequest("p1", 101, null))).getStatus().value());
        assertEquals(100, product.getStock());
    }

    @Test
    void managerCannotTouchTheCentralPoolEvenWithStockPermission() {
        when(branches.requireAccess(manager, "biz", "br-a")).thenReturn(managerAccess(a, BranchPermission.MANAGE_STOCK));
        assertEquals(403, assertThrows(ApiException.class, () -> service.allocate(manager, "biz", "br-a", new MoveRequest("p1", 1, null))).getStatus().value());
        assertEquals(403, assertThrows(ApiException.class, () -> service.returnToCentral(manager, "biz", "br-a", new MoveRequest("p1", 1, null))).getStatus().value());
        verify(moves, never()).save(any());
    }

    @Test
    void transferNeedsStockPermissionAtBothEnds() {
        when(branches.requireAccess(manager, "biz", "br-a")).thenReturn(managerAccess(a, BranchPermission.MANAGE_STOCK));
        when(branches.requireAccess(manager, "biz", "br-b")).thenReturn(managerAccess(b, BranchPermission.VIEW_ORDERS));
        assertEquals(403, assertThrows(ApiException.class, () -> service.transfer(manager, "biz", "br-a", new TransferRequest("br-b", "p1", 5, null))).getStatus().value());
        assertEquals(10, rowA.getQuantity());
        assertEquals(0, rowB.getQuantity());
    }

    @Test
    void ownerTransferMovesUnitsWithoutCreatingOrLosingAny() {
        when(branches.requireAccess(owner, "biz", "br-a")).thenReturn(ownerAccess(a));
        when(branches.requireAccess(owner, "biz", "br-b")).thenReturn(ownerAccess(b));
        service.transfer(owner, "biz", "br-a", new TransferRequest("br-b", "p1", 4, "restock"));
        assertEquals(6, rowA.getQuantity());
        assertEquals(4, rowB.getQuantity());
    }

    @Test
    void transferToABranchOfAnotherBusinessIsNotFound() {
        when(branches.requireAccess(owner, "biz", "br-a")).thenReturn(ownerAccess(a));
        when(branches.requireAccess(owner, "biz", "br-x")).thenThrow(ApiException.notFound("Branch not found"));
        assertEquals(404, assertThrows(ApiException.class, () -> service.transfer(owner, "biz", "br-a", new TransferRequest("br-x", "p1", 1, null))).getStatus().value());
        assertEquals(10, rowA.getQuantity());
    }

    @Test
    void cannotTransferMoreThanTheSourceHolds() {
        when(branches.requireAccess(owner, "biz", "br-a")).thenReturn(ownerAccess(a));
        when(branches.requireAccess(owner, "biz", "br-b")).thenReturn(ownerAccess(b));
        assertEquals(400, assertThrows(ApiException.class, () -> service.transfer(owner, "biz", "br-a", new TransferRequest("br-b", "p1", 11, null))).getStatus().value());
        assertEquals(10, rowA.getQuantity());
    }

    @Test
    void foodBusinessesUseTheFoodMenuInstead() {
        a.setBusiness(foodBusiness);
        when(branches.requireAccess(owner, "food", "br-a")).thenReturn(new Access(foodBusiness, a, Relation.OWNER, EnumSet.allOf(BranchPermission.class)));
        assertEquals(409, assertThrows(ApiException.class, () -> service.list(owner, "food", "br-a")).getStatus().value());
    }

    @Test
    void reserveTakesFromTheBranchAndReleaseGivesItBackExactlyOnce() {
        service.reserveForOrder(a, product, 4, "order-1");
        assertEquals(6, rowA.getQuantity());

        BranchStockMovement reserved = new BranchStockMovement();
        reserved.setBusinessId("biz"); reserved.setProductId("p1"); reserved.setFromBranchId("br-a"); reserved.setQuantity(4);
        reserved.setMovementType(BranchMovementType.SALE_RESERVE); reserved.setOrderId("order-1");
        when(moves.findByOrderIdAndMovementType("order-1", BranchMovementType.SALE_RESERVE)).thenReturn(List.of(reserved));
        when(moves.existsByOrderIdAndProductIdAndMovementType("order-1", "p1", BranchMovementType.SALE_RELEASE)).thenReturn(false);
        Order order = new Order(); order.setId("order-1");
        service.releaseForOrder(order);
        assertEquals(10, rowA.getQuantity());

        when(moves.existsByOrderIdAndProductIdAndMovementType("order-1", "p1", BranchMovementType.SALE_RELEASE)).thenReturn(true);
        service.releaseForOrder(order); // a second release must not add stock again
        assertEquals(10, rowA.getQuantity());
    }

    @Test
    void reserveRefusesMoreThanTheBranchHasAndItemsNotSoldThere() {
        assertEquals(400, assertThrows(ApiException.class, () -> service.reserveForOrder(a, product, 11, "order-2")).getStatus().value());
        assertEquals(10, rowA.getQuantity());
        rowA.setAvailable(false);
        assertEquals(400, assertThrows(ApiException.class, () -> service.reserveForOrder(a, product, 1, "order-3")).getStatus().value());
    }

    @Test
    void checkoutBranchMustBeActiveAndBelongToTheSeller() {
        when(branchRepo.findById("br-a")).thenReturn(Optional.of(a));
        a.applyStatus(BranchStatus.SUSPENDED);
        assertEquals(409, assertThrows(ApiException.class, () -> service.resolveForCheckout(business, "br-a")).getStatus().value());
        BusinessBranch other = new BusinessBranch(); other.setId("br-z"); other.setBusiness(foodBusiness);
        when(branchRepo.findById("br-z")).thenReturn(Optional.of(other));
        assertEquals(400, assertThrows(ApiException.class, () -> service.resolveForCheckout(business, "br-z")).getStatus().value());
        assertEquals(null, service.resolveForCheckout(business, null));
    }
}
