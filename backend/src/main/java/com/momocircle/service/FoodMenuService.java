package com.momocircle.service;

import com.momocircle.domain.BranchItemAvailability;
import com.momocircle.config.FoodProperties;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.MenuCategory;
import com.momocircle.domain.Modifier;
import com.momocircle.domain.ModifierGroup;
import com.momocircle.domain.Product;
import com.momocircle.domain.ProductFoodDetails;
import com.momocircle.domain.ProductModifierGroup;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.dto.FoodDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BranchItemAvailabilityRepository;
import com.momocircle.repository.BusinessBranchRepository;
import com.momocircle.repository.MenuCategoryRepository;
import com.momocircle.repository.ModifierGroupRepository;
import com.momocircle.repository.ModifierRepository;
import com.momocircle.repository.ProductFoodDetailsRepository;
import com.momocircle.repository.ProductModifierGroupRepository;
import com.momocircle.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PULA Food — merchant-side menu management: categories, optional food details, branches,
 * per-branch availability and modifiers. Menu items are ordinary Products (see
 * ProductService for create/update/price/status); this service only adds the food layer.
 *
 * Every method resolves the caller's own Business first and then checks that each referenced
 * id (product, branch, category, group, modifier) belongs to THAT business. A resource owned
 * by someone else is reported as "not found", never "forbidden", so ids can't be probed.
 * Ordering, pricing and availability enforcement at checkout are not part of this service.
 */
@Service
public class FoodMenuService {

    private static final int MAX_TOKEN = 64;

    private final BusinessService businessService;
    private final FinancialValidationService validation;
    private final ProductRepository productRepository;
    private final MenuCategoryRepository categoryRepository;
    private final ProductFoodDetailsRepository detailsRepository;
    private final BusinessBranchRepository branchRepository;
    private final BranchItemAvailabilityRepository availabilityRepository;
    private final ModifierGroupRepository groupRepository;
    private final ModifierRepository modifierRepository;
    private final ProductModifierGroupRepository productGroupRepository;
    private final FoodProperties properties;

    public FoodMenuService(BusinessService businessService, FinancialValidationService validation,
                           ProductRepository productRepository, MenuCategoryRepository categoryRepository,
                           ProductFoodDetailsRepository detailsRepository,
                           BusinessBranchRepository branchRepository,
                           BranchItemAvailabilityRepository availabilityRepository,
                           ModifierGroupRepository groupRepository, ModifierRepository modifierRepository,
                           ProductModifierGroupRepository productGroupRepository,
                           FoodProperties properties) {
        this.businessService = businessService;
        this.validation = validation;
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.detailsRepository = detailsRepository;
        this.branchRepository = branchRepository;
        this.availabilityRepository = availabilityRepository;
        this.groupRepository = groupRepository;
        this.modifierRepository = modifierRepository;
        this.productGroupRepository = productGroupRepository;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public FoodSettingsResponse getSettings(User owner) {
        return toResponse(requireFoodBusiness(owner));
    }

    @Transactional
    public FoodSettingsResponse updateSettings(User owner, UpdateFoodSettingsRequest req) {
        Business b = requireFoodBusiness(owner);
        if (req.temporarilyClosed() != null) b.setTemporarilyClosed(req.temporarilyClosed());
        if (req.acceptTimeoutMinutes() != null) {
            if (req.acceptTimeoutMinutes() > properties.getMaxAcceptTimeoutMinutes()) {
                throw ApiException.badRequest("The longest accept window is " + properties.getMaxAcceptTimeoutMinutes() + " minutes");
            }
            b.setAcceptTimeoutMinutes(req.acceptTimeoutMinutes());
        }
        return toResponse(b);
    }

    private FoodSettingsResponse toResponse(Business b) {
        Integer own = b.getAcceptTimeoutMinutes();
        int effective = own == null ? properties.getAcceptTimeoutMinutes()
                : Math.min(own, properties.getMaxAcceptTimeoutMinutes());
        return new FoodSettingsResponse(b.isTemporarilyClosed(), own, effective, properties.getMaxAcceptTimeoutMinutes());
    }

    // ------------------------------------------------------------------ categories

    @Transactional(readOnly = true)
    public List<MenuCategoryResponse> listCategories(User owner) {
        Business b = requireFoodBusiness(owner);
        return categoryRepository.findByBusinessIdOrderBySortOrderAscNameAsc(b.getId()).stream()
                .map(this::toResponse).toList();
    }

    @Transactional
    public MenuCategoryResponse createCategory(User owner, CreateMenuCategoryRequest req) {
        Business b = requireFoodBusiness(owner);
        String name = req.name().trim();
        if (name.isEmpty()) throw ApiException.badRequest("Category name is required");
        if (categoryRepository.existsByBusinessIdAndNameIgnoreCase(b.getId(), name)) {
            throw ApiException.conflict("You already have a category with that name");
        }
        MenuCategory c = new MenuCategory();
        c.setBusiness(b);
        c.setName(name);
        c.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
        return toResponse(categoryRepository.save(c));
    }

    @Transactional
    public MenuCategoryResponse updateCategory(User owner, String categoryId, UpdateMenuCategoryRequest req) {
        Business b = requireFoodBusiness(owner);
        MenuCategory c = requireCategory(b, categoryId);
        if (req.name() != null) {
            String name = req.name().trim();
            if (name.isEmpty()) throw ApiException.badRequest("Category name cannot be blank");
            if (!name.equalsIgnoreCase(c.getName())
                    && categoryRepository.existsByBusinessIdAndNameIgnoreCase(b.getId(), name)) {
                throw ApiException.conflict("You already have a category with that name");
            }
            c.setName(name);
        }
        if (req.sortOrder() != null) c.setSortOrder(req.sortOrder());
        return toResponse(categoryRepository.save(c));
    }

    @Transactional
    public void deleteCategory(User owner, String categoryId) {
        Business b = requireFoodBusiness(owner);
        MenuCategory c = requireCategory(b, categoryId);
        if (productRepository.countByMenuCategoryId(c.getId()) > 0) {
            throw ApiException.conflict("Move or archive the items in this category first");
        }
        categoryRepository.delete(c);
    }

    /** Sets (or clears, with a null categoryId) the category of one of the caller's menu items. */
    @Transactional
    public void assignCategory(User owner, String productId, String categoryId) {
        Business b = requireFoodBusiness(owner);
        Product p = requireOwnedProduct(b, productId);
        p.setMenuCategory(categoryId == null ? null : requireCategory(b, categoryId));
        productRepository.save(p);
    }

    // ------------------------------------------------------------------ food details

    @Transactional
    public FoodDetailsResponse upsertFoodDetails(User owner, String productId, UpsertFoodDetailsRequest req) {
        Business b = requireFoodBusiness(owner);
        Product p = requireOwnedProduct(b, productId);
        ProductFoodDetails d = detailsRepository.findByProductId(p.getId()).orElseGet(() -> {
            ProductFoodDetails created = new ProductFoodDetails();
            created.setProduct(p);
            return created;
        });
        d.setFoodType(token(req.foodType()));
        d.setProtein(token(req.protein()));
        d.setStarch(token(req.starch()));
        d.setPortionSize(token(req.portionSize()));
        d.setServingSize(blankToNull(req.servingSize()));
        d.setSpiceLevel(token(req.spiceLevel()));
        d.setPortionDescription(blankToNull(req.portionDescription()));
        d.setPrepTimeMinutes(req.prepTimeMinutes());
        d.setDailyQuantity(req.dailyQuantity());
        d.setSides(tokens(req.sides()));
        d.setDietaryTags(tokens(req.dietaryTags()));
        d.setAllergens(tokens(req.allergens()));
        return toResponse(detailsRepository.save(d));
    }

    @Transactional(readOnly = true)
    public FoodDetailsResponse getFoodDetails(User owner, String productId) {
        Business b = requireFoodBusiness(owner);
        Product p = requireOwnedProduct(b, productId);
        return detailsRepository.findByProductId(p.getId()).map(this::toResponse)
                .orElseThrow(() -> ApiException.notFound("This item has no food details yet"));
    }

    // ------------------------------------------------------------------ branches

    @Transactional(readOnly = true)
    public List<BranchResponse> listBranches(User owner) {
        Business b = requireFoodBusiness(owner);
        return branchRepository.findByBusinessIdOrderByCreatedAtAsc(b.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public BranchResponse createBranch(User owner, CreateBranchRequest req) {
        Business b = requireFoodBusiness(owner);
        BusinessBranch br = new BusinessBranch();
        br.setBusiness(b);
        br.setName(req.name().trim());
        if (br.getName().isEmpty()) throw ApiException.badRequest("Branch name is required");
        br.setAddress(blankToNull(req.address()));
        br.setArea(blankToNull(req.area()));
        applyCoordinates(br, req.latitude(), req.longitude());
        br.setPrepTimeMinutes(req.prepTimeMinutes());
        applyMoney(br, req.deliveryFee(), req.minimumOrderAmount());
        if (req.operatingHours() != null) br.setOperatingHours(new ArrayList<>(req.operatingHours()));
        if (req.fulfilmentOptions() != null) br.setFulfilmentOptions(new LinkedHashSet<>(req.fulfilmentOptions()));
        if (req.deliveryAreas() != null) br.setDeliveryAreas(areas(req.deliveryAreas()));
        requireRideFeeFloor(br);
        return toResponse(branchRepository.save(br));
    }

    @Transactional
    public BranchResponse updateBranch(User owner, String branchId, UpdateBranchRequest req) {
        Business b = requireFoodBusiness(owner);
        BusinessBranch br = requireBranch(b, branchId);
        if (req.name() != null) {
            if (req.name().isBlank()) throw ApiException.badRequest("Branch name cannot be blank");
            br.setName(req.name().trim());
        }
        if (req.address() != null) br.setAddress(blankToNull(req.address()));
        if (req.area() != null) br.setArea(blankToNull(req.area()));
        if (req.latitude() != null || req.longitude() != null) {
            applyCoordinates(br, req.latitude(), req.longitude());
        }
        if (req.prepTimeMinutes() != null) br.setPrepTimeMinutes(req.prepTimeMinutes());
        applyMoney(br, req.deliveryFee(), req.minimumOrderAmount());
        if (req.operatingHours() != null) br.setOperatingHours(new ArrayList<>(req.operatingHours()));
        if (req.fulfilmentOptions() != null) br.setFulfilmentOptions(new LinkedHashSet<>(req.fulfilmentOptions()));
        if (req.deliveryAreas() != null) br.setDeliveryAreas(areas(req.deliveryAreas()));
        if (req.active() != null) {
            // The legacy flag and the general branch status must never disagree. A closed branch stays closed.
            if (br.getStatus() == com.momocircle.branch.BranchStatus.CLOSED) {
                throw ApiException.conflict("A closed branch cannot be reactivated");
            }
            br.applyStatus(req.active() ? com.momocircle.branch.BranchStatus.ACTIVE : com.momocircle.branch.BranchStatus.SUSPENDED);
        }
        if (req.temporarilyClosed() != null) br.setTemporarilyClosed(req.temporarilyClosed());
        requireRideFeeFloor(br);
        return toResponse(branchRepository.save(br));
    }

    // ------------------------------------------------------------------ branch item availability

    @Transactional
    public BranchItemResponse setBranchItem(User owner, String branchId, String productId, SetBranchItemRequest req) {
        Business b = requireFoodBusiness(owner);
        BusinessBranch br = requireBranch(b, branchId);
        Product p = requireOwnedProduct(b, productId);
        if (req.priceOverride() != null) validation.requireValidAmount(req.priceOverride(), "priceOverride");
        BranchItemAvailability a = availabilityRepository.findByBranchIdAndProductId(br.getId(), p.getId())
                .orElseGet(() -> {
                    BranchItemAvailability created = new BranchItemAvailability();
                    created.setBranch(br);
                    created.setProduct(p);
                    return created;
                });
        a.setAvailable(req.available());
        a.setPriceOverride(req.priceOverride());
        a = availabilityRepository.save(a);
        return new BranchItemResponse(br.getId(), p.getId(), a.isAvailable(), a.getPriceOverride());
    }

    @Transactional(readOnly = true)
    public List<BranchItemResponse> listBranchItems(User owner, String branchId) {
        Business b = requireFoodBusiness(owner);
        BusinessBranch br = requireBranch(b, branchId);
        return availabilityRepository.findByBranchId(br.getId()).stream()
                .map(a -> new BranchItemResponse(br.getId(), a.getProduct().getId(), a.isAvailable(), a.getPriceOverride()))
                .toList();
    }

    // ------------------------------------------------------------------ modifiers

    @Transactional(readOnly = true)
    public List<ModifierGroupResponse> listModifierGroups(User owner) {
        Business b = requireFoodBusiness(owner);
        return groupRepository.findByBusinessIdOrderByNameAsc(b.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public ModifierGroupResponse createModifierGroup(User owner, CreateModifierGroupRequest req) {
        Business b = requireFoodBusiness(owner);
        int min = req.minSelect() == null ? 0 : req.minSelect();
        int max = req.maxSelect() == null ? 1 : req.maxSelect();
        requireBounds(min, max);
        ModifierGroup g = new ModifierGroup();
        g.setBusiness(b);
        g.setName(req.name().trim());
        if (g.getName().isEmpty()) throw ApiException.badRequest("Group name is required");
        g.setMinSelect(min);
        g.setMaxSelect(max);
        return toResponse(groupRepository.save(g));
    }

    @Transactional
    public ModifierGroupResponse updateModifierGroup(User owner, String groupId, UpdateModifierGroupRequest req) {
        Business b = requireFoodBusiness(owner);
        ModifierGroup g = requireGroup(b, groupId);
        int min = req.minSelect() == null ? g.getMinSelect() : req.minSelect();
        int max = req.maxSelect() == null ? g.getMaxSelect() : req.maxSelect();
        requireBounds(min, max);
        if (req.name() != null) {
            if (req.name().isBlank()) throw ApiException.badRequest("Group name cannot be blank");
            g.setName(req.name().trim());
        }
        g.setMinSelect(min);
        g.setMaxSelect(max);
        return toResponse(groupRepository.save(g));
    }

    @Transactional
    public ModifierResponse addModifier(User owner, String groupId, CreateModifierRequest req) {
        Business b = requireFoodBusiness(owner);
        ModifierGroup g = requireGroup(b, groupId);
        BigDecimal delta = req.priceDelta() == null ? BigDecimal.ZERO : req.priceDelta();
        validation.requireValidNonNegativeAmount(delta, "priceDelta");
        Modifier m = new Modifier();
        m.setGroup(g);
        m.setName(req.name().trim());
        if (m.getName().isEmpty()) throw ApiException.badRequest("Modifier name is required");
        m.setPriceDelta(delta);
        return toResponse(modifierRepository.save(m));
    }

    @Transactional
    public ModifierResponse updateModifier(User owner, String modifierId, UpdateModifierRequest req) {
        Business b = requireFoodBusiness(owner);
        Modifier m = modifierRepository.findById(modifierId)
                .filter(x -> x.getGroup().getBusiness().getId().equals(b.getId()))
                .orElseThrow(() -> ApiException.notFound("Modifier not found"));
        if (req.name() != null) {
            if (req.name().isBlank()) throw ApiException.badRequest("Modifier name cannot be blank");
            m.setName(req.name().trim());
        }
        if (req.priceDelta() != null) {
            validation.requireValidNonNegativeAmount(req.priceDelta(), "priceDelta");
            m.setPriceDelta(req.priceDelta());
        }
        if (req.available() != null) m.setAvailable(req.available());
        return toResponse(modifierRepository.save(m));
    }

    @Transactional
    public void attachModifierGroup(User owner, String productId, String groupId) {
        Business b = requireFoodBusiness(owner);
        Product p = requireOwnedProduct(b, productId);
        ModifierGroup g = requireGroup(b, groupId);
        if (productGroupRepository.findByProductIdAndGroupId(p.getId(), g.getId()).isPresent()) return; // idempotent
        ProductModifierGroup link = new ProductModifierGroup();
        link.setProduct(p);
        link.setGroup(g);
        productGroupRepository.save(link);
    }

    @Transactional
    public void detachModifierGroup(User owner, String productId, String groupId) {
        Business b = requireFoodBusiness(owner);
        Product p = requireOwnedProduct(b, productId);
        requireGroup(b, groupId);
        productGroupRepository.findByProductIdAndGroupId(p.getId(), groupId).ifPresent(productGroupRepository::delete);
    }

    @Transactional(readOnly = true)
    public List<ModifierGroupResponse> listItemModifierGroups(User owner, String productId) {
        Business b = requireFoodBusiness(owner);
        Product p = requireOwnedProduct(b, productId);
        return productGroupRepository.findByProductId(p.getId()).stream()
                .map(l -> toResponse(l.getGroup())).toList();
    }

    // ------------------------------------------------------------------ guards

    /** The caller's own business, which must be a food type before any menu feature is usable. */
    private Business requireFoodBusiness(User owner) {
        Business b = businessService.requireOwn(owner);
        if (b.getBusinessType() == null || !b.getBusinessType().isFood()) {
            throw ApiException.badRequest("Set your business type to a food business (for example Restaurant "
                    + "or Home Kitchen) before managing a menu");
        }
        return b;
    }

    private Product requireOwnedProduct(Business b, String productId) {
        return productRepository.findById(productId)
                .filter(p -> p.getBusiness().getId().equals(b.getId()))
                .orElseThrow(() -> ApiException.notFound("Product not found"));
    }

    private MenuCategory requireCategory(Business b, String id) {
        return categoryRepository.findById(id)
                .filter(c -> c.getBusiness().getId().equals(b.getId()))
                .orElseThrow(() -> ApiException.notFound("Category not found"));
    }

    private BusinessBranch requireBranch(Business b, String id) {
        return branchRepository.findById(id)
                .filter(x -> x.getBusiness().getId().equals(b.getId()))
                .orElseThrow(() -> ApiException.notFound("Branch not found"));
    }

    private ModifierGroup requireGroup(Business b, String id) {
        return groupRepository.findById(id)
                .filter(g -> g.getBusiness().getId().equals(b.getId()))
                .orElseThrow(() -> ApiException.notFound("Modifier group not found"));
    }

    // ------------------------------------------------------------------ validation helpers

    private static void requireBounds(int min, int max) {
        if (min < 0 || max < 1 || max < min) {
            throw ApiException.badRequest("Choose between a minimum of 0 or more and a maximum of at least 1, "
                    + "with the maximum not below the minimum");
        }
    }

    /** PULA Ride pays the driver 80% of this fee, so a branch offering it must meet the platform minimum. */
    private void requireRideFeeFloor(BusinessBranch br) {
        BigDecimal floor = properties.getMinPulaRideDeliveryFee();
        if (br.getFulfilmentOptions().contains(FulfilmentOption.MOMORIDE_DELIVERY)
                && br.getDeliveryFee() != null && br.getDeliveryFee().compareTo(floor) < 0) {
            throw ApiException.badRequest("PULA Ride delivery needs a delivery fee of at least " + floor.toPlainString());
        }
    }

    private void applyCoordinates(BusinessBranch br, BigDecimal lat, BigDecimal lon) {
        if (lat == null && lon == null) return;
        if (lat == null || lon == null) {
            throw ApiException.badRequest("Provide both latitude and longitude, or neither");
        }
        if (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lon.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw ApiException.badRequest("Latitude or longitude is out of range");
        }
        br.setLatitude(lat);
        br.setLongitude(lon);
    }

    private void applyMoney(BusinessBranch br, BigDecimal deliveryFee, BigDecimal minimumOrder) {
        if (deliveryFee != null) {
            validation.requireValidNonNegativeAmount(deliveryFee, "deliveryFee");
            br.setDeliveryFee(deliveryFee);
        }
        if (minimumOrder != null) {
            validation.requireValidNonNegativeAmount(minimumOrder, "minimumOrderAmount");
            br.setMinimumOrderAmount(minimumOrder);
        }
    }

    /** Upper-case, underscore-separated token (e.g. "beef stew" -> BEEF_STEW); null when blank. */
    static String token(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        if (t.length() > MAX_TOKEN) throw ApiException.badRequest("Value is too long: " + raw.trim());
        return t;
    }

    private static Set<String> tokens(Set<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw != null) for (String s : raw) { String t = token(s); if (t != null) out.add(t); }
        return out;
    }

    private static Set<String> areas(Set<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw) if (s != null && !s.isBlank()) out.add(s.trim());
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------ mapping

    private MenuCategoryResponse toResponse(MenuCategory c) {
        return new MenuCategoryResponse(c.getId(), c.getBusiness().getId(), c.getName(), c.getSortOrder());
    }

    private FoodDetailsResponse toResponse(ProductFoodDetails d) {
        return new FoodDetailsResponse(d.getProduct().getId(), d.getFoodType(), d.getProtein(), d.getStarch(),
                d.getPortionSize(), d.getServingSize(), d.getSpiceLevel(), d.getPortionDescription(),
                d.getPrepTimeMinutes(), d.getDailyQuantity(), new LinkedHashSet<>(d.getSides()),
                new LinkedHashSet<>(d.getDietaryTags()), new LinkedHashSet<>(d.getAllergens()));
    }

    private BranchResponse toResponse(BusinessBranch br) {
        return new BranchResponse(br.getId(), br.getBusiness().getId(), br.getName(), br.getAddress(), br.getArea(),
                br.getLatitude(), br.getLongitude(), br.isActive(), br.isTemporarilyClosed(), br.getPrepTimeMinutes(),
                br.getDeliveryFee(), br.getMinimumOrderAmount(), new ArrayList<>(br.getOperatingHours()),
                new LinkedHashSet<>(br.getFulfilmentOptions()), new LinkedHashSet<>(br.getDeliveryAreas()));
    }

    private ModifierResponse toResponse(Modifier m) {
        return new ModifierResponse(m.getId(), m.getGroup().getId(), m.getName(), m.getPriceDelta(), m.isAvailable());
    }

    private ModifierGroupResponse toResponse(ModifierGroup g) {
        List<ModifierResponse> mods = modifierRepository.findByGroupIdOrderByNameAsc(g.getId()).stream()
                .map(this::toResponse).toList();
        return new ModifierGroupResponse(g.getId(), g.getBusiness().getId(), g.getName(), g.getMinSelect(),
                g.getMaxSelect(), g.isRequired(), mods);
    }
}
