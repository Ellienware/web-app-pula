package com.momocircle.service;

import com.momocircle.domain.User;
import com.momocircle.dto.StockDtos.CreateWholesaleProductRequest;
import com.momocircle.dto.StockDtos.UpdateWholesaleProductRequest;
import com.momocircle.dto.StockDtos.WholesaleProductResponse;
import com.momocircle.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** NOT VERIFIED IN THIS SANDBOX - no Maven network access. Run ./mvnw test locally. */
class StockCatalogueImportServiceTest {

    private WholesaleProductService products;
    private StockCatalogueImportService service;
    private final User supplier = new User();

    @BeforeEach
    void setUp() {
        products = mock(WholesaleProductService.class);
        service = new StockCatalogueImportService(products);
        when(products.listMine(any(), any())).thenReturn(new PageImpl<>(List.of()));
        WholesaleProductResponse created = mock(WholesaleProductResponse.class);
        when(products.create(any(), any())).thenReturn(created);
    }

    @Test
    void createsValidRowsAndReportsBadRowsWithoutBlockingTheRest() {
        String csv = "name,sku,packSize,moq,pricePerPack,availableStock,category\n"
                + "White Sugar 2kg,SUG-2,6,2,120.50,40,Groceries\n"
                + "Bad Row,BAD,six,1,10,5,\n"
                + "\"Maize Meal, 10kg\",MEAL-10,1,1,89.99,100,Groceries\n";

        var r = service.importCsv(supplier, csv);

        assertEquals(2, r.created());
        assertEquals(1, r.errors().size());
        assertEquals(3, r.errors().get(0).row());
        verify(products, times(2)).create(eq(supplier), any(CreateWholesaleProductRequest.class));
    }

    @Test
    void existingSkuUpdatesInsteadOfCreating() {
        WholesaleProductResponse existing = mock(WholesaleProductResponse.class);
        when(existing.sku()).thenReturn("SUG-2");
        when(existing.id()).thenReturn("p-1");
        when(products.listMine(any(), any())).thenReturn(new PageImpl<>(List.of(existing)));

        var r = service.importCsv(supplier, "name,sku,packSize,moq,pricePerPack,availableStock\nSugar,SUG-2,6,2,99,10\n");

        assertEquals(1, r.updated());
        verify(products).update(eq(supplier), eq("p-1"), any(UpdateWholesaleProductRequest.class));
        verify(products, never()).create(any(), any());
    }

    @Test
    void missingRequiredColumnOrEmptyFileIsRejected() {
        assertThrows(ApiException.class, () -> service.importCsv(supplier, "name,packSize\nA,1\n"));
        assertThrows(ApiException.class, () -> service.importCsv(supplier, "  "));
    }

    @Test
    void parserHandlesQuotedCommasEscapedQuotesAndCrlf() {
        var rows = StockCatalogueImportService.parse("a,b\r\n\"x, y\",\"say \"\"hi\"\"\"\r\n");
        assertEquals("x, y", rows.get(1).get(0));
        assertEquals("say \"hi\"", rows.get(1).get(1));
    }
}
