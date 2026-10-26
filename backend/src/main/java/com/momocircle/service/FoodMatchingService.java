package com.momocircle.service;

import com.momocircle.domain.CanonicalFoodAlias;
import com.momocircle.domain.CanonicalFoodAttributes;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.Product;
import com.momocircle.domain.ProductFoodDetails;
import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.repository.CanonicalFoodAliasRepository;
import com.momocircle.repository.CanonicalFoodAttributesRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PULA Food — decides whether two meals are the same, similar, or only alternatives. Deliberately
 * conservative and explainable:
 *   - an explicit canonical link comes first, but is downgraded if the structured attributes
 *     contradict it;
 *   - otherwise comparison uses structured attributes (dish style, protein, starch, sides,
 *     portion), never the name alone;
 *   - unknown attributes are never treated as matching: they lower the level and are reported as
 *     uncertainty;
 *   - name similarity is used ONLY to suggest canonical foods to a merchant, never to link.
 * Beef burger + chips and beef stew + pap are different dish styles, so they are at most
 * "alternatives" (same protein) and are kept in a separate list by callers.
 */
@Service
public class FoodMatchingService {

    public enum MatchLevel { EXACT_MATCH, SIMILAR_MEAL, ALTERNATIVE }

    /** What we know about a meal. Any attribute may be null (= not stated). */
    public record FoodProfile(String canonicalId, String name, String foodType, String protein, String starch,
                              String portionClass, Set<String> sides, MatchClassification linkClass) {}

    /** level == null means "not a comparable meal at all". */
    public record MatchResult(MatchLevel level, List<String> reasons, boolean uncertain) {}

    public record Suggestion(String canonicalId, String name, double score) {}

    private static final Set<String> STOP_WORDS = Set.of("and", "with", "w", "n", "a", "the", "plus", "served", "side", "of");

    private final CanonicalFoodAttributesRepository attributesRepository;
    private final CanonicalFoodAliasRepository aliasRepository;

    public FoodMatchingService(CanonicalFoodAttributesRepository attributesRepository,
                               CanonicalFoodAliasRepository aliasRepository) {
        this.attributesRepository = attributesRepository;
        this.aliasRepository = aliasRepository;
    }

    // ------------------------------------------------------------------ text normalisation

    /** Lower-case word tokens with joiners ("&", "+", "with", "and") and filler removed. */
    public static Set<String> tokens(String raw) {
        Set<String> out = new TreeSet<>();
        if (raw == null) return out;
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[\\u2019']", "").replaceAll("[^a-z0-9]+", " ").trim();
        for (String t : s.split("\\s+")) {
            if (!t.isEmpty() && !STOP_WORDS.contains(t)) out.add(t);
        }
        return out;
    }

    /** Order-independent normal form used to store and compare aliases. */
    public static String normalise(String raw) {
        return String.join(" ", tokens(raw));
    }

    /** Upper-case, underscore token as stored on food details ("beef stew" -> BEEF_STEW); null if blank. */
    public static String token(String raw) {
        if (raw == null || raw.isBlank()) return null;
        return raw.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    }

    /** Maps common portion words onto a small fixed set so "standard" and "regular" compare equal. */
    public static String portionClass(String raw) {
        String t = token(raw);
        if (t == null) return null;
        return switch (t) {
            case "SMALL", "MINI", "KIDS" -> "SMALL";
            case "REGULAR", "STANDARD", "MEDIUM", "NORMAL" -> "REGULAR";
            case "LARGE", "BIG", "XL", "FAMILY", "EXTRA_LARGE" -> "LARGE";
            default -> t;
        };
    }

    public static String signature(Set<String> sides) {
        if (sides == null || sides.isEmpty()) return "";
        Set<String> sorted = new TreeSet<>();
        for (String s : sides) { String t = token(s); if (t != null) sorted.add(t); }
        return String.join(",", sorted);
    }

    private static Set<String> sidesOf(String signature) {
        Set<String> out = new TreeSet<>();
        if (signature != null && !signature.isBlank()) out.addAll(List.of(signature.split(",")));
        return out;
    }

    private static String pretty(String token) {
        return token == null ? "not stated" : token.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    // ------------------------------------------------------------------ profiles

    public FoodProfile profileOf(CanonicalProduct c, CanonicalFoodAttributes a) {
        if (a == null) return new FoodProfile(c.getId(), c.getName(), null, null, null, null, Set.of(), null);
        return new FoodProfile(c.getId(), c.getName(), a.getFoodType(), a.getProtein(), a.getStarch(),
                portionClass(a.getPortionClass()), sidesOf(a.getSidesSignature()), null);
    }

    public FoodProfile profileOf(Product p, ProductFoodDetails d) {
        String canonicalId = p.getCanonicalProduct() == null ? null : p.getCanonicalProduct().getId();
        if (d == null) {
            return new FoodProfile(canonicalId, p.getName(), null, null, null, null, Set.of(), p.getMatchClassification());
        }
        return new FoodProfile(canonicalId, p.getName(), d.getFoodType(), d.getProtein(), d.getStarch(),
                portionClass(d.getPortionSize()), sidesOf(signature(d.getSides())), p.getMatchClassification());
    }

    // ------------------------------------------------------------------ comparison

    public MatchResult match(FoodProfile ref, FoodProfile cand) {
        MatchResult byAttributes = matchByAttributes(ref, cand);
        boolean sameCanonical = ref.canonicalId() != null && ref.canonicalId().equals(cand.canonicalId());
        if (!sameCanonical) return byAttributes;

        // Explicit mapping by the restaurant/admin. Trusted unless the attributes contradict it.
        List<String> reasons = new ArrayList<>();
        reasons.add("Linked to the same meal by the restaurant");
        MatchLevel level = cand.linkClass() == MatchClassification.EXACT_MATCH ? MatchLevel.EXACT_MATCH : MatchLevel.SIMILAR_MEAL;
        boolean uncertain = byAttributes.uncertain();
        if (byAttributes.level() == null || byAttributes.level() == MatchLevel.ALTERNATIVE) {
            level = MatchLevel.SIMILAR_MEAL;
            reasons.add("Its details differ from the standard meal, so it is shown as similar, not identical");
            reasons.addAll(byAttributes.reasons());
        } else if (byAttributes.level() == MatchLevel.SIMILAR_MEAL) {
            level = MatchLevel.SIMILAR_MEAL;
            reasons.addAll(byAttributes.reasons());
        }
        return new MatchResult(level, reasons, uncertain);
    }

    private MatchResult matchByAttributes(FoodProfile ref, FoodProfile cand) {
        List<String> reasons = new ArrayList<>();
        boolean uncertain = false;

        boolean typeKnown = ref.foodType() != null && cand.foodType() != null;
        boolean typeSame = typeKnown && ref.foodType().equals(cand.foodType());
        boolean proteinKnown = ref.protein() != null && cand.protein() != null;
        boolean proteinSame = proteinKnown && ref.protein().equals(cand.protein());
        boolean starchKnown = ref.starch() != null && cand.starch() != null;
        boolean starchSame = starchKnown && ref.starch().equals(cand.starch());

        if (typeKnown && !typeSame) {
            if (!proteinSame) return new MatchResult(null, List.of(), false);
            reasons.add("A different style of dish (" + pretty(cand.foodType()) + " instead of " + pretty(ref.foodType())
                    + ") with the same protein");
            return new MatchResult(MatchLevel.ALTERNATIVE, reasons, false);
        }
        if (!typeKnown) {
            // Dish style missing on one or both sides: never claim better than "similar", and say so.
            uncertain = true;
            if (proteinSame && starchSame) {
                reasons.add("Same protein (" + pretty(ref.protein()) + ") and starch (" + pretty(ref.starch())
                        + "), but the dish style is not stated");
                return new MatchResult(MatchLevel.SIMILAR_MEAL, reasons, true);
            }
            if (proteinSame) {
                reasons.add("Same protein (" + pretty(ref.protein()) + "), but the dish style is not stated");
                return new MatchResult(MatchLevel.ALTERNATIVE, reasons, true);
            }
            return new MatchResult(null, List.of(), false);
        }

        // Same dish style from here on.
        if (proteinKnown && !proteinSame) return new MatchResult(null, List.of(), false);
        if (!proteinKnown) {
            if (starchSame) {
                reasons.add("Same dish style and starch, but the protein is not stated");
                return new MatchResult(MatchLevel.ALTERNATIVE, reasons, true);
            }
            return new MatchResult(null, List.of(), false);
        }
        if (starchKnown && !starchSame) {
            reasons.add("Same protein, but with " + pretty(cand.starch()) + " instead of " + pretty(ref.starch()));
            return new MatchResult(MatchLevel.ALTERNATIVE, reasons, false);
        }

        List<String> differences = new ArrayList<>();
        if (!starchKnown) {
            if (ref.starch() != null || cand.starch() != null) {
                differences.add("Starch is not stated for one of the meals");
            }
            uncertain = uncertain || ref.starch() == null && cand.starch() == null;
        }
        Set<String> onlyCand = new LinkedHashSet<>(cand.sides()); onlyCand.removeAll(ref.sides());
        Set<String> onlyRef = new LinkedHashSet<>(ref.sides()); onlyRef.removeAll(cand.sides());
        if (!onlyCand.isEmpty()) differences.add("Also includes " + joinPretty(onlyCand));
        if (!onlyRef.isEmpty()) differences.add("Does not include " + joinPretty(onlyRef));
        if (!java.util.Objects.equals(ref.portionClass(), cand.portionClass())) {
            differences.add("Portion: " + pretty(cand.portionClass()) + " (compared with " + pretty(ref.portionClass()) + ")");
        } else if (ref.portionClass() == null) {
            uncertain = true;
        }
        if (differences.isEmpty()) {
            reasons.add(uncertain ? "Same dish and protein. Some details (starch or portion size) are not stated by either restaurant"
                    : "Same dish, protein, starch, sides and portion size");
            return new MatchResult(MatchLevel.EXACT_MATCH, reasons, uncertain);
        }
        reasons.addAll(differences);
        return new MatchResult(MatchLevel.SIMILAR_MEAL, reasons, uncertain);
    }

    private static String joinPretty(Set<String> tokens) {
        List<String> out = new ArrayList<>();
        for (String t : tokens) out.add(pretty(t));
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ query resolution

    /**
     * Canonical foods a search phrase could mean: those where every word of the phrase appears in the
     * canonical name or one of its aliases ("kota" finds "chicken kota"; "beef pap" finds "beef stew
     * and pap"). Exact word-set matches come first. Reads the (small) set of canonical foods; if that
     * set grows large this should move to an indexed query.
     */
    @Transactional(readOnly = true)
    public List<CanonicalProduct> resolveCanonicalFoods(String query) {
        Set<String> q = tokens(query);
        if (q.isEmpty()) return List.of();
        Map<String, CanonicalProduct> exact = new LinkedHashMap<>();
        Map<String, CanonicalProduct> partial = new LinkedHashMap<>();
        Map<String, List<CanonicalFoodAlias>> aliases = aliasesByCanonical();
        for (CanonicalFoodAttributes a : attributesRepository.findAll()) {
            CanonicalProduct c = a.getCanonicalProduct();
            List<Set<String>> names = new ArrayList<>();
            names.add(tokens(c.getName()));
            for (CanonicalFoodAlias al : aliases.getOrDefault(c.getId(), List.of())) names.add(tokens(al.getAlias()));
            boolean isExact = names.stream().anyMatch(n -> n.equals(q));
            boolean isPartial = names.stream().anyMatch(n -> n.containsAll(q));
            if (isExact) exact.put(c.getId(), c);
            else if (isPartial) partial.put(c.getId(), c);
        }
        List<CanonicalProduct> out = new ArrayList<>(exact.values());
        out.addAll(partial.values());
        return out;
    }

    private Map<String, List<CanonicalFoodAlias>> aliasesByCanonical() {
        Map<String, List<CanonicalFoodAlias>> map = new LinkedHashMap<>();
        for (CanonicalFoodAlias a : aliasRepository.findAll()) {
            map.computeIfAbsent(a.getCanonicalProduct().getId(), k -> new ArrayList<>()).add(a);
        }
        return map;
    }

    @Transactional(readOnly = true)
    public CanonicalFoodAttributes attributesOf(String canonicalId) {
        return attributesRepository.findByCanonicalProductId(canonicalId).orElse(null);
    }

    // ------------------------------------------------------------------ merchant suggestions

    /**
     * Canonical foods whose names/aliases resemble a menu item name. SUGGESTIONS ONLY: nothing is
     * linked until the merchant (or an admin) confirms it through the existing canonical-link call.
     */
    @Transactional(readOnly = true)
    public List<Suggestion> suggest(String productName) {
        Set<String> mine = tokens(productName);
        if (mine.isEmpty()) return List.of();
        Map<String, List<CanonicalFoodAlias>> aliases = aliasesByCanonical();
        List<Suggestion> out = new ArrayList<>();
        for (CanonicalFoodAttributes a : attributesRepository.findAll()) {
            CanonicalProduct c = a.getCanonicalProduct();
            double best = jaccard(mine, tokens(c.getName()));
            for (CanonicalFoodAlias al : aliases.getOrDefault(c.getId(), List.of())) {
                best = Math.max(best, jaccard(mine, tokens(al.getAlias())));
            }
            if (best >= 0.5) out.add(new Suggestion(c.getId(), c.getName(), Math.round(best * 100.0) / 100.0));
        }
        out.sort(Comparator.comparingDouble(Suggestion::score).reversed().thenComparing(Suggestion::name));
        return out.size() > 5 ? out.subList(0, 5) : out;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new TreeSet<>(a); inter.retainAll(b);
        Set<String> union = new TreeSet<>(a); union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
