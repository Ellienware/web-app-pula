package com.momocircle.service;

import com.momocircle.branch.BranchPermission;
import com.momocircle.branch.BranchReportMath;
import com.momocircle.branch.BranchReportMath.Sales;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.OrderStatus;
import com.momocircle.dto.BranchOpsDtos.BranchReportView;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRefundRepository;
import com.momocircle.service.BranchManagementService.Access;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parent-business sales report by branch. The owner sees every branch plus the "no branch" bucket and the consolidated total; a manager
 * or staff member holding VIEW_REPORTS sees only their own branches and no consolidated or "no branch" figures. Each sale is counted once, from
 * its order, net of COMPLETED refunds (same rule as the seller dashboard). Read-only; it never changes any record.
 */
@Service
public class BranchReportService {

    private static final Set<OrderStatus> REVENUE = EnumSet.of(OrderStatus.PAID, OrderStatus.PROCESSING, OrderStatus.READY,
            OrderStatus.COMPLETED, OrderStatus.REFUND_PENDING);

    public static final String NOTICE = "Order totals net of completed refunds. These are order and payment amounts, not settled cash: "
            + "settlement is tracked separately and demo-wallet payments are simulated.";

    private final BranchManagementService branches;
    private final OrderRepository orders;
    private final PaymentRefundRepository refunds;

    public BranchReportService(BranchManagementService branches, OrderRepository orders, PaymentRefundRepository refunds) {
        this.branches = branches;
        this.orders = orders;
        this.refunds = refunds;
    }

    @Transactional(readOnly = true)
    public BranchReportView report(User caller, String businessId, Instant from, Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(30, ChronoUnit.DAYS);
        if (!start.isBefore(end)) throw ApiException.badRequest("The report start must be before its end");
        if (ChronoUnit.DAYS.between(start, end) > 400) throw ApiException.badRequest("Reports cover at most about 13 months");

        boolean owner = branches.isOwnerOf(caller, businessId);
        List<Access> visible = branches.accessibleBranches(caller, businessId, BranchPermission.VIEW_REPORTS);
        if (visible.isEmpty() && !owner) throw ApiException.forbidden("You do not have permission to see reports");

        Map<String, String> names = new LinkedHashMap<>();
        for (Access a : visible) names.put(a.branch().getId(), a.branch().getName());

        Map<String, Long> units = new HashMap<>();
        for (Object[] r : orders.unitsByBranch(businessId, REVENUE, start, end)) units.put((String) r[0], ((Number) r[1]).longValue());
        List<Sales> sales = new ArrayList<>();
        for (Object[] r : orders.salesByBranch(businessId, REVENUE, start, end)) {
            String id = (String) r[0];
            if (id == null ? !owner : !names.containsKey(id)) continue; // others' branches and (for non-owners) the main bucket stay hidden
            sales.add(new Sales(id, ((Number) r[1]).longValue(), units.getOrDefault(id, 0L), toDecimal(r[2])));
        }
        Map<String, BigDecimal> refunded = new HashMap<>();
        for (Object[] r : refunds.refundedByBranch(businessId, REVENUE, start, end)) {
            String id = (String) r[0];
            if (id == null ? !owner : !names.containsKey(id)) continue;
            refunded.put(id, toDecimal(r[1]));
        }
        return new BranchReportView(start, end, BranchReportMath.merge(names, sales, refunded), NOTICE);
    }

    private static BigDecimal toDecimal(Object o) {
        return o instanceof BigDecimal d ? d : new BigDecimal(String.valueOf(o));
    }
}
