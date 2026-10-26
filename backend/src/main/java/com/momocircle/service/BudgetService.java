package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.Budget;
import com.momocircle.domain.BudgetCategory;
import com.momocircle.domain.User;
import com.momocircle.dto.MoneyDtos.BudgetCategoryRequest;
import com.momocircle.dto.MoneyDtos.BudgetCategoryResponse;
import com.momocircle.dto.MoneyDtos.BudgetResponse;
import com.momocircle.dto.MoneyDtos.CreateOrUpdateBudgetRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BudgetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * One budget per user (see Budget's own javadoc for why). {@link #createOrUpdate}
 * replaces the whole category list on every call — simpler and less
 * error-prone than diffing category lists for this slice's scale (a
 * personal budget realistically has a handful of categories), at the cost
 * of category ids changing on every edit. Worth revisiting if/when the
 * mobile or web UI needs to reference a specific category id across edits
 * (e.g. for spend-tracking against a category) rather than just redraw the
 * whole list.
 */
@Service
public class BudgetService {

    private final BudgetRepository budgetRepository;
    private final CurrencyProperties currencyProperties;
    private final FinancialValidationService financialValidationService;

    public BudgetService(BudgetRepository budgetRepository, CurrencyProperties currencyProperties,
                          FinancialValidationService financialValidationService) {
        this.budgetRepository = budgetRepository;
        this.currencyProperties = currencyProperties;
        this.financialValidationService = financialValidationService;
    }

    @Transactional
    public BudgetResponse createOrUpdate(User user, CreateOrUpdateBudgetRequest req) {
        if (req.categories() == null || req.categories().isEmpty()) {
            throw ApiException.badRequest("A budget needs at least one category");
        }
        String currency = req.currency() == null || req.currency().isBlank()
                ? currencyProperties.getDefaultCurrency() : req.currency();
        financialValidationService.requireValidCurrency(currency);
        for (BudgetCategoryRequest catReq : req.categories()) {
            financialValidationService.requireValidNonNegativeAmount(catReq.allocatedAmount(), "allocatedAmount");
        }

        Budget budget = budgetRepository.findByUserId(user.getId()).orElseGet(Budget::new);
        budget.setUser(user);
        budget.setLabel(req.label().trim());
        budget.setCurrency(currency);

        budget.getCategories().clear();
        List<BudgetCategory> categories = new ArrayList<>();
        for (BudgetCategoryRequest catReq : req.categories()) {
            BudgetCategory category = new BudgetCategory();
            category.setBudget(budget);
            category.setName(catReq.name().trim());
            category.setAllocatedAmount(catReq.allocatedAmount());
            categories.add(category);
        }
        budget.getCategories().addAll(categories);

        return toResponse(budgetRepository.save(budget));
    }

    @Transactional(readOnly = true)
    public BudgetResponse get(User user) {
        Budget budget = budgetRepository.findByUserId(user.getId())
                .orElseThrow(() -> ApiException.notFound("No budget set up yet"));
        return toResponse(budget);
    }

    private BudgetResponse toResponse(Budget b) {
        List<BudgetCategoryResponse> categories = b.getCategories().stream()
                .map(c -> new BudgetCategoryResponse(c.getId(), c.getName(), c.getAllocatedAmount()))
                .toList();
        BigDecimal total = b.getCategories().stream()
                .map(BudgetCategory::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BudgetResponse(b.getId(), b.getLabel(), b.getCurrency(), categories, total, b.getCreatedAt());
    }
}
