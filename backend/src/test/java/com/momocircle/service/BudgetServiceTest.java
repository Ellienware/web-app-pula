package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.Budget;
import com.momocircle.domain.User;
import com.momocircle.dto.MoneyDtos.BudgetCategoryRequest;
import com.momocircle.dto.MoneyDtos.BudgetResponse;
import com.momocircle.dto.MoneyDtos.CreateOrUpdateBudgetRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BudgetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class BudgetServiceTest {

    private BudgetRepository repository;
    private BudgetService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(BudgetRepository.class);
        CurrencyProperties currencyProperties = new CurrencyProperties();
        currencyProperties.setDefaultCurrency("ZAR");
        currencyProperties.setSupported(List.of("ZAR"));
        FinancialValidationService financialValidationService =
                new FinancialValidationService(currencyProperties, new BigDecimal("10000000"));
        service = new BudgetService(repository, currencyProperties, financialValidationService);
        user = new User();
        user.setId("user-1");
        when(repository.save(any(Budget.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void rejectsBudgetWithNoCategories() {
        CreateOrUpdateBudgetRequest req = new CreateOrUpdateBudgetRequest("Monthly budget", null, List.of());
        assertThrows(ApiException.class, () -> service.createOrUpdate(user, req));
    }

    @Test
    void createComputesTotalAllocatedAcrossCategories() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());
        CreateOrUpdateBudgetRequest req = new CreateOrUpdateBudgetRequest("Monthly budget", null, List.of(
                new BudgetCategoryRequest("Rent", new BigDecimal("3500.00")),
                new BudgetCategoryRequest("Groceries", new BigDecimal("1200.00")),
                new BudgetCategoryRequest("Savings", new BigDecimal("500.00"))));

        BudgetResponse response = service.createOrUpdate(user, req);

        assertEquals(3, response.categories().size());
        assertEquals(0, new BigDecimal("5200.00").compareTo(response.totalAllocated()));
        assertEquals("ZAR", response.currency());
    }

    @Test
    void updateReplacesPreviousCategoriesEntirely() {
        Budget existing = new Budget();
        existing.setUser(user);
        existing.setLabel("Old budget");
        existing.setCurrency("ZAR");
        when(repository.findByUserId(user.getId())).thenReturn(Optional.of(existing));

        CreateOrUpdateBudgetRequest req = new CreateOrUpdateBudgetRequest("New budget", null,
                List.of(new BudgetCategoryRequest("Transport", new BigDecimal("400.00"))));

        BudgetResponse response = service.createOrUpdate(user, req);

        assertEquals(1, response.categories().size());
        assertEquals("Transport", response.categories().get(0).name());
        assertEquals("New budget", response.label());
    }

    @Test
    void getThrowsWhenNoBudgetExistsYet() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.get(user));
    }

    @Test
    void allowsAZeroAllocatedCategory() {
        // A category allocated R0 is a legitimate budgeting choice ("not spending on
        // this right now"), unlike a R0 income record or loan which wouldn't make
        // sense — this is exactly why the fix uses requireValidNonNegativeAmount
        // rather than requireValidAmount (which rejects zero).
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());
        CreateOrUpdateBudgetRequest req = new CreateOrUpdateBudgetRequest("Monthly budget", null,
                List.of(new BudgetCategoryRequest("Entertainment", BigDecimal.ZERO)));

        BudgetResponse response = service.createOrUpdate(user, req);

        assertEquals(0, BigDecimal.ZERO.compareTo(response.categories().get(0).allocatedAmount()));
    }

    @Test
    void rejectsANegativeAllocatedAmount() {
        CreateOrUpdateBudgetRequest req = new CreateOrUpdateBudgetRequest("Monthly budget", null,
                List.of(new BudgetCategoryRequest("Rent", new BigDecimal("-100.00"))));
        assertThrows(ApiException.class, () -> service.createOrUpdate(user, req));
    }

    @Test
    void rejectsAnUnsupportedCurrency() {
        CreateOrUpdateBudgetRequest req = new CreateOrUpdateBudgetRequest("Monthly budget", "USD",
                List.of(new BudgetCategoryRequest("Rent", new BigDecimal("100.00"))));
        assertThrows(ApiException.class, () -> service.createOrUpdate(user, req));
    }
}
