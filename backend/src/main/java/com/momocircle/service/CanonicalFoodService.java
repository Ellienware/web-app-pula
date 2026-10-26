package com.momocircle.service;

import com.momocircle.domain.CanonicalFoodAlias;
import com.momocircle.domain.CanonicalFoodAttributes;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.dto.FoodDtos.CanonicalFoodResponse;
import com.momocircle.dto.FoodDtos.UpsertCanonicalFoodRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CanonicalFoodAliasRepository;
import com.momocircle.repository.CanonicalFoodAttributesRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PULA Food — maintains the meaning of a canonical food (attributes + aliases). Callers must be
 * admins (enforced in the controller): these attributes decide what PULA calls an "exact match" for
 * every customer, so unlike creating a bare canonical product they are not open to every user.
 */
@Service
public class CanonicalFoodService {

    private static final int MAX_ALIASES = 20;

    private final CanonicalProductService canonicalProductService;
    private final CanonicalFoodAttributesRepository attributesRepository;
    private final CanonicalFoodAliasRepository aliasRepository;

    public CanonicalFoodService(CanonicalProductService canonicalProductService,
                                CanonicalFoodAttributesRepository attributesRepository,
                                CanonicalFoodAliasRepository aliasRepository) {
        this.canonicalProductService = canonicalProductService;
        this.attributesRepository = attributesRepository;
        this.aliasRepository = aliasRepository;
    }

    @Transactional
    public CanonicalFoodResponse upsert(String canonicalId, UpsertCanonicalFoodRequest req) {
        CanonicalProduct c = canonicalProductService.requireById(canonicalId);
        if (!"MEAL".equalsIgnoreCase(c.getCategory())) {
            throw ApiException.badRequest("Only canonical products in the MEAL category can hold food attributes");
        }
        CanonicalFoodAttributes a = attributesRepository.findByCanonicalProductId(c.getId()).orElseGet(() -> {
            CanonicalFoodAttributes created = new CanonicalFoodAttributes();
            created.setCanonicalProduct(c);
            return created;
        });
        a.setFoodType(FoodMatchingService.token(req.foodType()));
        a.setProtein(FoodMatchingService.token(req.protein()));
        a.setStarch(FoodMatchingService.token(req.starch()));
        a.setPortionClass(FoodMatchingService.portionClass(req.portionClass()));
        a.setSidesSignature(FoodMatchingService.signature(req.sides()));
        attributesRepository.save(a);

        Set<String> aliases = new LinkedHashSet<>();
        if (req.aliases() != null) {
            for (String raw : req.aliases()) {
                String n = FoodMatchingService.normalise(raw);
                if (!n.isEmpty()) aliases.add(n);
            }
        }
        if (aliases.size() > MAX_ALIASES) throw ApiException.badRequest("At most " + MAX_ALIASES + " aliases are allowed");
        aliasRepository.deleteByCanonicalProductId(c.getId());
        aliasRepository.flush();
        for (String n : aliases) {
            CanonicalFoodAlias al = new CanonicalFoodAlias();
            al.setCanonicalProduct(c);
            al.setAlias(n);
            aliasRepository.save(al);
        }
        return toResponse(c, a, aliases);
    }

    @Transactional(readOnly = true)
    public CanonicalFoodResponse get(String canonicalId) {
        CanonicalProduct c = canonicalProductService.requireById(canonicalId);
        CanonicalFoodAttributes a = attributesRepository.findByCanonicalProductId(c.getId())
                .orElseThrow(() -> ApiException.notFound("This canonical product has no food attributes"));
        Set<String> aliases = new LinkedHashSet<>();
        List<CanonicalFoodAlias> list = aliasRepository.findByCanonicalProductId(c.getId());
        for (CanonicalFoodAlias al : list) aliases.add(al.getAlias());
        return toResponse(c, a, aliases);
    }

    private static CanonicalFoodResponse toResponse(CanonicalProduct c, CanonicalFoodAttributes a, Set<String> aliases) {
        Set<String> sides = new LinkedHashSet<>();
        if (a.getSidesSignature() != null && !a.getSidesSignature().isBlank()) sides.addAll(List.of(a.getSidesSignature().split(",")));
        return new CanonicalFoodResponse(c.getId(), c.getName(), a.getFoodType(), a.getProtein(), a.getStarch(),
                a.getPortionClass(), sides, aliases);
    }
}
