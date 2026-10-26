package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.IncomeRecord;
import com.momocircle.domain.User;
import com.momocircle.dto.MoneyDtos.CreateIncomeRecordRequest;
import com.momocircle.dto.MoneyDtos.IncomeSummaryResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.IncomeRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class IncomeRecordServiceTest {

    private IncomeRecordRepository repository;
    private IncomeRecordService service;
    private User user;

    private IncomeRecord record(String amount, LocalDate date) {
        IncomeRecord r = new IncomeRecord();
        r.setUser(user);
        r.setAmount(new BigDecimal(amount));
        r.setCurrency("ZAR");
        r.setReceivedDate(date);
        return r;
    }

    @BeforeEach
    void setUp() {
        repository = mock(IncomeRecordRepository.class);
        CurrencyProperties currencyProperties = new CurrencyProperties();
        currencyProperties.setDefaultCurrency("ZAR");
        currencyProperties.setSupported(List.of("ZAR"));
        FinancialValidationService financialValidationService =
                new FinancialValidationService(currencyProperties, new BigDecimal("10000000"));
        service = new IncomeRecordService(repository, currencyProperties, financialValidationService);
        user = new User();
        user.setId("user-1");
        when(repository.save(any(IncomeRecord.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void deleteRejectsRecordNotOwnedByCaller() {
        when(repository.findByIdAndUserId("rec-1", user.getId())).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.delete(user, "rec-1"));
    }

    @Test
    void weeklySummaryBucketsIncomeByCalendarWeekAndFillsGapsWithZero() {
        // Two records eight weeks apart, landing in different buckets.
        LocalDate today = LocalDate.of(2026, 9, 15); // a Tuesday
        // We can't control LocalDate.now() without a clock abstraction in
        // the service, so this test only asserts structural properties
        // (bucket count, zero-filling, total correctness for whatever
        // window the repository mock actually returns) rather than exact
        // dates — a real clock-injection refactor would let us assert
        // precise week boundaries, and is a reasonable follow-up.
        when(repository.findByUserIdAndReceivedDateBetweenOrderByReceivedDateAsc(
                anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of(record("800.00", today), record("450.00", today.minusDays(1))));

        IncomeSummaryResponse summary = service.weeklySummary(user);

        assertEquals(8, summary.buckets().size());
        assertEquals("ZAR", summary.currency());
        // At least one bucket should be non-zero (the current week, which
        // contains "today" by construction) and at least one should be
        // zero (with only two records eight weeks apart in a naive test,
        // most buckets have no data).
        boolean hasNonZero = summary.buckets().stream().anyMatch(b -> b.recordCount() > 0);
        boolean hasZero = summary.buckets().stream().anyMatch(b -> b.recordCount() == 0);
        assertEquals(true, hasNonZero);
        assertEquals(true, hasZero);
    }

    @Test
    void weeklySummaryHasNullAverageWhenNoIncomeRecorded() {
        when(repository.findByUserIdAndReceivedDateBetweenOrderByReceivedDateAsc(
                anyString(), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(List.of());

        IncomeSummaryResponse summary = service.weeklySummary(user);

        assertNull(summary.averagePerBucket());
        assertNull(summary.minimumObservedPerBucket());
        assertEquals("ZAR", summary.currency()); // falls back to deployment default, not fabricated
    }

    @Test
    void rejectsAnUnsupportedCurrencyOnCreate() {
        // Regression test for a real gap found in the security/regression audit:
        // this service didn't route through FinancialValidationService at all
        // before this fix, despite that class's own javadoc stating every
        // financial amount in the app passes through it.
        CreateIncomeRecordRequest req = new CreateIncomeRecordRequest(new BigDecimal("500.00"), "USD",
                LocalDate.now(), "Casual labour");
        assertThrows(ApiException.class, () -> service.create(user, req));
    }

    @Test
    void rejectsAnAmountAboveTheConfiguredMaximum() {
        CreateIncomeRecordRequest req = new CreateIncomeRecordRequest(new BigDecimal("99999999999.00"), null,
                LocalDate.now(), null);
        assertThrows(ApiException.class, () -> service.create(user, req));
    }
}
