package com.momocircle.service;

import com.momocircle.domain.*;
import com.momocircle.domain.enums.*;
import com.momocircle.dto.ServiceDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import com.momocircle.security.ServiceRateLimiter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.List;

/**
 * Materials REUSE PULA Stock: a line points at a real wholesale product, is priced from it by the server, and is later
 * linked to an ordinary StockOrder placed through the existing stock checkout. There is no materials marketplace,
 * cart or fulfilment here.
 */
@Service
public class ServiceMaterialService {

    private static final int MAX_LINES = 30;

    private final ServiceRequestRepository requests;
    private final ServiceQuoteRepository quotes;
    private final ServiceMaterialLineRepository lines;
    private final WholesaleProductRepository products;
    private final StockOrderRepository stockOrders;
    private final BusinessRepository businesses;
    private final ServiceRequestService requestService;
    private final ServiceRateLimiter rateLimiter;
    private final AuditLogService audit;

    public ServiceMaterialService(ServiceRequestRepository requests, ServiceQuoteRepository quotes,
                                  ServiceMaterialLineRepository lines, WholesaleProductRepository products,
                                  StockOrderRepository stockOrders, BusinessRepository businesses,
                                  ServiceRequestService requestService, ServiceRateLimiter rateLimiter, AuditLogService audit) {
        this.requests = requests;
        this.quotes = quotes;
        this.lines = lines;
        this.products = products;
        this.stockOrders = stockOrders;
        this.businesses = businesses;
        this.requestService = requestService;
        this.rateLimiter = rateLimiter;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<MaterialLineView> list(User viewer, String requestId) {
        ServiceRequest r = requests.findById(requestId).orElseThrow(() -> ApiException.notFound("Request not found"));
        requestService.requireParty(r, viewer);
        return lines.findByRequestIdOrderByCreatedAtAsc(requestId).stream().map(ServiceViews::material).toList();
    }

    @Transactional
    public MaterialLineView add(User actor, String requestId, AddMaterialRequest req) {
        rateLimiter.check("service-material", actor.getId(), 60, Duration.ofHours(1));
        ServiceRequest r = requests.findForUpdate(requestId).orElseThrow(() -> ApiException.notFound("Request not found"));
        requestService.requireParty(r, actor);
        boolean provider = requestService.isProvider(r, actor);
        if (provider) {
            requestService.requireProviderActor(r, actor);
        }
        requireEditable(r);
        MaterialSupplier by = provider ? MaterialSupplier.PROVIDER : MaterialSupplier.CUSTOMER;
        if (req.suppliedBy() != by) {
            throw ApiException.forbidden("You can only add materials that you supply");
        }
        MaterialsResponsibility resp = r.getMaterialsResponsibility();
        boolean allowed = switch (resp) {
            case NONE -> false;
            case CUSTOMER -> by == MaterialSupplier.CUSTOMER;
            case PROVIDER -> by == MaterialSupplier.PROVIDER;
            case MIXED -> true;
        };
        if (!allowed) {
            throw ApiException.badRequest("This request's materials responsibility does not allow materials supplied by the "
                    + by.name().toLowerCase());
        }
        if (lines.findByRequestIdOrderByCreatedAtAsc(requestId).size() >= MAX_LINES) {
            throw ApiException.badRequest("Too many material lines");
        }
        WholesaleProduct product = products.findById(req.wholesaleProductId())
                .filter(WholesaleProduct::isActive)
                .orElseThrow(() -> ApiException.badRequest("That stock product is not available"));
        ServiceMaterialLine m = new ServiceMaterialLine();
        m.setRequest(r);
        m.setWholesaleProduct(product);
        m.setQuantityPacks(req.quantityPacks());
        m.setSuppliedBy(by);
        m.setUnitPriceSnapshot(product.getPricePerPack()); // priced by the server from PULA Stock, never by the client
        m.setAddedBy(actor);
        m = lines.save(m);
        audit.record(actor, "SERVICE_MATERIAL_ADDED", "SERVICE_REQUEST", requestId, product.getName() + " x" + req.quantityPacks());
        return ServiceViews.material(m);
    }

    @Transactional
    public void remove(User actor, String requestId, String lineId) {
        ServiceRequest r = requests.findForUpdate(requestId).orElseThrow(() -> ApiException.notFound("Request not found"));
        requestService.requireParty(r, actor);
        requireEditable(r);
        ServiceMaterialLine m = lines.findByIdAndRequestId(lineId, requestId).orElseThrow(() -> ApiException.notFound("Line not found"));
        if (!m.getAddedBy().getId().equals(actor.getId())) {
            throw ApiException.forbidden("Only the person who added a material line can remove it");
        }
        lines.delete(m);
        audit.record(actor, "SERVICE_MATERIAL_REMOVED", "SERVICE_REQUEST", requestId, lineId);
    }

    /**
     * After the supplying party has ordered the material through the normal PULA Stock checkout, they link that
     * StockOrder here. The order must be theirs, not cancelled, contain the product in sufficient quantity, and not be
     * attached to a different service request.
     */
    @Transactional
    public MaterialLineView linkStockOrder(User actor, String requestId, String lineId, String stockOrderId) {
        ServiceRequest r = requests.findForUpdate(requestId).orElseThrow(() -> ApiException.notFound("Request not found"));
        requestService.requireParty(r, actor);
        if (r.getStatus() != ServiceRequestStatus.BOOKED && r.getStatus() != ServiceRequestStatus.IN_PROGRESS
                && r.getStatus() != ServiceRequestStatus.COMPLETED) {
            throw ApiException.conflict("Materials can be linked once the service is booked");
        }
        ServiceMaterialLine m = lines.findByIdAndRequestId(lineId, requestId).orElseThrow(() -> ApiException.notFound("Line not found"));
        boolean provider = requestService.isProvider(r, actor);
        MaterialSupplier party = provider ? MaterialSupplier.PROVIDER : MaterialSupplier.CUSTOMER;
        if (m.getSuppliedBy() != party) {
            throw ApiException.forbidden("Only the party supplying this material can link its stock order");
        }
        Business buyer = businesses.findByOwnerId(actor.getId())
                .orElseThrow(() -> ApiException.badRequest("Ordering stock needs a business profile"));
        StockOrder order = stockOrders.findByIdAndBuyerBusinessId(stockOrderId, buyer.getId())
                .orElseThrow(() -> ApiException.notFound("Stock order not found")); // also hides other people's orders
        if (order.getStatus() == StockOrderStatus.CANCELLED) {
            throw ApiException.conflict("That stock order was cancelled");
        }
        final String productId = m.getWholesaleProduct().getId();
        final int needed = m.getQuantityPacks();
        boolean covers = order.getItems().stream()
                .anyMatch(i -> i.getWholesaleProduct().getId().equals(productId) && i.getQuantityPacks() >= needed);
        if (!covers) {
            throw ApiException.badRequest("That stock order does not contain the required material in the required quantity");
        }
        if (lines.existsByStockOrderIdAndRequestIdNot(stockOrderId, requestId)) {
            throw ApiException.conflict("That stock order is already linked to another service request");
        }
        if (m.getStockOrder() != null && !m.getStockOrder().getId().equals(stockOrderId)) {
            throw ApiException.conflict("This material is already linked to a stock order");
        }
        m.setStockOrder(order);
        m = lines.save(m);
        audit.record(actor, "SERVICE_MATERIAL_LINKED", "SERVICE_REQUEST", requestId, "stockOrder=" + stockOrderId);
        return ServiceViews.material(m);
    }

    /** Lines can change only before a quote is live: SUBMITTED, or QUOTED with no quote currently awaiting a decision. */
    private void requireEditable(ServiceRequest r) {
        boolean ok = r.getStatus() == ServiceRequestStatus.SUBMITTED || r.getStatus() == ServiceRequestStatus.DRAFT
                || (r.getStatus() == ServiceRequestStatus.QUOTED && quotes.findByRequestIdOrderByVersionNoDesc(r.getId()).stream()
                        .noneMatch(q -> q.getStatus() == ServiceQuoteStatus.SUBMITTED));
        if (!ok) {
            throw ApiException.conflict("Materials are locked while a quote is awaiting a decision or after booking");
        }
    }
}
