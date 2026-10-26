package com.momocircle.branch;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure merge of per-branch aggregates into a parent-business report. Each sale is counted ONCE, from its order, net of refunds that
 * have COMPLETED; a pending/failed refund never reduces it. These are order totals of simulated or provider payments: not settled cash.
 */
public final class BranchReportMath {

    public record Line(String branchId, String branchName, long orders, long units, BigDecimal gross, BigDecimal refunded, BigDecimal net) { }

    public record Report(List<Line> branches, long orders, long units, BigDecimal gross, BigDecimal refunded, BigDecimal net) { }

    public record Sales(String branchId, long orders, long units, BigDecimal gross) { }

    private BranchReportMath() { }

    /** {@code branchId == null} in sales/refunds means "orders with no branch" (the business's main/central sales). */
    public static Report merge(Map<String, String> branchNames, List<Sales> sales, Map<String, BigDecimal> refundedByBranch) {
        Map<String, Sales> byBranch = new LinkedHashMap<>();
        for (Sales s : sales) byBranch.put(s.branchId(), s);
        // Copy into a null-key-safe map: the "no branch" bucket has a null key and immutable maps (Map.of) throw on get(null).
        Map<String, BigDecimal> refunds = new java.util.HashMap<>();
        refundedByBranch.forEach(refunds::put);
        List<String> keys = new ArrayList<>(branchNames.keySet());
        if (byBranch.containsKey(null)) keys.add(null);

        List<Line> lines = new ArrayList<>();
        long orders = 0, units = 0;
        BigDecimal gross = BigDecimal.ZERO, refunded = BigDecimal.ZERO, net = BigDecimal.ZERO;
        for (String key : keys) {
            Sales s = byBranch.get(key);
            long o = s == null ? 0 : s.orders();
            long u = s == null ? 0 : s.units();
            BigDecimal g = s == null ? BigDecimal.ZERO : s.gross();
            BigDecimal r = refunds.getOrDefault(key, BigDecimal.ZERO);
            BigDecimal n = g.subtract(r).max(BigDecimal.ZERO);
            lines.add(new Line(key, key == null ? "No branch (main)" : branchNames.get(key), o, u, g, r, n));
            orders += o; units += u; gross = gross.add(g); refunded = refunded.add(r); net = net.add(n);
        }
        return new Report(lines, orders, units, gross, refunded, net);
    }
}
