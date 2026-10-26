package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.momocircle.domain.BranchItemAvailability;
import com.momocircle.domain.Business;
import com.momocircle.domain.BusinessBranch;
import com.momocircle.domain.CanonicalFoodAttributes;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.MenuCategory;
import com.momocircle.domain.Modifier;
import com.momocircle.domain.ModifierGroup;
import com.momocircle.domain.Product;
import com.momocircle.domain.ProductFoodDetails;
import com.momocircle.domain.ProductModifierGroup;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.FulfilmentOption;
import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.FoodDiscoveryDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.BranchItemAvailabilityRepository;
import com.momocircle.repository.BusinessBranchRepository;
import com.momocircle.repository.BusinessRepository;
import com.momocircle.repository.CanonicalFoodAliasRepository;
import com.momocircle.repository.CanonicalFoodAttributesRepository;
import com.momocircle.repository.ModifierRepository;
import com.momocircle.repository.ProductFoodDetailsRepository;
import com.momocircle.repository.ProductModifierGroupRepository;
import com.momocircle.repository.ProductRepository;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

/**
 * NOT VERIFIED IN THIS SANDBOX (no Maven) — run locally. Covers PULA Food discovery: text and
 * canonical search, filters, availability, distance and its privacy, sorting without a "winner",
 * comparison groups, restaurants and menus.
 */
class FoodDiscoveryServiceTest {

    private ProductRepository products;
    private BusinessRepository businesses;
    private BusinessBranchRepository branches;
    private BranchItemAvailabilityRepository availability;
    private ProductFoodDetailsRepository details;
    private ProductModifierGroupRepository productGroups;
    private ModifierRepository modifiers;
    private CanonicalFoodAttributesRepository attributes;
    private CanonicalFoodAliasRepository aliases;
    private CanonicalProductService canonicalService;
    private FoodDiscoveryService service;

    private final List<Product> catalogue = new ArrayList<>();
    private final List<ProductFoodDetails> detailRows = new ArrayList<>();
    private CanonicalProduct stewCanonical;

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        businesses = mock(BusinessRepository.class);
        branches = mock(BusinessBranchRepository.class);
        availability = mock(BranchItemAvailabilityRepository.class);
        details = mock(ProductFoodDetailsRepository.class);
        productGroups = mock(ProductModifierGroupRepository.class);
        modifiers = mock(ModifierRepository.class);
        attributes = mock(CanonicalFoodAttributesRepository.class);
        aliases = mock(CanonicalFoodAliasRepository.class);
        canonicalService = mock(CanonicalProductService.class);
        service = new FoodDiscoveryService(products, businesses, branches, availability, details, productGroups,
                modifiers, new FoodMatchingService(attributes, aliases), canonicalService);

        when(products.findFoodCandidates(any(), any())).thenAnswer(i -> catalogue);
        when(details.findByProductIdIn(any())).thenAnswer(i -> detailRows);
        when(branches.findByBusinessIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(availability.findByBranchId(any())).thenReturn(List.of());
        when(productGroups.findByProductIdIn(any())).thenReturn(List.of());
        when(aliases.findAll()).thenReturn(List.of());

        stewCanonical = new CanonicalProduct();
        stewCanonical.setId("c-stew");
        stewCanonical.setName("Beef Stew + Pap");
        stewCanonical.setCategory("MEAL");
        CanonicalFoodAttributes a = new CanonicalFoodAttributes();
        a.setCanonicalProduct(stewCanonical);
        a.setFoodType("STEW");
        a.setProtein("BEEF");
        a.setStarch("PAP");
        a.setPortionClass("REGULAR");
        when(attributes.findAll()).thenReturn(List.of(a));
        when(attributes.findByCanonicalProductId("c-stew")).thenReturn(Optional.of(a));
        when(canonicalService.requireById("c-stew")).thenReturn(stewCanonical);
    }

    // ------------------------------------------------------------------ fixtures

    private Business business(String id, String name, BusinessType type) {
        Business b = new Business();
        b.setId(id);
        b.setName(name);
        b.setBusinessType(type);
        b.setLocation(name + " street");
        b.setFulfilmentOptions(new LinkedHashSet<>(Set.of(FulfilmentOption.CUSTOMER_PICKUP)));
        return b;
    }

    private Product item(String id, String name, String price, Business b) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        p.setCurrency("ZAR");
        p.setStatus(ProductStatus.ACTIVE);
        p.setBusiness(b);
        catalogue.add(p);
        return p;
    }

    private ProductFoodDetails food(Product p, String type, String protein, String starch, String portion, String... sides) {
        ProductFoodDetails d = new ProductFoodDetails();
        d.setProduct(p);
        d.setFoodType(type);
        d.setProtein(protein);
        d.setStarch(starch);
        d.setPortionSize(portion);
        d.setSides(new LinkedHashSet<>(Arrays.asList(sides)));
        detailRows.add(d);
        return d;
    }

    private BusinessBranch branch(Business b, String id, String name, String lat, String lon) {
        BusinessBranch br = new BusinessBranch();
        br.setId(id);
        br.setName(name);
        br.setBusiness(b);
        br.setArea(name);
        if (lat != null) { br.setLatitude(new BigDecimal(lat)); br.setLongitude(new BigDecimal(lon)); }
        return br;
    }

    private void branchesOf(Business b, BusinessBranch... list) {
        when(branches.findByBusinessIdOrderByCreatedAtAsc(b.getId())).thenReturn(Arrays.asList(list));
    }

    private FoodSearchCriteria q(String text) {
        return new FoodSearchCriteria(text, null, null, null, null, null, null, null, null, null, null, null,
                false, null, 0, 20);
    }

    private FoodSearchCriteria with(FoodSearchCriteria c, java.util.function.UnaryOperator<Builder> f) {
        return f.apply(new Builder(c)).build();
    }

    /** Tiny builder so tests can vary one criterion at a time. */
    private static final class Builder {
        String q; BigDecimal maxPrice, minRating, maxFee; FulfilmentFilter fulfilment; Integer maxPrep; String area, foodType;
        BusinessType type; Double lat, lng, maxKm; boolean includeUnavailable; FoodSort sort; int page, size;
        Builder(FoodSearchCriteria c) {
            q = c.q(); maxPrice = c.maxPrice(); minRating = c.minRating(); maxFee = c.maxDeliveryFee(); fulfilment = c.fulfilment();
            maxPrep = c.maxPrepMinutes(); area = c.area(); foodType = c.foodType(); type = c.businessType(); lat = c.lat(); lng = c.lng();
            maxKm = c.maxDistanceKm(); includeUnavailable = c.includeUnavailable(); sort = c.sort(); page = c.page(); size = c.size();
        }
        FoodSearchCriteria build() {
            return new FoodSearchCriteria(q, maxPrice, minRating, fulfilment, maxFee, maxPrep, area, foodType, type,
                    lat, lng, maxKm, includeUnavailable, sort, page, size);
        }
    }

    private static List<String> ids(FoodSearchResponse r) {
        return r.offers().stream().map(FoodOfferResponse::productId).toList();
    }

    // ------------------------------------------------------------------ text and canonical search

    @Test
    void textSearchMatchesAllWordsAnywhereInTheItem() {
        Business b = business("b1", "Gogo's Kitchen", BusinessType.HOME_KITCHEN);
        item("p1", "Beef Stew & Pap", "65.00", b);
        item("p2", "Chicken Kota", "45.00", b);
        item("p3", "Beef Burger", "55.00", b);
        assertEquals(List.of("p1"), ids(service.search(q("beef pap"))));
        assertEquals(3, service.search(q(null)).total());
        assertEquals(0, service.search(q("sushi")).total());
    }

    @Test
    void canonicalSearchGroupsExactSimilarAndAlternativeAndExcludesDifferentMeals() {
        Business b1 = business("b1", "Gogo's Kitchen", BusinessType.HOME_KITCHEN);
        Business b2 = business("b2", "Kasi Kitchen", BusinessType.RESTAURANT);
        Business b3 = business("b3", "Burger Joint", BusinessType.FAST_FOOD);
        Business b4 = business("b4", "Chicken Place", BusinessType.CAFE);
        Product exactItem = item("p-exact", "Beef & Pap", "60.00", b1);
        food(exactItem, "STEW", "BEEF", "PAP", "REGULAR");
        Product similar = item("p-similar", "Beef Stew with Pap and Spinach", "75.00", b2);
        food(similar, "STEW", "BEEF", "PAP", "REGULAR", "SPINACH");
        Product burger = item("p-burger", "Beef burger and chips", "55.00", b3);
        food(burger, "BURGER", "BEEF", "CHIPS", "REGULAR");
        Product kota = item("p-kota", "Chicken kota", "40.00", b4);
        food(kota, "KOTA", "CHICKEN", "CHIPS", "REGULAR");

        FoodSearchResponse r = service.search(q("beef stew and pap"));

        assertEquals(List.of("Beef Stew + Pap"), r.interpretedAs());
        assertEquals(List.of("p-exact", "p-similar", "p-burger"), ids(r)); // by closeness of match; the kota is excluded
        assertEquals("EXACT_MATCH", r.offers().get(0).matchLevel());
        assertEquals("SIMILAR_MEAL", r.offers().get(1).matchLevel());
        assertEquals("ALTERNATIVE", r.offers().get(2).matchLevel());
        assertFalse(r.offers().get(1).matchReasons().isEmpty());
    }

    @Test
    void anItemWithTheRightWordsButNoAttributesIsStillFoundWithoutAMatchLevel() {
        Business b = business("b1", "Mama's Place", BusinessType.HOME_KITCHEN);
        item("p1", "Beef stew with pap", "58.00", b);
        FoodSearchResponse r = service.search(q("beef stew pap"));
        assertEquals(List.of("p1"), ids(r));
        assertEquals(null, r.offers().get(0).matchLevel());
    }

    // ------------------------------------------------------------------ filters

    @Test
    void priceFilterUsesTheBranchOverridePrice() {
        Business b = business("b1", "Gogo's Kitchen", BusinessType.RESTAURANT);
        Product p = item("p1", "Kota", "70.00", b);
        BusinessBranch cheap = branch(b, "br-cheap", "Cheap", null, null);
        BusinessBranch dear = branch(b, "br-dear", "Dear", null, null);
        branchesOf(b, cheap, dear);
        BranchItemAvailability row = new BranchItemAvailability();
        row.setBranch(cheap);
        row.setProduct(p);
        row.setPriceOverride(new BigDecimal("55.00"));
        when(availability.findByBranchId("br-cheap")).thenReturn(List.of(row));

        FoodSearchResponse r = service.search(with(q("kota"), c -> { c.maxPrice = new BigDecimal("60"); return c; }));

        assertEquals(1, r.offers().size());
        assertEquals("br-cheap", r.offers().get(0).branchId());
        assertEquals(new BigDecimal("55.00"), r.offers().get(0).price());
    }

    @Test
    void unavailableOffersAreHiddenByDefaultAndExplainedWhenRequested() {
        Business open = business("b1", "Open Kitchen", BusinessType.RESTAURANT);
        Business closed = business("b2", "Closed Kitchen", BusinessType.RESTAURANT);
        closed.setTemporarilyClosed(true);
        Business capped = business("b3", "Sold Out Kitchen", BusinessType.RESTAURANT);
        item("p-open", "Kota", "40.00", open);
        item("p-closed", "Kota", "41.00", closed);
        Product soldOut = item("p-sold", "Kota", "42.00", capped);
        ProductFoodDetails d = food(soldOut, "KOTA", null, null, null);
        d.setDailyQuantity(0);

        assertEquals(List.of("p-open"), ids(service.search(q("kota"))));
        FoodSearchResponse all = service.search(with(q("kota"), c -> { c.includeUnavailable = true; return c; }));
        assertEquals(3, all.total());
        assertEquals("Temporarily closed", all.offers().stream().filter(o -> o.productId().equals("p-closed")).findFirst().get().unavailableReason());
        assertEquals("Sold out", all.offers().stream().filter(o -> o.productId().equals("p-sold")).findFirst().get().unavailableReason());
    }

    @Test
    void anItemTurnedOffAtOneBranchOnlyDisappearsFromThatBranch() {
        Business b = business("b1", "Gogo's Kitchen", BusinessType.RESTAURANT);
        Product p = item("p1", "Kota", "40.00", b);
        BusinessBranch one = branch(b, "br1", "One", null, null);
        BusinessBranch two = branch(b, "br2", "Two", null, null);
        branchesOf(b, one, two);
        BranchItemAvailability off = new BranchItemAvailability();
        off.setBranch(two);
        off.setProduct(p);
        off.setAvailable(false);
        when(availability.findByBranchId("br2")).thenReturn(List.of(off));
        assertEquals(List.of("br1"), service.search(q("kota")).offers().stream().map(FoodOfferResponse::branchId).toList());
    }

    @Test
    void fulfilmentAndDeliveryFeeFilters() {
        Business pickupOnly = business("b1", "Pickup Only", BusinessType.RESTAURANT);
        Business delivers = business("b2", "Delivers", BusinessType.RESTAURANT);
        delivers.setFulfilmentOptions(new LinkedHashSet<>(Set.of(FulfilmentOption.CUSTOMER_PICKUP, FulfilmentOption.MOMORIDE_DELIVERY)));
        delivers.setDeliveryFee(new BigDecimal("20.00"));
        Business noFee = business("b3", "Delivery No Fee Set", BusinessType.RESTAURANT);
        noFee.setFulfilmentOptions(new LinkedHashSet<>(Set.of(FulfilmentOption.MOMORIDE_DELIVERY)));
        item("p1", "Kota", "40.00", pickupOnly);
        item("p2", "Kota", "41.00", delivers);
        item("p3", "Kota", "42.00", noFee);

        assertEquals(List.of("p2"), ids(service.search(with(q("kota"), c -> { c.fulfilment = FulfilmentFilter.DELIVERY; return c; }))));
        assertEquals(List.of("p1", "p2"), ids(service.search(with(q("kota"), c -> {
            c.fulfilment = FulfilmentFilter.COLLECTION;
            c.sort = FoodSort.PRICE;
            return c;
        }))));
        assertEquals(List.of("p2"), ids(service.search(with(q("kota"), c -> { c.maxFee = new BigDecimal("25"); return c; }))));
        assertEquals(0, service.search(with(q("kota"), c -> { c.maxFee = new BigDecimal("10"); return c; })).total());
    }

    @Test
    void ratingFilterUsesTheItemRatingWhenItHasReviewsOtherwiseTheRestaurants() {
        Business b = business("b1", "Rated", BusinessType.RESTAURANT);
        b.setRatingAverage(new BigDecimal("4.8"));
        b.setRatingCount(10);
        Business unrated = business("b2", "Unrated", BusinessType.RESTAURANT);
        Product itemRated = item("p1", "Kota", "40.00", b);
        itemRated.setRatingAverage(new BigDecimal("3.0"));
        itemRated.setRatingCount(5);
        Product restaurantRated = item("p2", "Kota", "41.00", b);
        item("p3", "Kota", "42.00", unrated);

        FoodSearchResponse r = service.search(with(q("kota"), c -> { c.minRating = new BigDecimal("4.5"); return c; }));
        assertEquals(List.of("p2"), ids(r)); // p1's own 3.0 wins over the restaurant's 4.8; p3 has no ratings at all
    }

    @Test
    void prepTimeAndAreaAndFoodTypeFilters() {
        Business b = business("b1", "Kasi Kitchen", BusinessType.RESTAURANT);
        Product p = item("p1", "Kota", "40.00", b);
        food(p, "KOTA", "CHICKEN", "CHIPS", "REGULAR").setPrepTimeMinutes(15);
        Product slow = item("p2", "Slow kota", "40.00", b);
        food(slow, "KOTA", "CHICKEN", "CHIPS", "REGULAR").setPrepTimeMinutes(45);
        Product other = item("p3", "Pap and wors", "40.00", b);
        food(other, "GRILL", "BOEREWORS", "PAP", "REGULAR");

        assertEquals(List.of("p1"), ids(service.search(with(q("kota"), c -> { c.maxPrep = 20; return c; }))));
        assertEquals(List.of("p1", "p2"), ids(service.search(with(q(null), c -> { c.foodType = "kota"; return c; }))));
        assertEquals(3, service.search(with(q(null), c -> { c.area = "kasi kitchen"; return c; })).total());
        assertEquals(0, service.search(with(q(null), c -> { c.area = "Sandton"; return c; })).total());
    }

    // ------------------------------------------------------------------ distance and privacy

    @Test
    void distanceIsComputedFromBranchCoordinatesAndFilteredByMaxDistance() {
        Business b = business("b1", "Gogo's Kitchen", BusinessType.RESTAURANT);
        item("p1", "Kota", "40.00", b);
        BusinessBranch near = branch(b, "near", "Near", "-26.2050", "28.0470");   // ~0.1 km from the customer
        BusinessBranch far = branch(b, "far", "Far", "-26.2485", "27.8540");      // ~20 km away
        branchesOf(b, near, far);

        FoodSearchCriteria base = with(q("kota"), c -> { c.lat = -26.2041; c.lng = 28.0473; return c; });
        FoodSearchResponse all = service.search(base);
        assertEquals(2, all.total());
        double nearKm = all.offers().stream().filter(o -> o.branchId().equals("near")).findFirst().get().distanceKm();
        double farKm = all.offers().stream().filter(o -> o.branchId().equals("far")).findFirst().get().distanceKm();
        assertTrue(nearKm < 1.0, "near was " + nearKm);
        assertTrue(farKm > 15.0 && farKm < 25.0, "far was " + farKm);

        FoodSearchResponse close = service.search(with(base, c -> { c.maxKm = 5.0; return c; }));
        assertEquals(List.of("near"), close.offers().stream().map(FoodOfferResponse::branchId).toList());
    }

    @Test
    void offersWithUnknownLocationAreExcludedFromADistanceSearchAndCounted() {
        Business located = business("b1", "Located", BusinessType.RESTAURANT);
        Business noCoords = business("b2", "No Coordinates", BusinessType.RESTAURANT);
        item("p1", "Kota", "40.00", located);
        item("p2", "Kota", "41.00", noCoords);
        branchesOf(located, branch(located, "br", "Here", "-26.2050", "28.0470"));

        FoodSearchResponse r = service.search(with(q("kota"), c -> { c.lat = -26.2041; c.lng = 28.0473; c.maxKm = 10.0; return c; }));

        assertEquals(List.of("p1"), ids(r));
        assertEquals(1, r.excludedForUnknownDistance());
    }

    @Test
    void withoutACustomerLocationNoDistanceIsReportedOrFiltered() {
        Business b = business("b1", "Gogo's Kitchen", BusinessType.RESTAURANT);
        item("p1", "Kota", "40.00", b);
        branchesOf(b, branch(b, "br", "Here", "-26.2050", "28.0470"));
        FoodSearchResponse r = service.search(with(q("kota"), c -> { c.maxKm = 5.0; return c; })); // no lat/lng: distance filter can't apply
        assertEquals(1, r.total());
        assertEquals(null, r.offers().get(0).distanceKm());
    }

    @Test
    void responsesNeverCarryTheCustomersCoordinates() {
        for (Class<?> type : List.of(FoodOfferResponse.class, FoodSearchResponse.class, FoodComparisonResponse.class,
                RestaurantSummary.class, RestaurantListResponse.class)) {
            for (RecordComponent rc : type.getRecordComponents()) {
                String n = rc.getName().toLowerCase();
                assertFalse(n.equals("lat") || n.equals("lng") || n.equals("latitude") || n.equals("longitude"),
                        type.getSimpleName() + " exposes " + rc.getName());
            }
        }
    }

    // ------------------------------------------------------------------ sorting is customer-chosen

    @Test
    void sortingFollowsTheCustomersChoiceAndNoWinnerIsDeclared() {
        Business a = business("b1", "A", BusinessType.RESTAURANT);
        Business b = business("b2", "B", BusinessType.RESTAURANT);
        a.setRatingAverage(new BigDecimal("3.5")); a.setRatingCount(3);
        b.setRatingAverage(new BigDecimal("4.9")); b.setRatingCount(30);
        item("p-cheap", "Kota", "30.00", a);
        item("p-dear", "Kota", "60.00", b);

        assertEquals(List.of("p-cheap", "p-dear"), ids(service.search(with(q("kota"), c -> { c.sort = FoodSort.PRICE; return c; }))));
        assertEquals(List.of("p-dear", "p-cheap"), ids(service.search(with(q("kota"), c -> { c.sort = FoodSort.RATING; return c; }))));
        // the response has no "best"/"winner"/"cheapest" field at all
        for (RecordComponent rc : FoodOfferResponse.class.getRecordComponents()) {
            String n = rc.getName().toLowerCase();
            assertFalse(n.contains("winner") || n.contains("best") || n.contains("cheapest") || n.contains("recommended"), rc.getName());
        }
    }

    @Test
    void pageSizeIsCapped() {
        Business b = business("b1", "Gogo's Kitchen", BusinessType.RESTAURANT);
        for (int i = 0; i < 60; i++) item("p" + i, "Kota " + i, "40.00", b);
        FoodSearchResponse r = service.search(with(q(null), c -> { c.size = 1000; return c; }));
        assertEquals(50, r.size());
        assertEquals(50, r.offers().size());
        assertEquals(60, r.total());
    }

    // ------------------------------------------------------------------ comparison

    @Test
    void comparingAnItemGroupsOtherRestaurantsByLevelAndExcludesTheItemItself() {
        Business b1 = business("b1", "Gogo's Kitchen", BusinessType.HOME_KITCHEN);
        Business b2 = business("b2", "Kasi Kitchen", BusinessType.RESTAURANT);
        Business b3 = business("b3", "Mama's Place", BusinessType.RESTAURANT);
        Business b4 = business("b4", "Burger Joint", BusinessType.FAST_FOOD);
        Business b5 = business("b5", "Closed Place", BusinessType.RESTAURANT);
        b5.setTemporarilyClosed(true);
        Product mine = item("p-mine", "Beef Stew + Pap", "65.00", b1);
        food(mine, "STEW", "BEEF", "PAP", "REGULAR");
        Product exact = item("p-exact", "Beef & Pap", "60.00", b2);
        food(exact, "STEW", "BEEF", "PAP", "REGULAR");
        Product similar = item("p-similar", "Beef Stew, Pap & Spinach", "75.00", b3);
        food(similar, "STEW", "BEEF", "PAP", "REGULAR", "SPINACH");
        Product burger = item("p-burger", "Beef burger", "55.00", b4);
        food(burger, "BURGER", "BEEF", "CHIPS", "REGULAR");
        Product closedExact = item("p-closed", "Beef stew and pap", "50.00", b5);
        food(closedExact, "STEW", "BEEF", "PAP", "REGULAR");
        when(products.findById("p-mine")).thenReturn(Optional.of(mine));
        when(details.findByProductId("p-mine")).thenReturn(Optional.of(detailRows.get(0)));

        FoodComparisonResponse r = service.compareItem("p-mine", q(null));

        assertEquals(List.of("p-exact"), r.exactMatches().stream().map(FoodOfferResponse::productId).toList());
        assertEquals(List.of("p-similar"), r.similarMeals().stream().map(FoodOfferResponse::productId).toList());
        assertEquals(List.of("p-burger"), r.alternatives().stream().map(FoodOfferResponse::productId).toList());
        assertTrue(r.note().contains("does not pick a winner"));
        assertEquals("p-mine", r.referenceProductId());
    }

    @Test
    void comparingACanonicalFoodUsesItsAttributesAndExplicitLinks() {
        Business b1 = business("b1", "Gogo's Kitchen", BusinessType.HOME_KITCHEN);
        Business b2 = business("b2", "Kasi Kitchen", BusinessType.RESTAURANT);
        Product linked = item("p-linked", "Gogo's special", "62.00", b1);
        linked.setCanonicalProduct(stewCanonical);
        linked.setMatchClassification(MatchClassification.EXACT_MATCH);
        food(linked, "STEW", "BEEF", "PAP", "REGULAR");
        Product byAttributes = item("p-attrs", "Beef and pap", "60.00", b2);
        food(byAttributes, "STEW", "BEEF", "PAP", "REGULAR");

        FoodComparisonResponse r = service.compareCanonical("c-stew", q(null));

        assertEquals("Beef Stew + Pap", r.referenceName());
        assertEquals(2, r.exactMatches().size());
        assertEquals("Linked to the same meal by the restaurant",
                r.exactMatches().stream().filter(o -> o.productId().equals("p-linked")).findFirst().get().matchReasons().get(0));
    }

    @Test
    void comparingAnUnknownOrNonFoodItemIsNotFound() {
        Business retail = business("b9", "Hardware", BusinessType.FORMAL_RETAILER);
        Product p = item("p-hw", "Hammer", "99.00", retail);
        when(products.findById("p-hw")).thenReturn(Optional.of(p));
        when(products.findById("missing")).thenReturn(Optional.empty());
        assertThrows(ApiException.class, () -> service.compareItem("p-hw", q(null)));
        assertThrows(ApiException.class, () -> service.compareItem("missing", q(null)));
    }

    // ------------------------------------------------------------------ restaurants

    @Test
    void restaurantListFiltersSortsAndReportsClosedAndNearest() {
        Business gogo = business("b1", "Gogo's Kitchen", BusinessType.HOME_KITCHEN);
        gogo.setDescription("Home cooking in Soweto");
        gogo.setFulfilmentOptions(new LinkedHashSet<>(Set.of(FulfilmentOption.CUSTOMER_PICKUP, FulfilmentOption.MOMORIDE_DELIVERY)));
        gogo.setDeliveryFee(new BigDecimal("18.00"));
        gogo.setRatingAverage(new BigDecimal("4.7")); gogo.setRatingCount(12);
        Business kasi = business("b2", "Kasi Kitchen", BusinessType.RESTAURANT);
        kasi.setTemporarilyClosed(true);
        when(businesses.findByActiveTrueAndBusinessTypeIn(any(), any())).thenReturn(new PageImpl<>(List.of(gogo, kasi)));
        branchesOf(gogo, branch(gogo, "br", "Soweto", "-26.2050", "28.0470"));

        RestaurantListResponse all = service.restaurants(new RestaurantQuery(null, null, null, null, null, -26.2041, 28.0473, null, null, 0, 20));
        assertEquals(2, all.total());
        RestaurantSummary g = all.restaurants().get(0);
        assertEquals("Gogo's Kitchen", g.name());
        assertTrue(g.deliveryAvailable() && g.collectionAvailable());
        assertEquals(new BigDecimal("18.00"), g.deliveryFeeFrom());
        assertTrue(g.nearestDistanceKm() < 1.0);
        RestaurantSummary k = all.restaurants().get(1);
        assertFalse(k.open());
        assertEquals("Temporarily closed", k.closedReason());

        assertEquals(List.of("b1"), service.restaurants(new RestaurantQuery("soweto", null, null, null, null, null, null, null, null, 0, 20))
                .restaurants().stream().map(RestaurantSummary::id).toList());
        assertEquals(List.of("b1"), service.restaurants(new RestaurantQuery(null, null, null, FulfilmentFilter.DELIVERY, null, null, null, null, null, 0, 20))
                .restaurants().stream().map(RestaurantSummary::id).toList());
        assertEquals(List.of("b1"), service.restaurants(new RestaurantQuery(null, null, null, null, new BigDecimal("4.5"), null, null, null, null, 0, 20))
                .restaurants().stream().map(RestaurantSummary::id).toList());

        RestaurantListResponse near = service.restaurants(new RestaurantQuery(null, null, null, null, null, -26.2041, 28.0473, 5.0, FoodSort.DISTANCE, 0, 20));
        assertEquals(List.of("b1"), near.restaurants().stream().map(RestaurantSummary::id).toList());
        assertEquals(1, near.excludedForUnknownDistance()); // Kasi Kitchen has no coordinates
    }

    @Test
    void restaurantDetailIsNotFoundForInactiveOrNonFoodBusinesses() {
        Business retail = business("b9", "Hardware", BusinessType.FORMAL_RETAILER);
        Business inactive = business("b8", "Gone", BusinessType.RESTAURANT);
        inactive.setActive(false);
        when(businesses.findById("b9")).thenReturn(Optional.of(retail));
        when(businesses.findById("b8")).thenReturn(Optional.of(inactive));
        assertThrows(ApiException.class, () -> service.restaurant("b9", null, null));
        assertThrows(ApiException.class, () -> service.restaurant("b8", null, null));
        assertThrows(ApiException.class, () -> service.menu("nope", null));
    }

    // ------------------------------------------------------------------ menu

    @Test
    void menuGroupsByCategoryAppliesBranchPricesAndIncludesModifiers() {
        Business b = business("b1", "Gogo's Kitchen", BusinessType.HOME_KITCHEN);
        when(businesses.findById("b1")).thenReturn(Optional.of(b));
        MenuCategory kota = new MenuCategory(); kota.setId("c-kota"); kota.setName("Kota"); kota.setSortOrder(2); kota.setBusiness(b);
        MenuCategory breakfast = new MenuCategory(); breakfast.setId("c-bf"); breakfast.setName("Breakfast"); breakfast.setSortOrder(1); breakfast.setBusiness(b);
        Product kotaItem = item("p-kota", "Chicken kota", "45.00", b); kotaItem.setMenuCategory(kota);
        Product egg = item("p-egg", "Egg and bacon", "30.00", b); egg.setMenuCategory(breakfast);
        Product loose = item("p-loose", "Cold drink", "12.00", b);
        when(products.findByBusinessIdAndStatusOrderByNameAsc("b1", ProductStatus.ACTIVE)).thenReturn(List.copyOf(catalogue));

        BusinessBranch soweto = branch(b, "br1", "Soweto", null, null);
        branchesOf(b, soweto);
        BranchItemAvailability priced = new BranchItemAvailability();
        priced.setBranch(soweto); priced.setProduct(kotaItem); priced.setPriceOverride(new BigDecimal("48.00"));
        BranchItemAvailability off = new BranchItemAvailability();
        off.setBranch(soweto); off.setProduct(loose); off.setAvailable(false);
        when(availability.findByBranchId("br1")).thenReturn(List.of(priced, off));

        ModifierGroup sides = new ModifierGroup(); sides.setId("g1"); sides.setName("Extras"); sides.setMinSelect(0); sides.setMaxSelect(2); sides.setBusiness(b);
        ProductModifierGroup link = new ProductModifierGroup(); link.setProduct(kotaItem); link.setGroup(sides);
        Modifier m = new Modifier(); m.setId("m1"); m.setGroup(sides); m.setName("Egg"); m.setPriceDelta(new BigDecimal("8.00"));
        when(productGroups.findByProductIdIn(any())).thenReturn(List.of(link));
        when(modifiers.findByGroupIdOrderByNameAsc("g1")).thenReturn(List.of(m));

        MenuResponse menu = service.menu("b1", "br1");

        assertEquals(List.of("Breakfast", "Kota", "Other"), menu.categories().stream().map(MenuCategoryView::name).toList());
        MenuItemView k = menu.categories().get(1).items().get(0);
        assertEquals(new BigDecimal("48.00"), k.price());
        assertEquals(1, k.modifierGroups().size());
        assertEquals("Egg", k.modifierGroups().get(0).modifiers().get(0).name());
        MenuItemView drink = menu.categories().get(2).items().get(0);
        assertFalse(drink.available());
        assertEquals("Not available at this branch", drink.unavailableReason());
        assertNotNull(menu.branchName());
    }

    @Test
    void menuForAnUnknownBranchIsNotFound() {
        Business b = business("b1", "Gogo's Kitchen", BusinessType.HOME_KITCHEN);
        when(businesses.findById("b1")).thenReturn(Optional.of(b));
        branchesOf(b, branch(b, "br1", "Soweto", null, null));
        assertThrows(ApiException.class, () -> service.menu("b1", "someone-elses-branch"));
    }

    @Test
    void haversineIsRoughlyRight() {
        assertEquals(0.0, FoodDiscoveryService.haversineKm(1, 1, 1, 1), 1e-9);
        double joburgToPretoria = FoodDiscoveryService.haversineKm(-26.2041, 28.0473, -25.7479, 28.2293);
        assertTrue(joburgToPretoria > 45 && joburgToPretoria < 60, "was " + joburgToPretoria);
    }
}
