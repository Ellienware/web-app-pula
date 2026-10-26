package com.momocircle.service;

import com.momocircle.domain.ServiceListing;
import com.momocircle.domain.ServiceProviderProfile;
import com.momocircle.domain.enums.ServiceListingStatus;
import com.momocircle.domain.enums.ServicePricingModel;
import com.momocircle.dto.ServiceDtos.ListingView;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ServiceListingRepository;
import com.momocircle.repository.ServiceProviderProfileRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Customer discovery over service listings. Only active, unhidden listings of non-suspended, accepting providers. */
@Service
public class ServiceDiscoveryService {

    private static final int MAX_COMPARE = 4;

    private final ServiceListingRepository listings;
    private final ServiceProviderProfileRepository profiles;

    public ServiceDiscoveryService(ServiceListingRepository listings, ServiceProviderProfileRepository profiles) {
        this.listings = listings;
        this.profiles = profiles;
    }

    @Transactional(readOnly = true)
    public Page<ListingView> search(String categoryId, String q, ServicePricingModel pricingModel, BigDecimal maxPrice,
                                    BigDecimal minRating, boolean verifiedOnly, String area, Pageable pageable) {
        String term = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
        String a = area == null || area.isBlank() ? null : area.trim().toLowerCase(Locale.ROOT);
        String cat = categoryId == null || categoryId.isBlank() ? null : categoryId;
        return listings.search(cat, term, pricingModel, maxPrice, minRating, verifiedOnly, a, pageable)
                .map(l -> ServiceViews.listing(l, profiles.findByBusinessId(l.getBusiness().getId()).orElse(null)));
    }

    @Transactional(readOnly = true)
    public ListingView listing(String id) {
        ServiceListing l = visible(id);
        return ServiceViews.listing(l, profiles.findByBusinessId(l.getBusiness().getId()).orElse(null));
    }

    /** Side-by-side comparison of up to four listings. Invisible or unknown ids are simply left out. */
    @Transactional(readOnly = true)
    public List<ListingView> compare(List<String> ids) {
        if (ids == null || ids.isEmpty()) throw ApiException.badRequest("Pick listings to compare");
        if (ids.size() > MAX_COMPARE) throw ApiException.badRequest("You can compare up to " + MAX_COMPARE + " services");
        List<ListingView> out = new ArrayList<>();
        for (String id : ids) {
            try {
                out.add(listing(id));
            } catch (ApiException ignored) {
                // not visible: skip rather than leak existence
            }
        }
        return out;
    }

    /** Visibility rule shared with requests: active, not hidden, provider not suspended, business active. */
    ServiceListing visible(String id) {
        ServiceListing l = listings.findById(id).orElseThrow(() -> ApiException.notFound("Service not found"));
        ServiceProviderProfile p = profiles.findByBusinessId(l.getBusiness().getId()).orElse(null);
        if (l.getStatus() != ServiceListingStatus.ACTIVE || l.isHiddenByAdmin() || p == null || p.isSuspended()
                || !l.getBusiness().isActive()) {
            throw ApiException.notFound("Service not found");
        }
        return l;
    }
}
