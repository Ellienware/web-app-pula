package com.momocircle.service;

import com.momocircle.branch.BranchPermission;
import com.momocircle.domain.Order;
import com.momocircle.domain.OrderItem;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.dto.BranchOpsDtos.BranchOrderView;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.OrderRepository;
import com.momocircle.service.BranchManagementService.Access;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Branch-level order visibility and fulfilment. Authorisation is per branch and per permission (VIEW_ORDERS / FULFIL_ORDERS); an order
 * is only reachable through ITS OWN branch of ITS OWN business, so a client cannot read or move another branch's order by guessing an id.
 * Fulfilment reuses OrderService's state machine; branch staff cannot cancel, refund or re-assign an order to a different branch.
 */
@Service
public class BranchOrderService {

    private final BranchManagementService branches;
    private final OrderRepository orders;
    private final OrderService orderService;

    public BranchOrderService(BranchManagementService branches, OrderRepository orders, OrderService orderService) {
        this.branches = branches;
        this.orders = orders;
        this.orderService = orderService;
    }

    @Transactional(readOnly = true)
    public List<BranchOrderView> list(User caller, String businessId, String branchId, int page, int size) {
        Access a = branches.requireAccess(caller, businessId, branchId);
        if (!a.has(BranchPermission.VIEW_ORDERS)) throw ApiException.forbidden("You do not have permission to see this branch's orders");
        return orders.findByBranchIdOrderByCreatedAtDesc(a.branch().getId(),
                PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)))).stream().map(this::view).toList();
    }

    @Transactional
    public BranchOrderView fulfil(User caller, String businessId, String branchId, String orderId, OrderStatus status) {
        Access a = branches.requireAccess(caller, businessId, branchId);
        if (!a.has(BranchPermission.FULFIL_ORDERS)) throw ApiException.forbidden("You do not have permission to fulfil this branch's orders");
        Order order = orders.findById(orderId == null ? "" : orderId)
                .filter(o -> o.getBranch() != null && o.getBranch().getId().equals(a.branch().getId()))
                .filter(o -> o.getBusiness().getId().equals(a.business().getId()))
                .orElseThrow(() -> ApiException.notFound("Order not found")); // another branch's order looks like a missing one
        return view(orderService.fulfilBranchOrder(order, status));
    }

    private BranchOrderView view(Order o) {
        List<String> lines = o.getItems().stream().map((OrderItem i) -> i.getQuantity() + " x " + i.getProductNameSnapshot()).toList();
        String buyer = o.getBuyer() == null ? null : o.getBuyer().getFullName();
        return new BranchOrderView(o.getId(), o.getStatus(), o.getTotal(), o.getCurrency(), o.getItems().size(), buyer, lines, o.getCreatedAt());
    }
}
