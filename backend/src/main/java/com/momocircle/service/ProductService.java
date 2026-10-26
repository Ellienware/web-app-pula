package com.momocircle.service;

import com.momocircle.config.CurrencyProperties;
import com.momocircle.domain.Business;
import com.momocircle.domain.CanonicalProduct;
import com.momocircle.domain.Product;
import com.momocircle.domain.User;
import com.momocircle.domain.enums.MatchClassification;
import com.momocircle.domain.enums.ProductStatus;
import com.momocircle.dto.MarketDtos.CreateProductRequest;
import com.momocircle.dto.MarketDtos.ProductResponse;
import com.momocircle.dto.MarketDtos.UpdateProductRequest;
import com.momocircle.exception.ApiException;
import com.momocircle.repository.CanonicalProductRepository;
import com.momocircle.repository.ProductRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final BusinessService businessService;
    private final CurrencyProperties currencyProperties;
    private final FinancialValidationService financialValidationService;
    private final CanonicalProductRepository canonicalProductRepository;

    public ProductService(ProductRepository productRepository, BusinessService businessService,
                          CurrencyProperties currencyProperties,
                          FinancialValidationService financialValidationService,
                          CanonicalProductRepository canonicalProductRepository) {
        this.productRepository = productRepository;
        this.businessService = businessService;
        this.currencyProperties = currencyProperties;
        this.financialValidationService = financialValidationService;
        this.canonicalProductRepository = canonicalProductRepository;
    }

    @Transactional
    public ProductResponse create(User owner, CreateProductRequest req) {
        Business business = businessService.requireOwn(owner);
        financialValidationService.requireValidAmount(req.price(), "price");
        String currency = (req.currency() == null || req.currency().isBlank())
                ? currencyProperties.getDefaultCurrency()
                : req.currency().toUpperCase();
        financialValidationService.requireValidCurrency(currency);

        Product product = new Product();
        product.setBusiness(business);
        product.setName(req.name().trim());
        product.setDescription(req.description());
        if (req.imageUrls() != null) product.setImageUrls(validImages(req.imageUrls()));
        product.setCategory(req.category());
        product.setPrice(req.price());
        product.setCurrency(currency);
        product.setService(req.isService());
        product.setStock(req.isService() ? 0 : req.stock());
        product.setStatus(ProductStatus.DRAFT);
        return toResponse(productRepository.save(product));
    }

    @Transactional
    public ProductResponse update(User owner, String productId, UpdateProductRequest req) {
        Product product = requireById(productId);
        businessService.requireOwnership(product.getBusiness(), owner);

        if (req.name() != null) product.setName(req.name().trim());
        if (req.description() != null) product.setDescription(req.description());
        if (req.imageUrls() != null) product.setImageUrls(validImages(req.imageUrls()));
        if (req.category() != null) product.setCategory(req.category());
        if (req.price() != null) {
            financialValidationService.requireValidAmount(req.price(), "price");
            product.setPrice(req.price());
        }
        if (req.stock() != null && !product.isService()) {
            if (req.stock() < 0) throw ApiException.badRequest("Stock cannot be negative");
            product.setStock(req.stock());
        }
        if (req.status() != null) product.setStatus(req.status());
        return toResponse(productRepository.save(product));
    }

    /** https links or images uploaded via POST /api/images only; anything else (data:, javascript:, http:) is refused. */
    private static List<String> validImages(List<String> raw) {
        try {
            return com.momocircle.services.ServiceInput.imageUrls(raw, 8);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    @Transactional
    public void archive(User owner, String productId) {
        Product product = requireById(productId);
        businessService.requireOwnership(product.getBusiness(), owner);
        product.setStatus(ProductStatus.ARCHIVED);
        productRepository.save(product);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> listForBusiness(String businessId, Pageable pageable) {
        return productRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, pageable).map(this::toResponse);
    }

    /** General browse — used by sellers looking at the full catalogue (not capability-scoped). */
    @Transactional(readOnly = true)
    public Page<ProductResponse> browse(String category, java.math.BigDecimal maxPrice, Pageable pageable) {
        String normalizedCategory = (category == null || category.isBlank()) ? null : category;
        return productRepository.browse(normalizedCategory, maxPrice, pageable).map(this::toResponse);
    }

    /**
     * Phase 5 — consumer-facing PULA Market — Shop browse. Only returns products from businesses
     * that carry CAN_SELL_TO_CONSUMERS, so a wholesaler's stock catalogue never leaks into the
     * consumer storefront even if the wholesaler accidentally lists products.
     */
    @Transactional(readOnly = true)
    public Page<ProductResponse> browseConsumer(String category, java.math.BigDecimal maxPrice, Pageable pageable) {
        String normalizedCategory = (category == null || category.isBlank()) ? null : category;
        return productRepository.browseConsumer(normalizedCategory, maxPrice, pageable).map(this::toResponse);
    }

    /**
     * Phase 5 — returns the top N most-used active categories from consumer sellers, for the
     * category-pill row on the market home. Capped at 20 to keep the response lean; ordered by
     * product count descending so the most-populated categories appear first.
     */
    @Transactional(readOnly = true)
    public java.util.List<String> popularCategories(int limit) {
        return productRepository.findPopularCategories(
                org.springframework.data.domain.PageRequest.of(0, Math.min(limit, 20)))
                .stream()
                .map(row -> (String) row[0])
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> search(String term, java.math.BigDecimal maxPrice, Pageable pageable) {
        return productRepository.search(term, maxPrice, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> searchConsumer(
            String term, java.math.BigDecimal maxPrice, Pageable pageable) {
        return productRepository.searchConsumer(term, maxPrice, pageable).map(this::toResponse);
    }

    /**
     * Consumer storefront detail lookup. Only active products from businesses
     * that explicitly support consumer sales may be displayed in PULA Market.
     */
    @Transactional(readOnly = true)
    public ProductResponse getConsumerProduct(String productId) {
        Product product = productRepository.findConsumerProductById(productId)
                .orElseThrow(() -> ApiException.notFound("Product not found"));
        return toResponse(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse get(String productId) {
        return toResponse(requireById(productId));
    }

    public Product requireById(String productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> ApiException.notFound("Product not found"));
    }

    /** Deducts stock for a physical product, throwing if there isn't enough. Services are never decremented. */
    @Transactional
    public void reserveStock(Product product, int quantity) {
        if (product.isService()) return;
        if (product.getStock() < quantity) {
            throw ApiException.badRequest("Not enough stock for \"" + product.getName() + "\"");
        }
        product.setStock(product.getStock() - quantity);
        if (product.getStock() == 0) {
            product.setStatus(ProductStatus.OUT_OF_STOCK);
        }
        productRepository.save(product);
    }

    /** Compensating action for {@link #reserveStock}: puts quantity back after a failed/cancelled charge. */
    @Transactional
    public void releaseStock(Product product, int quantity) {
        if (product.isService()) return;
        product.setStock(product.getStock() + quantity);
        if (product.getStatus() == ProductStatus.OUT_OF_STOCK && product.getStock() > 0) {
            product.setStatus(ProductStatus.ACTIVE);
        }
        productRepository.save(product);
    }

    /**
     * Links (or, with a null canonicalProductId, unlinks) this product to a
     * CanonicalProduct. Defaults to EXACT_MATCH (used for direct price
     * comparison — see PriceComparisonService). A seller can instead
     * explicitly choose SIMILAR_ALTERNATIVE for their own product — e.g.
     * "this is a 1.5L bottle, the canonical product is 2L, close enough to
     * mention as an alternative but not to price-compare directly." This is
     * always the seller's own informed choice about their own product,
     * never an automatic classification of anyone's data — see
     * CanonicalProduct/MatchClassification's own javadoc for why this app
     * doesn't do automatic fuzzy matching that could mutate a product
     * without its owner's consent.
     */
    @Transactional
    public ProductResponse linkCanonicalProduct(User owner, String productId, String canonicalProductId,
                                                 MatchClassification classification) {
        Product product = requireById(productId);
        businessService.requireOwnership(product.getBusiness(), owner);
        if (canonicalProductId == null) {
            product.setCanonicalProduct(null);
            product.setMatchClassification(MatchClassification.UNIQUE_PRODUCT);
        } else {
            if (classification == MatchClassification.UNIQUE_PRODUCT) {
                throw ApiException.badRequest(
                        "A linked product must be EXACT_MATCH or SIMILAR_ALTERNATIVE, not UNIQUE_PRODUCT.");
            }
            CanonicalProduct canonicalProduct = canonicalProductRepository.findById(canonicalProductId)
                    .orElseThrow(() -> ApiException.notFound("Canonical product not found"));
            product.setCanonicalProduct(canonicalProduct);
            product.setMatchClassification(classification == null ? MatchClassification.EXACT_MATCH : classification);
        }
        return toResponse(productRepository.save(product));
    }

    /**
     * Read-only candidate suggestions to help a seller decide what to link
     * their own product to — never persists anything itself. Matches
     * purely on category + simple case-insensitive word-token overlap
     * against canonical product names, which is transparent and auditable
     * (a seller can see exactly why something was suggested) rather than
     * an opaque similarity model. The seller still makes the actual
     * EXACT_MATCH/SIMILAR_ALTERNATIVE decision via linkCanonicalProduct.
     */
    @Transactional(readOnly = true)
    public List<CanonicalProductSuggestion> suggestCanonicalProducts(User owner, String productId) {
        Product product = requireById(productId);
        businessService.requireOwnership(product.getBusiness(), owner);

        java.util.Set<String> productTokens = tokenize(product.getName());
        if (productTokens.isEmpty()) return List.of();

        return canonicalProductRepository.findByCategory(product.getCategory()).stream()
                .filter(cp -> !cp.getId().equals(
                        product.getCanonicalProduct() == null ? null : product.getCanonicalProduct().getId()))
                .map(cp -> new CanonicalProductSuggestion(cp, jaccard(productTokens, tokenize(cp.getName()))))
                .filter(s -> s.score() > 0.2)
                .sorted(java.util.Comparator.comparingDouble(CanonicalProductSuggestion::score).reversed())
                .limit(5)
                .toList();
    }

    private java.util.Set<String> tokenize(String text) {
        if (text == null) return java.util.Set.of();
        return java.util.Arrays.stream(text.toLowerCase(java.util.Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 1)
                .collect(java.util.stream.Collectors.toSet());
    }

    private double jaccard(java.util.Set<String> a, java.util.Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        java.util.Set<String> intersection = new java.util.HashSet<>(a);
        intersection.retainAll(b);
        java.util.Set<String> union = new java.util.HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    /** A candidate canonical product a seller might link to, with a transparent, auditable similarity score (0-1). */
    public record CanonicalProductSuggestion(CanonicalProduct canonicalProduct, double score) {}

    private ProductResponse toResponse(Product p) {
        return new ProductResponse(
                p.getId(), p.getBusiness().getId(), p.getBusiness().getName(),
                p.getName(), p.getDescription(), p.getImageUrls(), p.getCategory(),
                p.getPrice(), p.getCurrency(), p.isService(), p.getStock(), p.getStatus(),
                p.getCanonicalProduct() == null ? null : p.getCanonicalProduct().getId(),
                p.getMatchClassification(), p.getRatingAverage(), p.getRatingCount(), p.getCreatedAt());
    }
}
