package com.momocircle.controller;

import com.momocircle.dto.StockDtos.CreateWholesaleProductRequest;
import com.momocircle.dto.StockDtos.UpdateWholesaleProductRequest;
import com.momocircle.dto.StockDtos.WholesaleProductResponse;
import com.momocircle.security.CurrentUserService;
import com.momocircle.service.WholesaleProductService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WholesaleProductController {

    private final WholesaleProductService service;
    private final CurrentUserService currentUser;
    private final com.momocircle.service.StockCatalogueImportService importService;

    public WholesaleProductController(WholesaleProductService service, CurrentUserService currentUser,
                                      com.momocircle.service.StockCatalogueImportService importService) {
        this.importService = importService;
        this.service = service;
        this.currentUser = currentUser;
    }

    // ---- Supplier: manage own catalogue ----

    @PostMapping("/api/stock/products")
    public ResponseEntity<WholesaleProductResponse> create(@Valid @RequestBody CreateWholesaleProductRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(currentUser.require(), req));
    }

    /** Bulk catalogue import from CSV text (JSON body {"csv": "..."}). */
    @PostMapping("/api/stock/products/import")
    public com.momocircle.service.StockCatalogueImportService.ImportResult importCsv(
            @RequestBody com.momocircle.service.StockCatalogueImportService.ImportRequest req) {
        return importService.importCsv(currentUser.require(), req.csv());
    }

    @GetMapping("/api/stock/products/mine")
    public Page<WholesaleProductResponse> mine(@RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return service.listMine(currentUser.require(), PageRequest.of(page, Math.min(size, 50)));
    }

    @PutMapping("/api/stock/products/{id}")
    public WholesaleProductResponse update(@PathVariable String id,
                                            @Valid @RequestBody UpdateWholesaleProductRequest req) {
        return service.update(currentUser.require(), id, req);
    }

    @DeleteMapping("/api/stock/products/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(currentUser.require(), id);
        return ResponseEntity.noContent().build();
    }

    // ---- Buyer / public: browse catalogues ----

    /** Browse all active wholesale products (requires auth — buyers with CAN_BUY_STOCK
     *  only; the service doesn't enforce this on browse because the brief says buyers can
     *  browse to discover suppliers before they have the capability, but placing an order
     *  does require the capability). */
    @GetMapping("/api/stock/products")
    public Page<WholesaleProductResponse> browse(@RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String q,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return service.browse(category, q, PageRequest.of(page, Math.min(size, 50)));
    }

    /** List a specific supplier's active wholesale products. */
    @GetMapping("/api/stock/suppliers/{businessId}/products")
    public Page<WholesaleProductResponse> forSupplier(@PathVariable String businessId,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        return service.listForSupplier(businessId, PageRequest.of(page, Math.min(size, 50)));
    }

    @GetMapping("/api/stock/products/{id}")
    public WholesaleProductResponse get(@PathVariable String id) {
        return service.get(id);
    }
}
