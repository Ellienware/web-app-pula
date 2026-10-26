package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.StockCartItem;
import com.momocircle.domain.User;
import com.momocircle.domain.WholesaleProduct;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.dto.StockDtos.AddStockCartItemRequest;
import com.momocircle.dto.StockDtos.StockCartItemResponse;
import com.momocircle.dto.StockDtos.StockCartResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.StockCartRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Stock cart for B2B procurement (brief §14). The buyer is a Business
 * (not a User) — businesses buy stock, not individual people on behalf of a business.
 * Totals, MOQ validation, and stock checks are all computed server-side.
 */
@Service
public class StockCartService {

    private final StockCartRepository cartRepo;
    private final WholesaleProductService productService;
    private final BusinessService businessService;

    public StockCartService(StockCartRepository cartRepo, WholesaleProductService productService,
                             BusinessService businessService) {
        this.cartRepo = cartRepo;
        this.productService = productService;
        this.businessService = businessService;
    }

    @Transactional
    public StockCartResponse addOrUpdate(User buyer, AddStockCartItemRequest req) {
        Business business = requireBuyerBusiness(buyer);
        WholesaleProduct product = productService.requireById(req.wholesaleProductId());
        if (req.quantityPacks() < 1) throw ApiException.badRequest("Quantity must be at least 1 pack");

        StockCartItem item = cartRepo
                .findByBuyerBusinessIdAndWholesaleProductId(business.getId(), product.getId())
                .orElseGet(() -> {
                    StockCartItem i = new StockCartItem();
                    i.setBuyerBusiness(business);
                    i.setWholesaleProduct(product);
                    return i;
                });
        item.setQuantityPacks(req.quantityPacks());
        cartRepo.save(item);
        return buildCart(business);
    }

    @Transactional
    public StockCartResponse remove(User buyer, String wholesaleProductId) {
        Business business = requireBuyerBusiness(buyer);
        cartRepo.deleteByBuyerBusinessIdAndWholesaleProductId(business.getId(), wholesaleProductId);
        return buildCart(business);
    }

    @Transactional(readOnly = true)
    public StockCartResponse getCart(User buyer) {
        Business business = requireBuyerBusiness(buyer);
        return buildCart(business);
    }

    @Transactional
    public void clearCart(Business buyerBusiness) {
        cartRepo.deleteByBuyerBusinessId(buyerBusiness.getId());
    }

    /** Returns the raw items for a buyer business — used by StockOrderService at checkout. */
    @Transactional(readOnly = true)
    public List<StockCartItem> rawItems(Business buyerBusiness) {
        return cartRepo.findByBuyerBusinessIdOrderByCreatedAtAsc(buyerBusiness.getId());
    }

    // ---- Internals ----

    private StockCartResponse buildCart(Business business) {
        List<StockCartItem> items = cartRepo.findByBuyerBusinessIdOrderByCreatedAtAsc(business.getId());
        List<StockCartItemResponse> responses = new ArrayList<>();
        List<String> validationMessages = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        boolean valid = true;
        String currency = "ZAR";

        for (StockCartItem item : items) {
            WholesaleProduct p = item.getWholesaleProduct();
            currency = p.getCurrency();
            BigDecimal line = p.getPricePerPack().multiply(BigDecimal.valueOf(item.getQuantityPacks()));
            subtotal = subtotal.add(line);
            boolean moqMet = item.getQuantityPacks() >= p.getMoq();
            boolean stockOk = item.getQuantityPacks() <= p.getAvailableStock();
            if (!moqMet) {
                valid = false;
                validationMessages.add(p.getName() + ": minimum order is " + p.getMoq() + " pack(s)");
            }
            if (!stockOk) {
                valid = false;
                validationMessages.add(p.getName() + ": only " + p.getAvailableStock() + " pack(s) in stock");
            }
            responses.add(new StockCartItemResponse(
                    item.getId(), p.getId(), p.getName(), p.getSku(), p.getPackSize(), p.getMoq(),
                    p.getPricePerPack(), p.getCurrency(), item.getQuantityPacks(), line,
                    moqMet, stockOk, p.getSupplierBusiness().getId(), p.getSupplierBusiness().getName()
            ));
        }
        return new StockCartResponse(responses, subtotal, currency, valid, validationMessages);
    }

    private Business requireBuyerBusiness(User buyer) {
        Business business = businessService.requireOwn(buyer);
        if (!business.hasCapability(BusinessCapability.CAN_BUY_STOCK)) {
            throw ApiException.forbidden("Your business needs the 'buy stock from suppliers' capability to use the stock cart");
        }
        return business;
    }
}
