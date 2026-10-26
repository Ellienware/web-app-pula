package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.FinancialProfile;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.IncomeType;
import com.momocircle.dto.MoneyDtos.FinancialProfileResponse;
import com.momocircle.dto.MoneyDtos.UpdateFinancialProfileRequest;
import com.momocircle.repository.FinancialProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run
 * `mvn test` locally before relying on this.
 */
class FinancialProfileServiceTest {

    private FinancialProfileRepository repository;
    private FinancialProfileService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(FinancialProfileRepository.class);
        CurrencyProperties currencyProperties = new CurrencyProperties();
        currencyProperties.setDefaultCurrency("ZAR");
        currencyProperties.setSupported(List.of("ZAR"));
        service = new FinancialProfileService(repository, currencyProperties);
        user = new User();
        user.setId("user-1");
        when(repository.save(any(FinancialProfile.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void getOrCreateCreatesWithDeploymentDefaultCurrencyOnFirstCall() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());

        FinancialProfileResponse response = service.getOrCreate(user);

        assertEquals("ZAR", response.currency());
        assertEquals(IncomeType.VARIABLE_INCOME, response.incomeType());
    }

    @Test
    void getOrCreateReturnsExistingProfileWithoutCreatingANewOne() {
        FinancialProfile existing = new FinancialProfile();
        existing.setUser(user);
        existing.setCurrency("ZAR");
        existing.setIncomeType(IncomeType.IRREGULAR_INCOME);
        when(repository.findByUserId(user.getId())).thenReturn(Optional.of(existing));

        FinancialProfileResponse response = service.getOrCreate(user);

        assertEquals(IncomeType.IRREGULAR_INCOME, response.incomeType());
    }

    @Test
    void updateChangesIncomeTypeAndCurrency() {
        when(repository.findByUserId(user.getId())).thenReturn(Optional.empty());
        UpdateFinancialProfileRequest req = new UpdateFinancialProfileRequest(IncomeType.IRREGULAR_INCOME, null);

        FinancialProfileResponse response = service.update(user, req);

        assertEquals(IncomeType.IRREGULAR_INCOME, response.incomeType());
        assertEquals("ZAR", response.currency());
    }
}
