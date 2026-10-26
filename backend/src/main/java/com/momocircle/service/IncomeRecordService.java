package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.IncomeRecord;
import com.momocircle.domain.User;
import com.momocircle.dto.MoneyDtos.CreateIncomeRecordRequest;
import com.momocircle.dto.MoneyDtos.IncomeBucket;
import com.momocircle.dto.MoneyDtos.IncomeRecordResponse;
import com.momocircle.dto.MoneyDtos.IncomeSummaryResponse;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.IncomeRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Manages individual income entries and implements the irregular-income
 * planner's weekly view (brief §16): buckets actual entries by calendar
 * week rather than requiring the user to pre-assign a "Week 1/2/3/4" label
 * that assumes a monthly cycle aligned to the calendar, which real
 * irregular income often isn't.
 */
@Service
public class IncomeRecordService {

    private static final int SUMMARY_WEEKS = 8;

    private final IncomeRecordRepository incomeRecordRepository;
    private final CurrencyProperties currencyProperties;
    private final FinancialValidationService financialValidationService;

    public IncomeRecordService(IncomeRecordRepository incomeRecordRepository,
                                CurrencyProperties currencyProperties,
                                FinancialValidationService financialValidationService) {
        this.incomeRecordRepository = incomeRecordRepository;
        this.currencyProperties = currencyProperties;
        this.financialValidationService = financialValidationService;
    }

    @Transactional
    public IncomeRecordResponse create(User user, CreateIncomeRecordRequest req) {
        String currency = req.currency() == null || req.currency().isBlank()
                ? currencyProperties.getDefaultCurrency() : req.currency();
        financialValidationService.requireValidAmount(req.amount(), "amount");
        financialValidationService.requireValidCurrency(currency);

        IncomeRecord record = new IncomeRecord();
        record.setUser(user);
        record.setAmount(req.amount());
        record.setCurrency(currency);
        record.setReceivedDate(req.receivedDate());
        record.setSource(req.source());
        return toResponse(incomeRecordRepository.save(record));
    }

    @Transactional
    public void delete(User user, String recordId) {
        IncomeRecord record = incomeRecordRepository.findByIdAndUserId(recordId, user.getId())
                .orElseThrow(() -> ApiException.notFound("Income record not found"));
        incomeRecordRepository.delete(record);
    }

    @Transactional(readOnly = true)
    public List<IncomeRecordResponse> list(User user) {
        return incomeRecordRepository.findByUserIdOrderByReceivedDateDesc(user.getId())
                .stream().map(this::toResponse).toList();
    }

    /**
     * The last {@value #SUMMARY_WEEKS} calendar weeks (Monday-Sunday),
     * each summed from whatever records actually fall in it - weeks with no
     * recorded income show a zero total rather than being skipped, so gaps
     * in the pattern are visible rather than hidden.
     */
    @Transactional(readOnly = true)
    public IncomeSummaryResponse weeklySummary(User user) {
        LocalDate today = LocalDate.now();
        LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate earliestWeekStart = currentWeekStart.minusWeeks(SUMMARY_WEEKS - 1L);
        LocalDate rangeEnd = currentWeekStart.plusDays(6);

        List<IncomeRecord> records = incomeRecordRepository
                .findByUserIdAndReceivedDateBetweenOrderByReceivedDateAsc(user.getId(), earliestWeekStart, rangeEnd);

        List<IncomeBucket> buckets = new ArrayList<>();
        String currency = null;
        for (int i = 0; i < SUMMARY_WEEKS; i++) {
            LocalDate weekStart = earliestWeekStart.plusWeeks(i);
            LocalDate weekEnd = weekStart.plusDays(6);
            List<IncomeRecord> inWeek = records.stream()
                    .filter(r -> !r.getReceivedDate().isBefore(weekStart) && !r.getReceivedDate().isAfter(weekEnd))
                    .toList();
            BigDecimal total = inWeek.stream().map(IncomeRecord::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            buckets.add(new IncomeBucket(weekStart, weekEnd, total, inWeek.size(), false));
            if (currency == null && !inWeek.isEmpty()) {
                currency = inWeek.get(0).getCurrency();
            }
        }
        if (currency == null) {
            currency = currencyProperties.getDefaultCurrency();
        }

        List<BigDecimal> nonEmptyTotals = buckets.stream()
                .filter(b -> b.recordCount() > 0)
                .map(IncomeBucket::total)
                .collect(Collectors.toList());

        BigDecimal average = nonEmptyTotals.isEmpty() ? null
                : nonEmptyTotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(nonEmptyTotals.size()), 2, RoundingMode.HALF_UP);
        BigDecimal minimum = nonEmptyTotals.isEmpty() ? null
                : nonEmptyTotals.stream().min(BigDecimal::compareTo).orElse(null);

        return new IncomeSummaryResponse(currency, buckets, average, minimum);
    }

    private IncomeRecordResponse toResponse(IncomeRecord r) {
        return new IncomeRecordResponse(r.getId(), r.getAmount(), r.getCurrency(), r.getReceivedDate(),
                r.getSource(), r.getCreatedAt());
    }
}
