package com.momocircle.service;

import com.momocircle.domain.Order;
import com.momocircle.domain.Payment;
import com.momocircle.domain.PaymentRefund;
import com.momocircle.domain.RestaurantSettlement;
import com.momocircle.domain.enums.RefundStatus;
import com.momocircle.domain.enums.SettlementType;
import com.momocircle.domain.ledger.LedgerTransactionEntity;
import com.momocircle.finance.FinancialRecordReconciler;
import com.momocircle.finance.FinancialRecordReconciler.Finding;
import com.momocircle.finance.FinancialRecordReconciler.Sale;
import com.momocircle.finance.RefundMath;
import com.momocircle.ledger.TxStatus;
import com.momocircle.payment.PaymentRails;
import com.momocircle.repository.OrderRepository;
import com.momocircle.repository.PaymentRefundRepository;
import com.momocircle.repository.PaymentRepository;
import com.momocircle.repository.RestaurantSettlementRepository;
import com.momocircle.repository.ledger.LedgerTransactionRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only reconciliation of the records behind each SIMULATED wallet sale (payment, refunds, merchant settlements, ledger
 * transaction). It only assembles facts and hands them to the pure {@link FinancialRecordReconciler}; it never writes, so it is
 * safe to run repeatedly and cannot create, delete or alter any financial record. Operators decide what to do with a finding.
 */
@Service
public class FinancialReconciliationService {

    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final PaymentRefundRepository refunds;
    private final RestaurantSettlementRepository settlements;
    private final LedgerTransactionRepository ledgerTx;

    public FinancialReconciliationService(PaymentRepository payments, OrderRepository orders, PaymentRefundRepository refunds,
                                          RestaurantSettlementRepository settlements, LedgerTransactionRepository ledgerTx) {
        this.payments = payments;
        this.orders = orders;
        this.refunds = refunds;
        this.settlements = settlements;
        this.ledgerTx = ledgerTx;
    }

    @Transactional(readOnly = true)
    public List<Finding> reconcileWalletSales(int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        var page = payments.findByProvider(PaymentRails.WALLET,
                PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Sale> sales = new ArrayList<>();
        for (Payment p : page.getContent()) {
            Order order = orders.findByPaymentId(p.getId()).orElse(null);
            boolean reversed = p.getLedgerTransactionId() != null && ledgerTx.findById(p.getLedgerTransactionId())
                    .map(LedgerTransactionEntity::getStatus).map(s -> s == TxStatus.REVERSED).orElse(false);
            List<FinancialRecordReconciler.Refund> rs = new ArrayList<>();
            for (PaymentRefund r : refunds.findByPaymentIdOrderByCreatedAtAsc(p.getId())) {
                rs.add(new FinancialRecordReconciler.Refund(r.getId(), RefundMath.toMinor(r.getAmount()),
                        r.getStatus() == RefundStatus.REFUNDED, r.getLedgerTransactionId(), reversed));
            }
            List<FinancialRecordReconciler.Settlement> ss = new ArrayList<>();
            for (RestaurantSettlement s : settlements.findByPaymentId(p.getId())) {
                ss.add(new FinancialRecordReconciler.Settlement(s.getId(), s.getType() == SettlementType.SALE,
                        s.getStatus().name(), s.getPayoutProvider(), RefundMath.toMinor(s.getGrossAmount()),
                        RefundMath.toMinor(s.getRefundAmount())));
            }
            sales.add(new Sale(p.getId(), order == null ? null : order.getId(),
                    order == null || order.getBusiness() == null ? null : order.getBusiness().getId(),
                    true, p.getStatus().name(), RefundMath.toMinor(p.getAmount()), p.getLedgerTransactionId(), rs, ss));
        }
        return FinancialRecordReconciler.reconcile(sales);
    }
}
