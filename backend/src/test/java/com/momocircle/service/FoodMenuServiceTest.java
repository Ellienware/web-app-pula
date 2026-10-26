package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.MenuCategory;
import com.momocircle.domain.ModifierGroup;
import com.momocircle.domain.Product;
import com.momocircle.domain.ProductFoodDetails;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.dto.FoodDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NOT VERIFIED IN THIS SANDBOX — no Maven Central access here; run `mvn test` locally before
 * relying on this. Covers PULA Food menu management: food-type gating, tenant isolation,
 * validation, and idempotent attach.
 */
class FoodMenuServiceTest {

    private BusinessService businessService;
    private ProductRepository productRepository;
    private MenuCategoryRepository categoryRepository;
    private ProductFoodDetailsRepository detailsRepository;
    private BusinessBranchRepository branchRepository;
    private BranchItemAvailabilityRepository availabilityRepository;
    private ModifierGroupRepository groupRepository;
    private ModifierRepository modifierRepository;
    private ProductModifierGroupRepository productGroupRepository;
    private FoodMenuService service;

    private User owner;
    private Business business;
    private Business otherBusiness;

    @BeforeEach
    void setUp() {
        businessService = mock(BusinessService.class);
        productRepository = mock(ProductRepository.class);
        categoryRepository = mock(MenuCategoryRepository.class);
        detailsRepository = mock(ProductFoodDetailsRepository.class);
        branchRepository = mock(BusinessBranchRepository.class);
        availabilityRepository = mock(BranchItemAvailabilityRepository.class);
        groupRepository = mock(ModifierGroupRepository.class);
        modifierRepository = mock(ModifierRepository.class);
        productGroupRepository = mock(ProductModifierGroupRepository.class);

        CurrencyProperties currency = new CurrencyProperties();
        currency.setDefaultCurrency("ZAR");
        currency.setSupported(List.of("ZAR"));
        FinancialValidationService validation = new FinancialValidationService(currency, new BigDecimal("10000000"));

        service = new FoodMenuService(businessService, validation, productRepository, categoryRepository,
                detailsRepository, branchRepository, availabilityRepository, groupRepository, modifierRepository,
                productGroupRepository, new com.momocircle.config.FoodProperties());

        owner = new User();
        owner.setId("owner-1");
        business = new Business();
        business.setId("biz-1");
        business.setOwner(owner);
        business.setBusinessType(BusinessType.HOME_KITCHEN);
        otherBusiness = new Business();
        otherBusiness.setId("biz-2");
        when(businessService.requireOwn(owner)).thenReturn(business);
        when(categoryRepository.save(any(MenuCategory.class))).thenAnswer(i -> i.getArgument(0));
        when(branchRepository.save(any(BusinessBranch.class))).thenAnswer(i -> i.getArgument(0));
        when(groupRepository.save(any(ModifierGroup.class))).thenAnswer(i -> i.getArgument(0));
        when(detailsRepository.save(any(ProductFoodDetails.class))).thenAnswer(i -> i.getArgument(0));
        when(modifierRepository.findByGroupIdOrderByNameAsc(any())).thenReturn(List.of());
    }

    private Product productOf(Business b, String id) {
        Product p = new Product();
        p.setId(id);
        p.setBusiness(b);
        return p;
    }

    @Test
    void retailBusinessCannotUseMenuFeatures() {
        business.setBusinessType(BusinessType.FORMAL_RETAILER);
        assertThrows(ApiException.class,
                () -> service.createCategory(owner, new CreateMenuCategoryRequest("Kota", null)));
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void createsCategoryForFoodBusinessAndRejectsDuplicateName() {
        MenuCategoryResponse r = service.createCategory(owner, new CreateMenuCategoryRequest("  Kota ", 2));
        assertEquals("Kota", r.name());
        assertEquals(2, r.sortOrder());

        when(categoryRepository.existsByBusinessIdAndNameIgnoreCase("biz-1", "Kota")).thenReturn(true);
        assertThrows(ApiException.class,
                () -> service.createCategory(owner, new CreateMenuCategoryRequest("Kota", null)));
    }

    @Test
    void cannotDeleteCategoryThatStillHasItems() {
        MenuCategory c = new MenuCategory();
        c.setId("cat-1");
        c.setBusiness(business);
        when(categoryRepository.findById("cat-1")).thenReturn(Optional.of(c));
        when(productRepository.countByMenuCategoryId("cat-1")).thenReturn(3L);
        assertThrows(ApiException.class, () -> service.deleteCategory(owner, "cat-1"));
        verify(categoryRepository, never()).delete(any());
    }

    @Test
    void foodDetailsAreNormalisedAndOptional() {
        when(productRepository.findById("p1")).thenReturn(Optional.of(productOf(business, "p1")));
        when(detailsRepository.findByProductId("p1")).thenReturn(Optional.empty());

        FoodDetailsResponse r = service.upsertFoodDetails(owner, "p1", new UpsertFoodDetailsRequest(
                "stew", "beef", "pap", "regular", null, null, null, 25, null,
                Set.of("spinach", " ", "Fried Egg"), null, null));

        assertEquals("STEW", r.foodType());
        assertEquals("BEEF", r.protein());
        assertEquals("PAP", r.starch());
        assertEquals(Set.of("SPINACH", "FRIED_EGG"), r.sides());
        assertTrue(r.allergens().isEmpty());
    }

    @Test
    void cannotEditAnotherBusinessesProduct() {
        when(productRepository.findById("p-other")).thenReturn(Optional.of(productOf(otherBusiness, "p-other")));
        ApiException ex = assertThrows(ApiException.class, () -> service.upsertFoodDetails(owner, "p-other",
                new UpsertFoodDetailsRequest(null, null, null, null, null, null, null, null, null, null, null, null)));
        assertEquals("Product not found", ex.getMessage());
        verify(detailsRepository, never()).save(any());
    }

    @Test
    void branchRequiresBothCoordinatesAndValidRange() {
        assertThrows(ApiException.class, () -> service.createBranch(owner, new CreateBranchRequest(
                "Soweto", null, "Soweto", new BigDecimal("-26.25"), null, null, null, null, null, null, null)));
        assertThrows(ApiException.class, () -> service.createBranch(owner, new CreateBranchRequest(
                "Soweto", null, "Soweto", new BigDecimal("95"), new BigDecimal("27.9"), null, null, null, null, null, null)));

        BranchResponse ok = service.createBranch(owner, new CreateBranchRequest(
                "Soweto", "12 Vilakazi St", "Soweto", new BigDecimal("-26.238"), new BigDecimal("27.907"),
                20, new BigDecimal("15.00"), null, null, null, Set.of(" Soweto ", "Diepkloof")));
        assertEquals(Set.of("Soweto", "Diepkloof"), ok.deliveryAreas());
    }

    @Test
    void cannotSetAvailabilityOnAnotherBusinessesBranch() {
        BusinessBranch foreign = new BusinessBranch();
        foreign.setId("br-x");
        foreign.setBusiness(otherBusiness);
        when(branchRepository.findById("br-x")).thenReturn(Optional.of(foreign));
        when(productRepository.findById("p1")).thenReturn(Optional.of(productOf(business, "p1")));
        assertThrows(ApiException.class,
                () -> service.setBranchItem(owner, "br-x", "p1", new SetBranchItemRequest(true, null)));
        verify(availabilityRepository, never()).save(any());
    }

    @Test
    void priceOverrideMustBePositive() {
        BusinessBranch br = new BusinessBranch();
        br.setId("br-1");
        br.setBusiness(business);
        when(branchRepository.findById("br-1")).thenReturn(Optional.of(br));
        when(productRepository.findById("p1")).thenReturn(Optional.of(productOf(business, "p1")));
        assertThrows(ApiException.class,
                () -> service.setBranchItem(owner, "br-1", "p1", new SetBranchItemRequest(true, BigDecimal.ZERO)));
    }

    @Test
    void modifierGroupBoundsAreValidated() {
        assertThrows(ApiException.class, () -> service.createModifierGroup(owner,
                new CreateModifierGroupRequest("Sides", 3, 2)));
        ModifierGroupResponse g = service.createModifierGroup(owner, new CreateModifierGroupRequest("Sides", 1, 2));
        assertTrue(g.required());
    }

    @Test
    void modifierPriceCannotBeNegative() {
        ModifierGroup g = new ModifierGroup();
        g.setId("g1");
        g.setBusiness(business);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(g));
        assertThrows(ApiException.class,
                () -> service.addModifier(owner, "g1", new CreateModifierRequest("Egg", new BigDecimal("-1"))));
    }

    @Test
    void attachingTheSameGroupTwiceIsIdempotent() {
        ModifierGroup g = new ModifierGroup();
        g.setId("g1");
        g.setBusiness(business);
        when(groupRepository.findById("g1")).thenReturn(Optional.of(g));
        when(productRepository.findById("p1")).thenReturn(Optional.of(productOf(business, "p1")));
        when(productGroupRepository.findByProductIdAndGroupId("p1", "g1"))
                .thenReturn(Optional.of(new com.momocircle.domain.ProductModifierGroup()));
        service.attachModifierGroup(owner, "p1", "g1");
        verify(productGroupRepository, never()).save(any());
    }

    @Test
    void acceptWindowIsCappedAndClosureToggles() {
        FoodSettingsResponse r = service.updateSettings(owner, new UpdateFoodSettingsRequest(true, 12));
        assertTrue(r.temporarilyClosed());
        assertEquals(12, r.effectiveAcceptTimeoutMinutes());
        assertThrows(ApiException.class, () -> service.updateSettings(owner, new UpdateFoodSettingsRequest(null, 16)));
        assertEquals(12, service.getSettings(owner).acceptTimeoutMinutes());
    }

    @Test
    void rideDeliveryFeeMustMeetConfiguredFloor() {
        com.momocircle.config.FoodProperties props = new com.momocircle.config.FoodProperties();
        props.setMinPulaRideDeliveryFee(new BigDecimal("15"));
        FoodMenuService strict = new FoodMenuService(businessService,
                new FinancialValidationService(new CurrencyProperties() {{ setDefaultCurrency("ZAR"); setSupported(List.of("ZAR")); }},
                        new BigDecimal("10000000")),
                productRepository, categoryRepository, detailsRepository, branchRepository, availabilityRepository,
                groupRepository, modifierRepository, productGroupRepository, props);
        assertThrows(ApiException.class, () -> strict.createBranch(owner, new CreateBranchRequest(
                "Soweto", null, null, null, null, null, new BigDecimal("10"), null, null,
                Set.of(com.momocircle.domain.enums.FulfilmentOption.MOMORIDE_DELIVERY), null)));
        assertEquals("Soweto", strict.createBranch(owner, new CreateBranchRequest(
                "Soweto", null, null, null, null, null, new BigDecimal("20"), null, null,
                Set.of(com.momocircle.domain.enums.FulfilmentOption.MOMORIDE_DELIVERY), null)).name());
    }

    @Test
    void foodTokenNormalisation() {
        assertEquals("BEEF_STEW", FoodMenuService.token(" beef stew "));
        assertEquals("FRIED_EGG", FoodMenuService.token("fried-egg"));
        assertEquals(null, FoodMenuService.token("  "));
    }
}
