package com.momocircle.service;

import com.momocircle.domain.User;
import com.momocircle.dto.StockDtos.CreateWholesaleProductRequest;
import com.momocircle.dto.StockDtos.UpdateWholesaleProductRequest;
import com.momocircle.dto.StockDtos.WholesaleProductResponse;
import com.momocircle.exception.ApiException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Supplier catalogue CSV import. Columns (header row required, any order):
 * name, packSize, moq, pricePerPack, availableStock  (required)  and  sku, category, description (optional).
 * Rows with an existing SKU update that product; other valid rows create one. Each row is validated on its
 * own, so one bad row never blocks the rest, and every product still goes through the normal
 * WholesaleProductService (same ownership and validation rules as manual entry). No external suppliers are contacted.
 */
@Service
public class StockCatalogueImportService {

    public static final int MAX_ROWS = 500;
    public static final int MAX_CHARS = 500_000;

    public record ImportRequest(String csv) {}
    public record RowError(int row, String message) {}
    public record ImportResult(int created, int updated, List<RowError> errors) {}

    private final WholesaleProductService products;

    public StockCatalogueImportService(WholesaleProductService products) { this.products = products; }

    // Not @Transactional on purpose: each row commits on its own via WholesaleProductService.
    public ImportResult importCsv(User supplier, String csv) {
        if (csv == null || csv.isBlank()) throw ApiException.badRequest("The CSV is empty");
        if (csv.length() > MAX_CHARS) throw ApiException.badRequest("The CSV is too large (max 500 KB)");

        List<List<String>> rows = parse(csv);
        if (rows.size() < 2) throw ApiException.badRequest("Add a header row and at least one product row");
        if (rows.size() - 1 > MAX_ROWS) throw ApiException.badRequest("Too many rows (max " + MAX_ROWS + ")");

        Map<String, Integer> col = new HashMap<>();
        List<String> header = rows.get(0);
        for (int i = 0; i < header.size(); i++) col.put(header.get(i).trim().toLowerCase(), i);
        for (String req : List.of("name", "packsize", "moq", "priceperpack", "availablestock")) {
            if (!col.containsKey(req)) throw ApiException.badRequest("Missing required column: " + req);
        }

        Map<String, String> existingBySku = new HashMap<>();
        for (WholesaleProductResponse p : products.listMine(supplier, PageRequest.of(0, 1000)).getContent()) {
            if (p.sku() != null) existingBySku.put(p.sku(), p.id());
        }

        int created = 0, updated = 0;
        List<RowError> errors = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.stream().allMatch(String::isBlank)) continue;
            try {
                String name = get(row, col, "name");
                if (name.isBlank()) throw new IllegalArgumentException("name is required");
                int packSize = Integer.parseInt(get(row, col, "packsize"));
                int moq = Integer.parseInt(get(row, col, "moq"));
                BigDecimal price = new BigDecimal(get(row, col, "priceperpack"));
                int stock = Integer.parseInt(get(row, col, "availablestock"));
                if (packSize < 1 || moq < 1 || stock < 0 || price.signum() < 0) {
                    throw new IllegalArgumentException("packSize and moq must be at least 1; price and stock cannot be negative");
                }
                String sku = get(row, col, "sku");
                String category = get(row, col, "category");
                String description = get(row, col, "description");
                String existingId = sku.isBlank() ? null : existingBySku.get(sku);
                if (existingId != null) {
                    products.update(supplier, existingId, new UpdateWholesaleProductRequest(
                            name, blankToNull(description), sku, blankToNull(category), packSize, moq, price, stock, null));
                    updated++;
                } else {
                    WholesaleProductResponse c = products.create(supplier, new CreateWholesaleProductRequest(
                            name, blankToNull(description), blankToNull(sku), blankToNull(category), packSize, moq, price, stock));
                    if (c.sku() != null) existingBySku.put(c.sku(), c.id());
                    created++;
                }
            } catch (NumberFormatException e) {
                errors.add(new RowError(r + 1, "packSize, moq, pricePerPack and availableStock must be numbers"));
            } catch (IllegalArgumentException | ApiException e) {
                errors.add(new RowError(r + 1, e.getMessage()));
            }
        }
        return new ImportResult(created, updated, errors);
    }

    private static String get(List<String> row, Map<String, Integer> col, String name) {
        Integer i = col.get(name);
        return i == null || i >= row.size() ? "" : row.get(i).trim();
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    /** Minimal RFC-4180 style parser: commas, double-quoted fields, "" escapes, CRLF or LF. */
    static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else quoted = false;
                } else cur.append(c);
            } else if (c == '"') quoted = true;
            else if (c == ',') { row.add(cur.toString()); cur.setLength(0); }
            else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') i++;
                row.add(cur.toString()); cur.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else cur.append(c);
        }
        if (cur.length() > 0 || !row.isEmpty()) { row.add(cur.toString()); rows.add(row); }
        return rows;
    }
}
