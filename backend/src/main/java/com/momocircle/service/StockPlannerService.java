package com.momocircle.service;

import com.momocircle.domain.Business;
import com.momocircle.domain.User;
import com.momocircle.domain.WholesaleProduct;
import com.momocircle.domain.enums.BusinessCapability;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.WholesaleProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PULA stock planner. Turns a restocking budget into a PROPOSED stock cart using only real
 * wholesale listings (never invented products, prices or stock). It respects MOQ and
 * available stock, prefers the cheapest supplier for the same product name + pack size,
 * counts each chosen supplier's real delivery fee, and reports savings versus the most
 * expensive listing of the same product. It NEVER buys anything: the proposal is reviewed
 * and confirmed by the user, added to the stock cart, and paid through normal checkout.
 */
@Service
public class StockPlannerService {

    public record PlanRequest(BigDecimal budget, String category) {}
    public record PlanLine(String wholesaleProductId, String supplierBusinessId, String supplierName,
                           String productName, String sku, int packSize, int quantityPacks,
                           BigDecimal pricePerPack, BigDecimal lineTotal) {}
    public record SupplierTotal(String supplierBusinessId, String supplierName, BigDecimal subtotal,
                                BigDecimal deliveryFee, BigDecimal total) {}
    public record StockPlan(BigDecimal budget, String currency, List<PlanLine> lines,
                            List<SupplierTotal> suppliers, BigDecimal estimatedTotal, BigDecimal remainingBudget,
                            BigDecimal potentialSavings, List<String> notes) {}

    private final BusinessService businessService;
    private final WholesaleProductRepository productRepository;

    public StockPlannerService(BusinessService businessService, WholesaleProductRepository productRepository) {
        this.businessService = businessService;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public StockPlan plan(User buyer, BigDecimal budget, String category) {
        Business me = businessService.requireOwn(buyer);
        if (!me.hasCapability(BusinessCapability.CAN_BUY_STOCK)) {
            throw ApiException.forbidden("Your business needs the 'buy stock from suppliers' capability");
        }
        if (budget == null || budget.signum() <= 0) throw ApiException.badRequest("Enter a budget greater than zero");
        if (budget.compareTo(new BigDecimal("10000000")) > 0) throw ApiException.badRequest("Budget is too large");

        String cat = category == null || category.isBlank() ? null : category.trim();
        List<WholesaleProduct> all = productRepository.browse(cat, PageRequest.of(0, 200)).getContent().stream()
                .filter(p -> !p.getSupplierBusiness().getId().equals(me.getId()))
                .filter(p -> !"INDIVIDUAL_ONLY".equals(p.getSellingMode()))
                .filter(p -> p.getAvailableStock() >= p.getMoq() && p.getMoq() >= 1)
                .toList();

        // Group identical products (same normalised name + pack size) across suppliers.
        Map<String, List<WholesaleProduct>> groups = new LinkedHashMap<>();
        for (WholesaleProduct p : all) {
            groups.computeIfAbsent(p.getName().trim().toLowerCase() + "|" + p.getPackSize(), k -> new ArrayList<>()).add(p);
        }
        List<WholesaleProduct> chosen = new ArrayList<>();
        Map<String, BigDecimal> maxPriceByChosen = new LinkedHashMap<>();
        for (List<WholesaleProduct> g : groups.values()) {
            WholesaleProduct best = g.stream().min(Comparator.comparing(WholesaleProduct::getPricePerPack)).orElseThrow();
            chosen.add(best);
            maxPriceByChosen.put(best.getId(), g.stream().map(WholesaleProduct::getPricePerPack).max(BigDecimal::compareTo).orElse(best.getPricePerPack()));
        }
        chosen.sort(Comparator.comparing(WholesaleProduct::getPricePerPack));

        Map<String, Integer> qty = new LinkedHashMap<>();
        Map<String, BigDecimal> supplierSubtotal = new LinkedHashMap<>();
        Map<String, Business> supplierById = new LinkedHashMap<>();
        BigDecimal spent = BigDecimal.ZERO;
        List<String> notes = new ArrayList<>();

        // Pass 1: MOQ of each product, cheapest first, while it still fits the budget (including delivery).
        for (WholesaleProduct p : chosen) {
            BigDecimal cost = p.getPricePerPack().multiply(BigDecimal.valueOf(p.getMoq()));
            BigDecimal extra = cost.add(newSupplierFee(p, supplierSubtotal));
            if (spent.add(extra).compareTo(budget) <= 0) {
                addLine(p, p.getMoq(), qty, supplierSubtotal, supplierById);
                spent = spent.add(extra);
            }
        }
        // Pass 2: top up one pack at a time (cheapest first, round robin) while budget and stock allow.
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (WholesaleProduct p : chosen) {
                Integer have = qty.get(p.getId());
                if (have == null || have >= p.getAvailableStock()) continue;
                if (spent.add(p.getPricePerPack()).compareTo(budget) <= 0) {
                    addLine(p, 1, qty, supplierSubtotal, supplierById);
                    spent = spent.add(p.getPricePerPack());
                    progressed = true;
                }
            }
        }

        List<PlanLine> lines = new ArrayList<>();
        BigDecimal savings = BigDecimal.ZERO;
        for (WholesaleProduct p : chosen) {
            Integer q = qty.get(p.getId());
            if (q == null) continue;
            BigDecimal lt = p.getPricePerPack().multiply(BigDecimal.valueOf(q));
            lines.add(new PlanLine(p.getId(), p.getSupplierBusiness().getId(), p.getSupplierBusiness().getName(),
                    p.getName(), p.getSku(), p.getPackSize(), q, p.getPricePerPack(), lt));
            savings = savings.add(maxPriceByChosen.get(p.getId()).subtract(p.getPricePerPack()).multiply(BigDecimal.valueOf(q)));
        }
        List<SupplierTotal> suppliers = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : supplierSubtotal.entrySet()) {
            Business s = supplierById.get(e.getKey());
            BigDecimal fee = s.getDeliveryFee() != null ? s.getDeliveryFee() : BigDecimal.ZERO;
            suppliers.add(new SupplierTotal(s.getId(), s.getName(), e.getValue(), fee, e.getValue().add(fee)));
            total = total.add(e.getValue()).add(fee);
        }

        if (lines.isEmpty()) notes.add("No wholesale listings fit this budget right now. Try a larger budget or check back when suppliers list more stock.");
        notes.add("Delivery fees are included for each supplier, as if you choose delivery. Choose self-collection at checkout to pay R0 delivery.");
        notes.add("Potential savings compare the chosen supplier with the most expensive listing of the same product and pack size on PULA.");
        notes.add("This is a proposal only. Nothing is bought until you review it, add it to your stock cart and pay.");

        return new StockPlan(budget, "ZAR", lines, suppliers, total, budget.subtract(total), savings, notes);
    }

    private static BigDecimal newSupplierFee(WholesaleProduct p, Map<String, BigDecimal> supplierSubtotal) {
        if (supplierSubtotal.containsKey(p.getSupplierBusiness().getId())) return BigDecimal.ZERO;
        BigDecimal f = p.getSupplierBusiness().getDeliveryFee();
        return f != null ? f : BigDecimal.ZERO;
    }

    private static void addLine(WholesaleProduct p, int packs, Map<String, Integer> qty,
                                Map<String, BigDecimal> supplierSubtotal, Map<String, Business> supplierById) {
        qty.merge(p.getId(), packs, Integer::sum);
        String sid = p.getSupplierBusiness().getId();
        supplierById.putIfAbsent(sid, p.getSupplierBusiness());
        supplierSubtotal.merge(sid, p.getPricePerPack().multiply(BigDecimal.valueOf(packs)), BigDecimal::add);
    }
}
