package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.momocircle.domain.Business;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.BusinessType;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.AssistantDtos.ActionType;
import com.momocircle.dto.AssistantDtos.AssistantResponse;
import com.momocircle.dto.AssistantDtos.ProposedAction;
import com.momocircle.dto.FoodDiscoveryDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NOT VERIFIED IN THIS SANDBOX (no Maven) — run locally. Covers PULA AI for food: what it recognises,
 * that answers come only from PULA data and never declare a winner, that "add to cart" resolves the
 * product on the server (ignoring any model-supplied id) and needs confirmation, and that it never
 * orders or pays.
 */
class FoodAssistantServiceTest {

    private FoodDiscoveryService discovery;
    private FoodMatchingService matching;
    private FoodCheckoutService foodCheckout;
    private ProductRepository products;
    private FoodAssistantService service;
    private User user;

    @BeforeEach
    void setUp() {
        discovery = mock(FoodDiscoveryService.class);
        matching = mock(FoodMatchingService.class);
        foodCheckout = mock(FoodCheckoutService.class);
        products = mock(ProductRepository.class);
        service = new FoodAssistantService(discovery, matching, foodCheckout, products);
        user = new User();
        user.setId("u1");
    }

    // ------------------------------------------------------------------ detection

    private ProposedAction detect(String message) {
        return service.detect(message).orElse(null);
    }

    @Test
    void findsBeefStewAndPapNearMe() {
        ProposedAction a = detect("Find beef stew and pap near me");
        assertEquals(ActionType.SEARCH_FOOD, a.intent());
        assertEquals("beef stew pap", a.parameters().get("q"));
        assertEquals(true, a.parameters().get("nearMe"));
        assertFalse(a.requiresConfirmation());
    }

    @Test
    void understandsPriceCapsAndAreas() {
        ProposedAction a = detect("Where can I get chicken and chips under R60?");
        assertEquals(ActionType.SEARCH_FOOD, a.intent());
        assertEquals("chicken chips", a.parameters().get("q"));
        assertEquals(new BigDecimal("60"), a.parameters().get("maxPrice"));

        ProposedAction b = detect("find a kota under R45 in Soweto");
        assertEquals("kota", b.parameters().get("q"));
        assertEquals(new BigDecimal("45"), b.parameters().get("maxPrice"));
        assertEquals("soweto", b.parameters().get("area"));
    }

    @Test
    void comparisonRestaurantListAndMenuIntents() {
        ProposedAction c = detect("Compare kota prices near me");
        assertEquals(ActionType.COMPARE_FOOD, c.intent());
        assertEquals("kota", c.parameters().get("q"));

        ProposedAction r = detect("Find restaurants in Soweto");
        assertEquals(ActionType.SEARCH_RESTAURANTS, r.intent());
        assertEquals("soweto", r.parameters().get("area"));
        assertNull(r.parameters().get("q"));

        ProposedAction m = detect("Show me the menu at Gogo's Kitchen");
        assertEquals(ActionType.VIEW_MENU, m.intent());
        assertEquals("gogo's kitchen", m.parameters().get("restaurant"));

        assertEquals(ActionType.SEARCH_FOOD, detect("Show me restaurants selling beef stew").intent());
    }

    @Test
    void addToCartNamesTheDishAndRestaurantAndNeedsConfirmation() {
        ProposedAction a = detect("Add the beef stew from Gogo's Kitchen to my cart");
        assertEquals(ActionType.ADD_FOOD_TO_CART, a.intent());
        assertEquals("beef stew", a.parameters().get("q"));
        assertEquals("gogo's kitchen", a.parameters().get("restaurant"));
        assertTrue(a.requiresConfirmation());
    }

    @Test
    void nonFoodMessagesAreLeftAlone() {
        assertTrue(service.detect("Contribute R100 to the circle for chicken").isEmpty());
        assertTrue(service.detect("I need a taxi to the rank").isEmpty());
        assertTrue(service.detect("What is my budget for the month?").isEmpty());
        assertTrue(service.detect("Add a goal for a new fridge").isEmpty());
        assertTrue(service.detect(null).isEmpty());
        assertTrue(service.detect("hello").isEmpty());
    }

    @Test
    void thereIsNoOrderOrPayAction() {
        for (ActionType t : ActionType.values()) {
            String n = t.name();
            assertFalse(n.contains("CREATE_FOOD") || n.contains("ORDER_FOOD") || n.contains("PAY_FOOD") || n.contains("BUY_FOOD"), n);
        }
        assertTrue(FoodAssistantService.isReadOnlyFood(ActionType.SEARCH_FOOD));
        assertFalse(FoodAssistantService.isReadOnlyFood(ActionType.ADD_FOOD_TO_CART));
        assertTrue(FoodAssistantService.isFoodAction(ActionType.ADD_FOOD_TO_CART));
    }

    // ------------------------------------------------------------------ fixtures

    private FoodOfferResponse offer(String productId, String name, String business, String price, BigDecimal itemRating,
                                    int itemCount, String level, List<String> reasons, Double km) {
        return new FoodOfferResponse(productId, name, null, null, "b-" + business, business, BusinessType.HOME_KITCHEN,
                null, null, "Soweto", "addr", new BigDecimal(price), "ZAR", "STEW", "BEEF", "PAP", "REGULAR", null,
                Set.of("SPINACH"), Set.of(), Set.of(), false, itemRating, itemCount, null, 0, true, true,
                new BigDecimal("20.00"), 35, km, true, null, List.of(), null, level, reasons, false);
    }

    private FoodSearchResponse found(List<FoodOfferResponse> offers, String... interpreted) {
        return new FoodSearchResponse(offers, offers.size(), 0, 5, List.of(interpreted), 0);
    }

    private ProposedAction action(ActionType type, Map<String, Object> params) {
        return new ProposedAction(null, type, "x", params, 0.6, type == ActionType.ADD_FOOD_TO_CART);
    }

    // ------------------------------------------------------------------ read-only answers

    @Test
    void searchAnswerUsesOnlyPulaDataAndDeclaresNoWinner() {
        when(discovery.search(any())).thenReturn(found(List.of(
                offer("p1", "Beef Stew + Pap", "Gogo's Kitchen", "65.00", new BigDecimal("4.7"), 64, "EXACT_MATCH", List.of("Same dish"), 1.2),
                offer("p2", "Beef & Pap", "Kasi Kitchen", "60.00", BigDecimal.ZERO, 0, "SIMILAR_MEAL", List.of("Also includes spinach"), null)),
                "Beef Stew + Pap"));

        AssistantResponse r = service.answer(action(ActionType.SEARCH_FOOD, Map.of("q", "beef stew pap")), 
                -26.2, 28.0, "heuristic");

        assertNull(r.action());
        String t = r.reply();
        assertTrue(t.contains("Gogo's Kitchen") && t.contains("R65"));
        assertTrue(t.contains("rated 4.7 (64 reviews)"));
        assertTrue(t.contains("no reviews yet"), "an unrated dish must say so, not invent a rating");
        assertTrue(t.contains("exact match") && t.contains("similar meal"));
        assertTrue(t.contains("1.2 km away"));
        assertTrue(t.contains("I don't pick a winner"));
        assertFalse(t.toLowerCase().contains("cheapest") || t.toLowerCase().contains("best choice"));
    }

    @Test
    void noResultsIsSaidPlainlyNotInvented() {
        when(discovery.search(any())).thenReturn(found(List.of()));
        String t = service.answer(action(ActionType.SEARCH_FOOD, Map.of("q", "sushi", "maxPrice", new BigDecimal("30"))),
                null, null, "heuristic").reply();
        assertTrue(t.contains("couldn't find \"sushi\" under R30"));
    }

    @Test
    void nearMeWithoutLocationAsksForOneAndNeverGuesses() {
        when(discovery.search(any())).thenReturn(found(List.of()));
        String noLoc = service.answer(action(ActionType.SEARCH_FOOD, Map.of("q", "kota", "nearMe", true)), null, null, "heuristic").reply();
        assertTrue(noLoc.contains("don't have your location"));
        String withLoc = service.answer(action(ActionType.SEARCH_FOOD, Map.of("q", "kota", "nearMe", true)), -26.2, 28.0, "heuristic").reply();
        assertFalse(withLoc.contains("don't have your location"));
        String withArea = service.answer(action(ActionType.SEARCH_FOOD, Map.of("q", "kota", "nearMe", true, "area", "Soweto")), null, null, "heuristic").reply();
        assertFalse(withArea.contains("don't have your location"));
    }

    @Test
    void comparisonGroupsExactSimilarAndAlternativeAndSaysNoWinner() {
        CanonicalProduct stew = new CanonicalProduct();
        stew.setId("c1");
        stew.setName("Beef Stew + Pap");
        when(matching.resolveCanonicalFoods("beef pap")).thenReturn(List.of(stew));
        when(discovery.compareCanonical(anyString(), any())).thenReturn(new FoodComparisonResponse("Beef Stew + Pap", "c1", null,
                List.of(offer("p1", "Beef & Pap", "Kasi Kitchen", "60.00", BigDecimal.ZERO, 0, "EXACT_MATCH", List.of("Same dish"), null)),
                List.of(offer("p2", "Beef Stew, Pap & Spinach", "Mama's Place", "75.00", BigDecimal.ZERO, 0, "SIMILAR_MEAL", List.of("Also includes spinach"), null)),
                List.of(offer("p3", "Beef burger", "Burger Joint", "55.00", BigDecimal.ZERO, 0, "ALTERNATIVE", List.of("A different style of dish"), null)),
                0, FoodDiscoveryService.NO_WINNER_NOTE));

        String t = service.answer(action(ActionType.COMPARE_FOOD, Map.of("q", "beef pap")), null, null, "ai").reply();

        assertTrue(t.contains("Exact matches:") && t.contains("Similar meals:") && t.contains("Alternatives:"));
        assertTrue(t.contains("does not pick a winner"));
        assertTrue(t.contains("R60") && t.contains("R75") && t.contains("R55"));
    }

    @Test
    void comparingSomethingWithNoStandardMealSaysItCannotCompareLikeForLike() {
        when(matching.resolveCanonicalFoods("mystery")).thenReturn(List.of());
        when(discovery.search(any())).thenReturn(found(List.of()));
        String t = service.answer(action(ActionType.COMPARE_FOOD, Map.of("q", "mystery")), null, null, "ai").reply();
        assertTrue(t.contains("can't compare like for like"));
        assertTrue(service.answer(action(ActionType.COMPARE_FOOD, Map.of()), null, null, "ai").reply().contains("Which meal"));
    }

    @Test
    void menuAnswerListsItemsAndAsksWhichRestaurantWhenAmbiguous() {
        RestaurantSummary gogo = new RestaurantSummary("b1", "Gogo's Kitchen", BusinessType.HOME_KITCHEN, null, null, "Soweto", 0,
                BigDecimal.ZERO, 0, true, false, null, null, null, null, true, null);
        RestaurantSummary gogo2 = new RestaurantSummary("b2", "Gogo's Grill", BusinessType.RESTAURANT, null, null, "Pretoria", 0,
                BigDecimal.ZERO, 0, true, false, null, null, null, null, true, null);
        MenuItemView item = new MenuItemView("p1", "Chicken kota", null, null, new BigDecimal("45.00"), "ZAR", "KOTA", "CHICKEN",
                "CHIPS", "REGULAR", null, Set.of(), Set.of(), Set.of(), null, null, BigDecimal.ZERO, 0, true, null, null, List.of());
        when(discovery.menu("b1", null)).thenReturn(new MenuResponse("b1", "Gogo's Kitchen", null, null,
                List.of(new MenuCategoryView("c1", "Kota", List.of(item)))));

        when(discovery.restaurants(any())).thenReturn(new RestaurantListResponse(List.of(gogo, gogo2), 2, 0, 10, 0));
        String ask = service.answer(action(ActionType.VIEW_MENU, Map.of("restaurant", "gogo")), null, null, "ai").reply();
        assertTrue(ask.contains("Which one do you mean?"));

        when(discovery.restaurants(any())).thenReturn(new RestaurantListResponse(List.of(gogo), 1, 0, 10, 0));
        String menu = service.answer(action(ActionType.VIEW_MENU, Map.of("restaurant", "Gogo's Kitchen")), null, null, "ai").reply();
        assertTrue(menu.contains("Menu at Gogo's Kitchen") && menu.contains("Chicken kota") && menu.contains("R45"));

        when(discovery.restaurants(any())).thenReturn(new RestaurantListResponse(List.of(), 0, 0, 10, 0));
        assertTrue(service.answer(action(ActionType.VIEW_MENU, Map.of("restaurant", "Nowhere")), null, null, "ai").reply()
                .contains("couldn't find a restaurant"));
    }

    // ------------------------------------------------------------------ add to cart

    private Product product(String id, String name, BusinessType type, String price) {
        Business b = new Business();
        b.setId("biz-" + id);
        b.setName("Gogo's Kitchen");
        b.setBusinessType(type);
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        p.setCurrency("ZAR");
        p.setStatus(ProductStatus.ACTIVE);
        p.setBusiness(b);
        when(products.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void aUniqueMatchBecomesAConfirmedProposalWithTheServerChosenProductId() {
        product("p-real", "Beef Stew + Pap", BusinessType.HOME_KITCHEN, "65.00");
        when(discovery.search(any())).thenReturn(found(List.of(
                offer("p-real", "Beef Stew + Pap", "Gogo's Kitchen", "65.00", BigDecimal.ZERO, 0, null, List.of(), null))));

        // the model/heuristic tries to smuggle in its own productId, a price and a branch
        Map<String, Object> params = new java.util.HashMap<>(Map.of("q", "beef stew", "restaurant", "Gogo's Kitchen", "quantity", 2));
        params.put("productId", "p-evil");
        params.put("price", new BigDecimal("1.00"));
        AssistantResponse r = service.proposeAdd(user, action(ActionType.ADD_FOOD_TO_CART, params), null, "ai");

        ProposedAction a = r.action();
        assertNotNull(a);
        assertTrue(a.requiresConfirmation());
        assertEquals("p-real", a.parameters().get("productId"));
        assertEquals(2, a.parameters().get("quantity"));
        assertEquals(new BigDecimal("65.00"), a.parameters().get("price")); // PULA's price, not the smuggled one
        assertFalse(a.parameters().containsKey("lat") || a.parameters().containsKey("lng"));
        assertTrue(r.reply().contains("Confirm below") && r.reply().contains("I won't order or pay for you"));
    }

    @Test
    void severalMatchesAreAQuestionNotAGuess() {
        when(discovery.search(any())).thenReturn(found(List.of(
                offer("p1", "Beef Stew + Pap", "Gogo's Kitchen", "65.00", BigDecimal.ZERO, 0, null, List.of(), null),
                offer("p2", "Beef & Pap", "Kasi Kitchen", "60.00", BigDecimal.ZERO, 0, null, List.of(), null))));
        AssistantResponse r = service.proposeAdd(user, action(ActionType.ADD_FOOD_TO_CART, Map.of("q", "beef pap")), null, "ai");
        assertNull(r.action());
        assertTrue(r.reply().contains("Tell me which one") && r.reply().contains("Kasi Kitchen"));
    }

    @Test
    void theRestaurantNameNarrowsTheChoiceToOne() {
        product("p1", "Beef Stew + Pap", BusinessType.HOME_KITCHEN, "65.00");
        when(discovery.search(any())).thenReturn(found(List.of(
                offer("p1", "Beef Stew + Pap", "Gogo's Kitchen", "65.00", BigDecimal.ZERO, 0, null, List.of(), null),
                offer("p2", "Beef & Pap", "Kasi Kitchen", "60.00", BigDecimal.ZERO, 0, null, List.of(), null))));
        AssistantResponse r = service.proposeAdd(user, action(ActionType.ADD_FOOD_TO_CART,
                Map.of("q", "beef", "restaurant", "Gogo's Kitchen")), null, "ai");
        assertEquals("p1", r.action().parameters().get("productId"));
    }

    @Test
    void nothingFoundMeansNoProposal() {
        when(discovery.search(any())).thenReturn(found(List.of()));
        AssistantResponse r = service.proposeAdd(user, action(ActionType.ADD_FOOD_TO_CART, Map.of("q", "sushi")), null, "ai");
        assertNull(r.action());
        assertTrue(r.reply().contains("couldn't find an available"));
    }

    @Test
    void aDishThatNeedsRequiredChoicesIsNotAddedBlindly() {
        Product p = product("p1", "Combo", BusinessType.RESTAURANT, "80.00");
        when(discovery.search(any())).thenReturn(found(List.of(
                offer("p1", "Combo", "Gogo's Kitchen", "80.00", BigDecimal.ZERO, 0, null, List.of(), null))));
        doThrow(ApiException.badRequest("Choose at least 1 from \"Sides\"")).when(foodCheckout).resolveModifiers(p, List.of());
        AssistantResponse r = service.proposeAdd(user, action(ActionType.ADD_FOOD_TO_CART, Map.of("q", "combo")), null, "ai");
        assertNull(r.action());
        assertTrue(r.reply().contains("needs you to choose some options"));
    }

    @Test
    void thePageContextItemIsUsedOnlyWhenTheMessageNamesNothing() {
        product("p-page", "Chicken kota", BusinessType.FAST_FOOD, "45.00");
        AssistantResponse r = service.proposeAdd(user, action(ActionType.ADD_FOOD_TO_CART, Map.of()), "p-page", "heuristic");
        assertEquals("p-page", r.action().parameters().get("productId"));

        AssistantResponse none = service.proposeAdd(user, action(ActionType.ADD_FOOD_TO_CART, Map.of()), null, "heuristic");
        assertNull(none.action());
        assertTrue(none.reply().contains("Which dish"));
    }

    @Test
    void nonFoodItemsAreRefusedAndQuantityIsClamped() {
        product("p-hw", "Hammer", BusinessType.FORMAL_RETAILER, "99.00");
        AssistantResponse r = service.proposeAdd(user, action(ActionType.ADD_FOOD_TO_CART, Map.of()), "p-hw", "ai");
        assertNull(r.action());

        product("p-k", "Kota", BusinessType.FAST_FOOD, "40.00");
        AssistantResponse big = service.proposeAdd(user, action(ActionType.ADD_FOOD_TO_CART, Map.of("quantity", 99)), "p-k", "ai");
        assertEquals(20, big.action().parameters().get("quantity"));
        AssistantResponse neg = service.proposeAdd(user, action(ActionType.ADD_FOOD_TO_CART, Map.of("quantity", -3)), "p-k", "ai");
        assertEquals(1, neg.action().parameters().get("quantity"));
    }

    @Test
    void moneyFormatting() {
        assertEquals("R65", FoodAssistantService.money(new BigDecimal("65.00"), "ZAR"));
        assertEquals("R17.50", FoodAssistantService.money(new BigDecimal("17.5"), "ZAR"));
        assertEquals("USD 3", FoodAssistantService.money(new BigDecimal("3"), "USD"));
    }
}
