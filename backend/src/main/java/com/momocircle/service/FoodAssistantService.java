package com.momocircle.service;

import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.dto.AssistantDtos.ActionType;
import com.momocircle.dto.AssistantDtos.AssistantResponse;
import com.momocircle.dto.AssistantDtos.ProposedAction;
import com.momocircle.dto.FoodDiscoveryDtos.*;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * PULA AI for PULA Food. The assistant never invents food data: every answer is composed on the
 * server from the same discovery service the app screens use, so names, prices, ratings and
 * availability are PULA's own. It does not rank a winner and does not buy anything.
 *
 * Read-only intents (SEARCH_FOOD, SEARCH_RESTAURANTS, COMPARE_FOOD, VIEW_MENU) are answered as text.
 * ADD_FOOD_TO_CART resolves the item on the server (a model-supplied productId is ignored), asks
 * the customer to confirm, and when confirmed only adds it to the cart. Choosing collection or
 * delivery, reviewing the total and paying remain the normal checkout. There is deliberately no
 * "create order" action. Customer coordinates are used for distance and never stored in a proposal.
 */
@Service
public class FoodAssistantService {

    private static final Set<ActionType> READ_ONLY = EnumSet.of(
            ActionType.SEARCH_FOOD, ActionType.SEARCH_RESTAURANTS, ActionType.COMPARE_FOOD, ActionType.VIEW_MENU);
    private static final int MAX_LINES = 5;

    private static final Set<String> FOOD_WORDS = Set.of("pap", "stew", "kota", "kotas", "chicken", "beef", "burger",
            "burgers", "chips", "wors", "boerewors", "vetkoek", "bunny", "gatsby", "rice", "fish", "pizza", "sandwich",
            "salad", "spinach", "meal", "meals", "food", "takeaway", "lunch", "dinner", "breakfast", "restaurant",
            "restaurants", "kitchen", "cafe", "café", "eat", "hungry", "atchar", "samp", "mogodu", "tripe", "pork",
            "lamb", "curry", "braai");
    private static final Set<String> NOT_FOOD_INTENTS = Set.of("contribute", "contribution", "circle", "taxi",
            "payment request", "goal", "budget", "loan", "credit", "scam", "recurring", "member", "stokvel");
    private static final Set<String> TRIGGERS = Set.of("find", "show", "search", "where", "looking", "want", "get me",
            "hungry", "order", "need", "can i get", "compare", "menu", "add", "buy", "list", "any ");
    /** Words dropped when turning a sentence into a meal query; food words are kept. */
    private static final Set<String> FILLER = Set.of("i", "me", "my", "you", "can", "could", "would", "like", "want",
            "to", "for", "some", "any", "get", "find", "show", "search", "looking", "where", "is", "are", "there", "near",
            "nearby", "around", "please", "the", "a", "an", "and", "with", "of", "in", "at", "from", "on", "under", "below",
            "less", "than", "cheaper", "cheap", "cheapest", "best", "good", "compare", "prices", "price", "add", "cart",
            "basket", "order", "buy", "menu", "list", "us", "it", "that", "this", "r", "rand", "rands", "max", "up", "close",
            "need", "have", "what", "whats", "does", "do", "sell", "selling", "serve", "serving", "available", "today", "now");
    private static final Set<String> GENERIC = Set.of("food", "meal", "meals", "restaurant", "restaurants", "place",
            "places", "eat", "hungry", "takeaway", "lunch", "dinner", "kitchen", "cafe", "café");

    private static final Pattern PRICE = Pattern.compile("(?:under|below|less than|cheaper than|max(?:imum)?|up to)\\s*r?\\s*(\\d+(?:[.,]\\d{1,2})?)");
    private static final Pattern AREA = Pattern.compile("\\b(?:near|in|around)\\s+(?!me\\b|my\\b|the\\b)([a-z][a-z' -]{2,30}?)(?=\\s+(?:under|below|for|with|please|that|which)\\b|[.?!,]|$)");
    private static final Pattern RESTAURANT = Pattern.compile("\\b(?:from|at)\\s+(.+?)(?=\\s+(?:to\\s+(?:my\\s+)?(?:cart|basket)|under|below|near|please)\\b|[.?!,]|$)");
    private static final Pattern QUANTITY = Pattern.compile("\\b(\\d{1,2})\\s*(?:x|×)?\\s+(?=[a-z])");

    private final FoodDiscoveryService discovery;
    private final FoodMatchingService matching;
    private final FoodCheckoutService foodCheckout;
    private final ProductRepository productRepository;

    public FoodAssistantService(FoodDiscoveryService discovery, FoodMatchingService matching,
                                FoodCheckoutService foodCheckout, ProductRepository productRepository) {
        this.discovery = discovery;
        this.matching = matching;
        this.foodCheckout = foodCheckout;
        this.productRepository = productRepository;
    }

    public static boolean isReadOnlyFood(ActionType t) {
        return READ_ONLY.contains(t);
    }

    public static boolean isFoodAction(ActionType t) {
        return READ_ONLY.contains(t) || t == ActionType.ADD_FOOD_TO_CART;
    }

    // ------------------------------------------------------------------ detection (no-AI fallback)

    /** Recognises an obvious food request. Returns empty for anything that isn't clearly about food. */
    public Optional<ProposedAction> detect(String message) {
        if (message == null) return Optional.empty();
        String text = message.toLowerCase(Locale.ROOT);
        for (String bad : NOT_FOOD_INTENTS) if (text.contains(bad)) return Optional.empty();
        Set<String> words = new java.util.HashSet<>(List.of(text.replaceAll("[^a-zé' ]", " ").split("\\s+")));
        boolean foodWord = words.stream().anyMatch(FOOD_WORDS::contains);
        if (!foodWord) return Optional.empty();
        boolean trigger = TRIGGERS.stream().anyMatch(text::contains);
        if (!trigger) return Optional.empty();

        Map<String, Object> params = extractParams(text);
        ActionType type;
        if (text.contains("menu")) {
            type = ActionType.VIEW_MENU;
        } else if (text.contains("compare")) {
            type = ActionType.COMPARE_FOOD;
        } else if (text.contains("cart") || text.contains("basket") || text.startsWith("order ") || text.contains("i want to order")
                || text.startsWith("buy ") || text.contains("i'll take") || text.contains("add the ") || text.contains("add a ")) {
            type = ActionType.ADD_FOOD_TO_CART;
        } else if ((words.contains("restaurant") || words.contains("restaurants")) && params.get("q") == null) {
            type = ActionType.SEARCH_RESTAURANTS;
        } else {
            type = ActionType.SEARCH_FOOD;
        }
        String summary = switch (type) {
            case VIEW_MENU -> "Show a menu";
            case COMPARE_FOOD -> "Compare food";
            case ADD_FOOD_TO_CART -> "Add food to your cart";
            case SEARCH_RESTAURANTS -> "Find restaurants";
            default -> "Find food";
        };
        return Optional.of(new ProposedAction(null, type, summary, params, 0.6, type == ActionType.ADD_FOOD_TO_CART));
    }

    static Map<String, Object> extractParams(String text) {
        Map<String, Object> params = new LinkedHashMap<>();
        String rest = text;

        Matcher pm = PRICE.matcher(rest);
        if (pm.find()) {
            params.put("maxPrice", new BigDecimal(pm.group(1).replace(',', '.')));
            rest = rest.replace(pm.group(0), " ");
        }
        boolean nearMe = rest.contains("near me") || rest.contains("close to me") || rest.contains("nearby") || rest.contains("around me");
        if (nearMe) params.put("nearMe", true);
        Matcher am = AREA.matcher(rest);
        if (am.find()) {
            params.put("area", am.group(1).trim());
            rest = rest.replace(am.group(0), " ");
        }
        if (text.contains("menu") || text.contains("add ") || text.contains("order ") || text.contains("buy ")) {
            Matcher rm = RESTAURANT.matcher(rest);
            if (rm.find()) {
                params.put("restaurant", rm.group(1).trim());
                rest = rest.replace(rm.group(0), " ");
            }
        }
        Matcher qm = QUANTITY.matcher(rest);
        if (qm.find()) {
            params.put("quantity", Integer.parseInt(qm.group(1)));
            rest = rest.replace(qm.group(0), " ");
        }
        List<String> kept = new ArrayList<>();
        for (String w : rest.replaceAll("[^a-zé0-9' ]", " ").split("\\s+")) {
            if (w.isEmpty() || FILLER.contains(w) || GENERIC.contains(w) || w.matches("\\d+")) continue;
            kept.add(w);
        }
        if (!kept.isEmpty()) params.put("q", String.join(" ", kept));
        return params;
    }

    // ------------------------------------------------------------------ read-only answers

    public AssistantResponse answer(ProposedAction action, Double lat, Double lng, String source) {
        Map<String, Object> p = action.parameters() == null ? Map.of() : action.parameters();
        String q = str(p.get("q"));
        String area = str(p.get("area"));
        BigDecimal maxPrice = decimal(p.get("maxPrice"));
        boolean nearMe = Boolean.TRUE.equals(p.get("nearMe")) || "true".equalsIgnoreCase(String.valueOf(p.get("nearMe")));
        boolean located = lat != null && lng != null;
        String locationNote = nearMe && !located && area == null
                ? " I don't have your location, so I can't sort by distance. Share your location, or tell me an area such as \"near Soweto\"."
                : "";
        String reply = switch (action.intent()) {
            case SEARCH_RESTAURANTS -> restaurantsReply(q, area, lat, lng, nearMe, located);
            case VIEW_MENU -> menuReply(str(p.get("restaurant")) != null ? str(p.get("restaurant")) : q);
            case COMPARE_FOOD -> compareReply(q, area, maxPrice, lat, lng);
            default -> searchReply(q, area, maxPrice, lat, lng, located);
        };
        return new AssistantResponse(reply + locationNote, null, source);
    }

    private String searchReply(String q, String area, BigDecimal maxPrice, Double lat, Double lng, boolean located) {
        FoodSearchCriteria c = new FoodSearchCriteria(q, maxPrice, null, null, null, null, area, null, null, lat, lng,
                null, false, located ? FoodSort.DISTANCE : FoodSort.RELEVANCE, 0, MAX_LINES);
        FoodSearchResponse r = discovery.search(c);
        if (r.offers().isEmpty()) {
            return "I couldn't find " + describe(q, maxPrice, area) + " on PULA right now. "
                    + "Try a different meal, a higher price or another area.";
        }
        StringBuilder sb = new StringBuilder("Here's what I found on PULA for " + describe(q, maxPrice, area) + ":\n");
        if (!r.interpretedAs().isEmpty()) sb.append("(I read that as: ").append(String.join(", ", r.interpretedAs())).append(")\n");
        for (FoodOfferResponse o : r.offers()) sb.append("• ").append(line(o)).append('\n');
        if (r.total() > r.offers().size()) sb.append("Showing ").append(r.offers().size()).append(" of ").append(r.total()).append(". ");
        sb.append("I don't pick a winner. Check the portion, sides, delivery and reviews, then tell me which one to add to your cart.");
        return sb.toString();
    }

    private String compareReply(String q, String area, BigDecimal maxPrice, Double lat, Double lng) {
        if (q == null) return "Which meal would you like me to compare? For example, \"compare kota prices\".";
        List<CanonicalProduct> resolved = matching.resolveCanonicalFoods(q);
        if (resolved.isEmpty()) {
            return "I don't have a standard meal for \"" + q + "\" yet, so I can't compare like for like. "
                    + searchReply(q, area, maxPrice, lat, lng, lat != null && lng != null)
                    + "\nThese are text matches and may not be the same meal.";
        }
        StringBuilder sb = new StringBuilder();
        FoodSearchCriteria c = new FoodSearchCriteria(null, maxPrice, null, null, null, null, area, null, null, lat, lng,
                null, false, lat != null && lng != null ? FoodSort.DISTANCE : FoodSort.PRICE, 0, 50);
        int shown = 0;
        for (CanonicalProduct cp : resolved) {
            if (shown++ == 2) break;
            FoodComparisonResponse r = discovery.compareCanonical(cp.getId(), c);
            sb.append(cp.getName()).append(":\n");
            appendGroup(sb, "Exact matches", r.exactMatches());
            appendGroup(sb, "Similar meals", r.similarMeals());
            appendGroup(sb, "Alternatives", r.alternatives());
            if (r.exactMatches().isEmpty() && r.similarMeals().isEmpty() && r.alternatives().isEmpty()) {
                sb.append("• Nothing available right now.\n");
            }
        }
        sb.append(FoodDiscoveryService.NO_WINNER_NOTE);
        return sb.toString();
    }

    private void appendGroup(StringBuilder sb, String title, List<FoodOfferResponse> offers) {
        if (offers.isEmpty()) return;
        sb.append(title).append(":\n");
        offers.stream().limit(3).forEach(o -> sb.append("• ").append(line(o)).append('\n'));
        if (offers.size() > 3) sb.append("  (+").append(offers.size() - 3).append(" more in the app)\n");
    }

    private String restaurantsReply(String q, String area, Double lat, Double lng, boolean nearMe, boolean located) {
        RestaurantListResponse r = discovery.restaurants(new RestaurantQuery(q, null, area, null, null, lat, lng, null,
                located ? FoodSort.DISTANCE : FoodSort.NAME, 0, MAX_LINES));
        if (r.restaurants().isEmpty()) return "I couldn't find any restaurants" + (area != null ? " in " + area : "") + " on PULA right now.";
        StringBuilder sb = new StringBuilder("Restaurants on PULA" + (area != null ? " in " + area : "") + ":\n");
        for (RestaurantSummary s : r.restaurants()) {
            sb.append("• ").append(s.name());
            if (s.area() != null) sb.append(" (").append(s.area()).append(')');
            sb.append(" — ").append(ratingText(null, 0, s.rating(), s.ratingCount()));
            sb.append(", ").append(fulfilmentText(s.collectionAvailable(), s.deliveryAvailable(), s.deliveryFeeFrom()));
            if (s.nearestDistanceKm() != null) sb.append(", ").append(s.nearestDistanceKm()).append(" km");
            if (!s.open()) sb.append(" [").append(s.closedReason()).append(']');
            sb.append('\n');
        }
        sb.append("Tell me a restaurant and I'll show its menu.");
        return sb.toString();
    }

    private String menuReply(String restaurantName) {
        if (restaurantName == null) return "Which restaurant's menu would you like to see?";
        RestaurantListResponse r = discovery.restaurants(new RestaurantQuery(restaurantName, null, null, null, null, null, null, null, FoodSort.NAME, 0, 10));
        if (r.restaurants().isEmpty()) return "I couldn't find a restaurant called \"" + restaurantName + "\" on PULA.";
        Set<String> wanted = FoodMatchingService.tokens(restaurantName);
        List<RestaurantSummary> exact = r.restaurants().stream()
                .filter(s -> FoodMatchingService.tokens(s.name()).equals(wanted)).toList();
        List<RestaurantSummary> pick = exact.size() == 1 ? exact : r.restaurants();
        if (pick.size() > 1) {
            return "I found more than one match: " + String.join(", ", pick.stream().map(RestaurantSummary::name).toList())
                    + ". Which one do you mean?";
        }
        RestaurantSummary rest = pick.get(0);
        MenuResponse menu = discovery.menu(rest.id(), null);
        StringBuilder sb = new StringBuilder("Menu at " + rest.name() + (rest.open() ? "" : " (" + rest.closedReason() + ")") + ":\n");
        int lines = 0;
        outer:
        for (MenuCategoryView cat : menu.categories()) {
            sb.append(cat.name()).append(":\n");
            for (MenuItemView i : cat.items()) {
                if (lines++ >= 12) { sb.append("…and more in the app.\n"); break outer; }
                sb.append("• ").append(i.name()).append(" — ").append(money(i.price(), i.currency()));
                if (i.portionSize() != null) sb.append(", ").append(i.portionSize().toLowerCase(Locale.ROOT).replace('_', ' ')).append(" portion");
                if (!i.modifierGroups().isEmpty()) sb.append(", extras available");
                if (!i.available()) sb.append(" [").append(i.unavailableReason()).append(']');
                sb.append('\n');
            }
        }
        sb.append("Prices can differ by branch. Say \"add the <dish> from ").append(rest.name()).append(" to my cart\" when you're ready.");
        return sb.toString();
    }

    // ------------------------------------------------------------------ add to cart (needs confirmation)

    /**
     * Resolves what to add ON THE SERVER. A productId coming from the model or the message is never
     * used; only the app's own page context (contextProductId) or a unique, available match in PULA's
     * data is. Anything unclear becomes a question, not a proposal.
     */
    public AssistantResponse proposeAdd(User actor, ProposedAction action, String contextProductId, String source) {
        Map<String, Object> p = action.parameters() == null ? Map.of() : action.parameters();
        String q = str(p.get("q"));
        String restaurant = str(p.get("restaurant"));
        int quantity = clampQuantity(p.get("quantity"));

        Product product = null;
        String restaurantName = null;
        BigDecimal price = null;
        if (q == null && restaurant == null && contextProductId != null && !contextProductId.isBlank()) {
            product = productRepository.findById(contextProductId).orElse(null);
            if (product == null) return new AssistantResponse("I couldn't find that item any more.", null, source);
        } else if (q == null) {
            return new AssistantResponse("Which dish would you like to add? For example, \"add the beef stew from Gogo's Kitchen to my cart\".", null, source);
        } else {
            FoodSearchResponse found = discovery.search(new FoodSearchCriteria(q, null, null, null, null, null, null, null,
                    null, null, null, null, false, FoodSort.RELEVANCE, 0, 50));
            Map<String, FoodOfferResponse> distinct = new LinkedHashMap<>();
            Set<String> wantedRestaurant = FoodMatchingService.tokens(restaurant);
            for (FoodOfferResponse o : found.offers()) {
                if (!wantedRestaurant.isEmpty() && !FoodMatchingService.tokens(o.businessName()).containsAll(wantedRestaurant)) continue;
                distinct.putIfAbsent(o.productId(), o);
            }
            if (distinct.isEmpty()) {
                return new AssistantResponse("I couldn't find an available \"" + q + "\""
                        + (restaurant != null ? " at " + restaurant : "") + " on PULA.", null, source);
            }
            if (distinct.size() > 1) {
                StringBuilder sb = new StringBuilder("I found a few matches. Tell me which one:\n");
                distinct.values().stream().limit(MAX_LINES).forEach(o -> sb.append("• ").append(o.name()).append(" at ")
                        .append(o.businessName()).append(" — ").append(money(o.price(), o.currency())).append('\n'));
                return new AssistantResponse(sb.toString().trim(), null, source);
            }
            FoodOfferResponse only = distinct.values().iterator().next();
            product = productRepository.findById(only.productId()).orElse(null);
            if (product == null) return new AssistantResponse("I couldn't find that item any more.", null, source);
            restaurantName = only.businessName();
            price = only.price();
        }
        if (!FoodCheckoutService.isFoodBusiness(product.getBusiness())) {
            return new AssistantResponse("That isn't a food item. Use the normal add to cart for it.", null, source);
        }
        try {
            foodCheckout.resolveModifiers(product, List.of()); // throws if a required choice (e.g. a side) is missing
        } catch (ApiException e) {
            return new AssistantResponse("\"" + product.getName() + "\" needs you to choose some options first, so I can't add it for you. "
                    + "Open it from the menu to pick them.", null, source);
        }
        if (restaurantName == null) restaurantName = product.getBusiness().getName();
        if (price == null) price = product.getPrice();

        Map<String, Object> params = new HashMap<>();
        params.put("productId", product.getId());           // set here, from PULA data only
        params.put("quantity", quantity);
        params.put("itemName", product.getName());           // display only
        params.put("restaurantName", restaurantName);        // display only
        params.put("price", price);                          // display only
        params.put("currency", product.getCurrency());       // display only
        String reply = "I can add " + quantity + " × " + product.getName() + " from " + restaurantName + " ("
                + money(price, product.getCurrency()) + " each) to your cart. Confirm below. "
                + "You'll choose collection or delivery, see the full total and pay at checkout. I won't order or pay for you.";
        return new AssistantResponse(reply, new ProposedAction(null, ActionType.ADD_FOOD_TO_CART,
                "Add " + quantity + " × " + product.getName() + " to your cart", params, 0.7, true), source);
    }

    // ------------------------------------------------------------------ formatting

    private String line(FoodOfferResponse o) {
        StringBuilder sb = new StringBuilder();
        sb.append(o.businessName());
        if (o.branchName() != null) sb.append(" (").append(o.branchName()).append(')');
        sb.append(" — ").append(o.name()).append(' ').append(money(o.price(), o.currency()));
        if (o.portionSize() != null) sb.append(", ").append(o.portionSize().toLowerCase(Locale.ROOT).replace('_', ' ')).append(" portion");
        if (!o.sides().isEmpty()) sb.append(", with ").append(String.join(", ", o.sides()).toLowerCase(Locale.ROOT).replace('_', ' '));
        sb.append(", ").append(ratingText(o.itemRating(), o.itemRatingCount(), o.restaurantRating(), o.restaurantRatingCount()));
        sb.append(", ").append(fulfilmentText(o.collectionAvailable(), o.deliveryAvailable(), o.deliveryFee()));
        if (o.prepMinutes() != null) sb.append(", about ").append(o.prepMinutes()).append(" min to prepare");
        if (o.distanceKm() != null) sb.append(", ").append(o.distanceKm()).append(" km away");
        if (o.matchLevel() != null) {
            String why = o.matchReasons().isEmpty() ? "" : ": " + o.matchReasons().get(0);
            sb.append(" [").append(switch (o.matchLevel()) {
                case "EXACT_MATCH" -> "exact match";
                case "SIMILAR_MEAL" -> "similar meal" + why;
                default -> "alternative" + why;
            }).append(o.matchUncertain() ? "; some details not stated" : "").append(']');
        }
        return sb.toString();
    }

    private static String ratingText(BigDecimal itemRating, int itemCount, BigDecimal restRating, int restCount) {
        if (itemCount > 0) return "rated " + itemRating + " (" + itemCount + " reviews)";
        if (restCount > 0) return "restaurant rated " + restRating + " (" + restCount + " reviews)";
        return "no reviews yet";
    }

    private static String fulfilmentText(boolean collection, boolean delivery, BigDecimal fee) {
        if (collection && delivery) return "collection or delivery from " + money(fee, "ZAR");
        if (delivery) return "delivery from " + money(fee, "ZAR");
        if (collection) return "collection only";
        return "ask the restaurant about collection or delivery";
    }

    static String money(BigDecimal v, String currency) {
        if (v == null) return "—";
        String symbol = "ZAR".equals(currency) ? "R" : currency + " ";
        BigDecimal whole = v.setScale(0, java.math.RoundingMode.DOWN);
        return symbol + (v.compareTo(whole) == 0 ? whole.toPlainString() : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
    }

    private static String describe(String q, BigDecimal maxPrice, String area) {
        StringBuilder sb = new StringBuilder(q == null ? "food" : "\"" + q + "\"");
        if (maxPrice != null) sb.append(" under ").append(money(maxPrice, "ZAR"));
        if (area != null) sb.append(" in ").append(area);
        return sb.toString();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) return null;
        try {
            BigDecimal v = new BigDecimal(String.valueOf(o));
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clampQuantity(Object o) {
        try {
            return Math.max(1, Math.min(20, o == null ? 1 : new BigDecimal(String.valueOf(o)).intValue()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
