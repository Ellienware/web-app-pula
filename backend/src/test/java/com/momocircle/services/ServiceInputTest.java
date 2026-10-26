package com.momocircle.services;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceInputTest {

    @Test
    void areasAreTrimmedLowercasedAndDeduplicated() {
        assertEquals(List.of("soweto", "pretoria"), ServiceInput.areas(List.of(" Soweto ", "SOWETO", "Pretoria", " "), 30));
        assertThrows(IllegalArgumentException.class, () -> ServiceInput.areas(List.of("x".repeat(81)), 30));
        assertThrows(IllegalArgumentException.class, () -> ServiceInput.areas(List.of("a\u0000b"), 30));
        assertThrows(IllegalArgumentException.class, () -> ServiceInput.areas(List.of("a", "b", "c"), 2));
    }

    @Test
    void onlyHttpsImageLinksAreAccepted() {
        assertEquals(List.of("https://cdn.example.com/a.jpg"), ServiceInput.imageUrls(List.of(" https://cdn.example.com/a.jpg "), 8));
        for (String bad : new String[] {"http://x.com/a.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA", "//x.com/a.jpg",
                "/uploads/a.jpg", "https://x.com/a b.jpg", "https://x.com/<script>", "ftp://x.com/a", "https://x.com/\"onload="}) {
            assertThrows(IllegalArgumentException.class, () -> ServiceInput.imageUrls(List.of(bad), 8), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ServiceInput.imageUrls(List.of("https://x.com/" + "a".repeat(500)), 8));
        assertThrows(IllegalArgumentException.class, () -> ServiceInput.imageUrls(List.of("https://a.com/1", "https://a.com/2"), 1));
    }

    @Test
    void textAndSlugHelpers() {
        assertNull(ServiceInput.text("   ", 10, "x", false));
        assertThrows(IllegalArgumentException.class, () -> ServiceInput.text("  ", 10, "x", true));
        assertThrows(IllegalArgumentException.class, () -> ServiceInput.text("12345678901", 10, "x", true));
        assertEquals("car-wash", ServiceInput.slug("  Car Wash! "));
        assertThrows(IllegalArgumentException.class, () -> ServiceInput.slug("!!!"));
    }
}
