package com.momocircle.branch;

import com.momocircle.branch.BranchReportMath.Report;
import com.momocircle.branch.BranchReportMath.Sales;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BranchReportMathTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    @Test
    void consolidatedTotalsEqualTheSumOfBranchesAndCountEachSaleOnce() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("a", "Branch A");
        names.put("b", "Branch B");
        Report r = BranchReportMath.merge(names,
                List.of(new Sales("a", 3, 7, d("300.00")), new Sales("b", 1, 2, d("50.00")), new Sales(null, 2, 4, d("120.00"))),
                Map.of("a", d("100.00")));
        assertEquals(3, r.branches().size());
        assertEquals(6, r.orders());
        assertEquals(13, r.units());
        assertEquals(d("470.00"), r.gross());
        assertEquals(d("100.00"), r.refunded());
        assertEquals(d("370.00"), r.net());
        assertEquals(d("200.00"), r.branches().get(0).net());
        assertEquals("No branch (main)", r.branches().get(2).branchName());
    }

    @Test
    void branchesWithNoSalesAppearWithZeroAndRefundsNeverMakeNetNegative() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("a", "Branch A");
        names.put("b", "Branch B");
        Report r = BranchReportMath.merge(names, List.of(new Sales("a", 1, 1, d("10.00"))), Map.of("a", d("25.00")));
        assertEquals(2, r.branches().size());
        assertEquals(0, r.branches().get(1).orders());
        assertEquals(d("0"), r.branches().get(0).net());
    }
}
