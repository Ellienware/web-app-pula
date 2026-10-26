package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.FinancialProfile;
import com.momocircle.domain.User;
import com.momocircle.dto.MoneyDtos.FinancialProfileResponse;
import com.momocircle.dto.MoneyDtos.UpdateFinancialProfileRequest;
import com.momocircle.repository.FinancialProfileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every user implicitly has a FinancialProfile — {@link #getOrCreate} lazily
 * creates one with deployment defaults the first time it's needed, rather
 * than requiring a separate "set up Money Confidence" onboarding step
 * before any of the rest of this domain can be used.
 */
@Service
public class FinancialProfileService {

    private final FinancialProfileRepository financialProfileRepository;
    private final CurrencyProperties currencyProperties;

    public FinancialProfileService(FinancialProfileRepository financialProfileRepository,
                                    CurrencyProperties currencyProperties) {
        this.financialProfileRepository = financialProfileRepository;
        this.currencyProperties = currencyProperties;
    }

    @Transactional
    public FinancialProfileResponse getOrCreate(User user) {
        return toResponse(getOrCreateEntity(user));
    }

    private FinancialProfile getOrCreateEntity(User user) {
        return financialProfileRepository.findByUserId(user.getId())
                .orElseGet(() -> {
                    FinancialProfile profile = new FinancialProfile();
                    profile.setUser(user);
                    profile.setCurrency(currencyProperties.getDefaultCurrency());
                    return financialProfileRepository.save(profile);
                });
    }

    @Transactional
    public FinancialProfileResponse update(User user, UpdateFinancialProfileRequest req) {
        FinancialProfile profile = getOrCreateEntity(user);
        profile.setIncomeType(req.incomeType());
        if (req.currency() != null && !req.currency().isBlank()) {
            profile.setCurrency(req.currency());
        }
        return toResponse(financialProfileRepository.save(profile));
    }

    private FinancialProfileResponse toResponse(FinancialProfile p) {
        return new FinancialProfileResponse(
                p.getId(), p.getUser().getId(), p.getIncomeType(), p.getCurrency(), p.getCreatedAt());
    }
}
