package com.momocircle.domain.enums;

/**
 * How confidently a Product (in its "retailer product" role — see
 * CanonicalProduct's javadoc) maps to a canonical product for price
 * comparison purposes. Only EXACT_MATCH products are shown as direct price
 * comparisons — see PriceComparisonService. This app does not run automatic
 * fuzzy/NLP matching: EXACT_MATCH is set only when a seller deliberately
 * links their listing to a specific CanonicalProduct (see
 * ProductService#linkCanonicalProduct), which is a safer default than
 * guessing two differently-named listings are "the same product" and
 * potentially showing a wrong comparison for something people are making
 * real purchase decisions with.
 */
public enum MatchClassification {
    /** Deliberately linked by the seller to a specific CanonicalProduct. Shown in direct comparisons. */
    EXACT_MATCH,
    /**
     * Reserved for a future algorithmic "probably the same or a close
     * substitute" classification. Not automatically assigned by anything in
     * this codebase yet.
     */
    SIMILAR_ALTERNATIVE,
    /** No canonical link. The default for every product until a seller links one. */
    UNIQUE_PRODUCT
}
