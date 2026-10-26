package com.momocircle.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.momocircle.domain.CanonicalFoodAlias;
import com.momocircle.domain.CanonicalFoodAttributes;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.repository.CanonicalFoodAliasRepository;
import com.momocircle.repository.CanonicalFoodAttributesRepository;
import com.momocircle.service.FoodMatchingService.FoodProfile;
import com.momocircle.service.FoodMatchingService.MatchLevel;
import com.momocircle.service.FoodMatchingService.MatchResult;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * NOT VERIFIED IN THIS SANDBOX (no Maven) — run locally. Covers PULA Food matching: normalisation,
 * exact / similar / alternative levels, the "different meal" negatives, unknown attributes, explicit
 * canonical links, query resolution and merchant suggestions.
 */
class FoodMatchingServiceTest {

    private CanonicalFoodAttributesRepository attributes;
    private CanonicalFoodAliasRepository aliases;
    private FoodMatchingService service;

    @BeforeEach
    void setUp() {
        attributes = mock(CanonicalFoodAttributesRepository.class);
        aliases = mock(CanonicalFoodAliasRepository.class);
        service = new FoodMatchingService(attributes, aliases);
        when(attributes.findAll()).thenReturn(List.of());
        when(aliases.findAll()).thenReturn(List.of());
    }

    private static FoodProfile meal(String type, String protein, String starch, String portion, String... sides) {
        return new FoodProfile(null, "meal", type, protein, starch, portion, Set.of(sides), null);
    }

    private MatchResult match(FoodProfile ref, FoodProfile cand) {
        return service.match(ref, cand);
    }

    // ------------------------------------------------------------------ normalisation

    @Test
    void differentWaysOfWritingTheSameMealNormaliseTogether() {
        assertEquals(FoodMatchingService.normalise("Beef stew and pap"), FoodMatchingService.normalise("beef stew + pap"));
        assertEquals(FoodMatchingService.normalise("Beef stew & pap"), FoodMatchingService.normalise("BEEF STEW WITH PAP"));
        assertEquals(Set.of("beef", "pap"), FoodMatchingService.tokens("Beef & pap"));
        assertEquals(Set.of("gogos", "kitchen"), FoodMatchingService.tokens("Gogo's Kitchen"));
        assertTrue(FoodMatchingService.tokens(null).isEmpty());
    }

    @Test
    void normalisationDoesNotTurnDifferentMealsIntoTheSameString() {
        assertFalse(FoodMatchingService.normalise("Beef burger and chips").equals(FoodMatchingService.normalise("Beef stew and pap")));
    }

    @Test
    void portionWordsMapOntoAFixedSet() {
        assertEquals("REGULAR", FoodMatchingService.portionClass("standard"));
        assertEquals("REGULAR", FoodMatchingService.portionClass("Medium"));
        assertEquals("LARGE", FoodMatchingService.portionClass("family"));
        assertEquals("SMALL", FoodMatchingService.portionClass("kids"));
        assertNull(FoodMatchingService.portionClass(" "));
    }

    // ------------------------------------------------------------------ levels

    @Test
    void sameDishProteinStarchSidesAndPortionIsAnExactMatch() {
        MatchResult r = match(meal("STEW", "BEEF", "PAP", "REGULAR"), meal("STEW", "BEEF", "PAP", "REGULAR"));
        assertEquals(MatchLevel.EXACT_MATCH, r.level());
        assertFalse(r.uncertain());
    }

    @Test
    void anExtraSideMakesItASimilarMealAndSaysWhy() {
        MatchResult r = match(meal("STEW", "BEEF", "PAP", "REGULAR"), meal("STEW", "BEEF", "PAP", "REGULAR", "SPINACH"));
        assertEquals(MatchLevel.SIMILAR_MEAL, r.level());
        assertTrue(r.reasons().stream().anyMatch(x -> x.contains("spinach")));
    }

    @Test
    void aDifferentPortionSizeMakesItASimilarMeal() {
        MatchResult r = match(meal("STEW", "BEEF", "PAP", "REGULAR"), meal("STEW", "BEEF", "PAP", "LARGE"));
        assertEquals(MatchLevel.SIMILAR_MEAL, r.level());
        assertTrue(r.reasons().stream().anyMatch(x -> x.toLowerCase().contains("portion")));
    }

    @Test
    void aPortionOnlyOneSideStatesIsNotTreatedAsTheSame() {
        assertEquals(MatchLevel.SIMILAR_MEAL,
                match(meal("STEW", "BEEF", "PAP", null), meal("STEW", "BEEF", "PAP", "LARGE")).level());
    }

    @Test
    void portionUnknownOnBothSidesIsStillExactButFlaggedUncertain() {
        MatchResult r = match(meal("STEW", "BEEF", "PAP", null), meal("STEW", "BEEF", "PAP", null));
        assertEquals(MatchLevel.EXACT_MATCH, r.level());
        assertTrue(r.uncertain());
    }

    @Test
    void differentStarchIsOnlyAnAlternative() {
        MatchResult r = match(meal("STEW", "BEEF", "PAP", "REGULAR"), meal("STEW", "BEEF", "CHIPS", "REGULAR"));
        assertEquals(MatchLevel.ALTERNATIVE, r.level());
    }

    @Test
    void beefBurgerAndChipsIsNeverTheSameMealAsBeefStewAndPap() {
        MatchResult r = match(meal("STEW", "BEEF", "PAP", "REGULAR"), meal("BURGER", "BEEF", "CHIPS", "REGULAR"));
        assertEquals(MatchLevel.ALTERNATIVE, r.level()); // same protein only, never exact or similar
        assertTrue(r.reasons().get(0).contains("different style of dish"));
    }

    @Test
    void differentProteinIsNotComparable() {
        assertNull(match(meal("STEW", "BEEF", "PAP", "REGULAR"), meal("STEW", "CHICKEN", "PAP", "REGULAR")).level());
        assertNull(match(meal("STEW", "BEEF", "PAP", "REGULAR"), meal("KOTA", "CHICKEN", "CHIPS", "REGULAR")).level());
    }

    // ------------------------------------------------------------------ unknowns

    @Test
    void missingDishStyleCapsTheLevelAtSimilarAndIsFlagged() {
        MatchResult r = match(meal("STEW", "BEEF", "PAP", "REGULAR"), meal(null, "BEEF", "PAP", "REGULAR"));
        assertEquals(MatchLevel.SIMILAR_MEAL, r.level());
        assertTrue(r.uncertain());
        assertTrue(r.reasons().get(0).contains("not stated"));
    }

    @Test
    void onlyTheProteinKnownGivesAnUncertainAlternative() {
        MatchResult r = match(meal("STEW", "BEEF", "PAP", "REGULAR"), meal(null, "BEEF", null, null));
        assertEquals(MatchLevel.ALTERNATIVE, r.level());
        assertTrue(r.uncertain());
    }

    @Test
    void nothingKnownIsNeverAMatch() {
        assertNull(match(meal("STEW", "BEEF", "PAP", "REGULAR"), meal(null, null, null, null)).level());
    }

    // ------------------------------------------------------------------ explicit canonical links

    private static FoodProfile linked(String canonicalId, MatchClassification link, String type, String protein, String starch) {
        return new FoodProfile(canonicalId, "m", type, protein, starch, "REGULAR", Set.of(), link);
    }

    @Test
    void anExplicitExactLinkIsTrustedWhenAttributesAgree() {
        MatchResult r = match(linked("c1", null, "STEW", "BEEF", "PAP"), linked("c1", MatchClassification.EXACT_MATCH, "STEW", "BEEF", "PAP"));
        assertEquals(MatchLevel.EXACT_MATCH, r.level());
        assertEquals("Linked to the same meal by the restaurant", r.reasons().get(0));
    }

    @Test
    void anExplicitLinkIsDowngradedWhenAttributesContradictIt() {
        MatchResult r = match(linked("c1", null, "STEW", "BEEF", "PAP"), linked("c1", MatchClassification.EXACT_MATCH, "STEW", "CHICKEN", "PAP"));
        assertEquals(MatchLevel.SIMILAR_MEAL, r.level());
        assertTrue(r.reasons().stream().anyMatch(x -> x.contains("differ from the standard meal")));
    }

    @Test
    void aSimilarOrUnclassifiedLinkIsNeverShownAsExact() {
        FoodProfile ref = linked("c1", null, "STEW", "BEEF", "PAP");
        assertEquals(MatchLevel.SIMILAR_MEAL, match(ref, linked("c1", MatchClassification.SIMILAR_ALTERNATIVE, "STEW", "BEEF", "PAP")).level());
        assertEquals(MatchLevel.SIMILAR_MEAL, match(ref, linked("c1", null, "STEW", "BEEF", "PAP")).level());
    }

    @Test
    void differentCanonicalIdsFallBackToAttributes() {
        MatchResult r = match(linked("c1", null, "STEW", "BEEF", "PAP"), linked("c2", MatchClassification.EXACT_MATCH, "STEW", "BEEF", "PAP"));
        assertEquals(MatchLevel.EXACT_MATCH, r.level());
        assertFalse(r.reasons().contains("Linked to the same meal by the restaurant"));
    }

    // ------------------------------------------------------------------ query resolution

    private CanonicalProduct canonical(String id, String name) {
        CanonicalProduct c = new CanonicalProduct();
        c.setId(id);
        c.setName(name);
        c.setCategory("MEAL");
        return c;
    }

    private CanonicalFoodAttributes attrs(CanonicalProduct c) {
        CanonicalFoodAttributes a = new CanonicalFoodAttributes();
        a.setCanonicalProduct(c);
        return a;
    }

    private CanonicalFoodAlias alias(CanonicalProduct c, String text) {
        CanonicalFoodAlias a = new CanonicalFoodAlias();
        a.setCanonicalProduct(c);
        a.setAlias(FoodMatchingService.normalise(text));
        return a;
    }

    @Test
    void searchPhrasesResolveThroughAliasesAndExactMatchesComeFirst() {
        CanonicalProduct stew = canonical("c-stew", "Beef Stew + Pap");
        CanonicalProduct kota = canonical("c-kota", "Chicken Kota");
        CanonicalProduct bigStew = canonical("c-big", "Beef Stew Pap Spinach Combo");
        when(attributes.findAll()).thenReturn(List.of(attrs(bigStew), attrs(stew), attrs(kota)));
        when(aliases.findAll()).thenReturn(List.of(alias(stew, "beef and pap")));

        List<CanonicalProduct> r = service.resolveCanonicalFoods("Beef & Pap");
        assertEquals(List.of("c-stew", "c-big"), r.stream().map(CanonicalProduct::getId).toList()); // exact alias first, partial after
        assertEquals(List.of("c-kota"), service.resolveCanonicalFoods("kota").stream().map(CanonicalProduct::getId).toList());
        assertTrue(service.resolveCanonicalFoods("burger").isEmpty());
        assertTrue(service.resolveCanonicalFoods("  ").isEmpty());
    }

    // ------------------------------------------------------------------ merchant suggestions

    @Test
    void suggestionsAreScoredByNameResemblanceAndNothingIsLinked() {
        CanonicalProduct stew = canonical("c-stew", "Beef Stew + Pap");
        CanonicalProduct kota = canonical("c-kota", "Chicken Kota");
        when(attributes.findAll()).thenReturn(List.of(attrs(stew), attrs(kota)));

        List<FoodMatchingService.Suggestion> s = service.suggest("Beef Stew & Pap");
        assertEquals(1, s.size());
        assertEquals("c-stew", s.get(0).canonicalId());
        assertEquals(1.0, s.get(0).score());
        assertTrue(service.suggest("Mystery meal").isEmpty());
    }

    @Test
    void jaccardScoresOverlap() {
        assertEquals(0.5, FoodMatchingService.jaccard(Set.of("a", "b"), Set.of("b", "c", "a", "d")));
        assertEquals(0.0, FoodMatchingService.jaccard(Set.of(), Set.of("a")));
    }
}
