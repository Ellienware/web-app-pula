package com.momocircle.services;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadedImageRulesTest {

    private static byte[] pad(int... head) {
        byte[] b = new byte[32];
        for (int i = 0; i < head.length; i++) b[i] = (byte) head[i];
        return b;
    }

    @Test
    void detectsRealImageTypesFromBytesOnly() {
        assertEquals("image/jpeg", UploadedImageRules.detectType(pad(0xFF, 0xD8, 0xFF, 0xE0)));
        assertEquals("image/png", UploadedImageRules.detectType(pad(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)));
        assertEquals("image/webp", UploadedImageRules.detectType(pad('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P')));
    }

    @Test
    void rejectsSvgGifHtmlAndTinyOrNullInput() {
        assertNull(UploadedImageRules.detectType("<svg xmlns='http://www.w3.org/2000/svg'></svg>".getBytes()));
        assertNull(UploadedImageRules.detectType("GIF89a......................".getBytes()));
        assertNull(UploadedImageRules.detectType("<html><script>alert(1)</script></html>".getBytes()));
        assertNull(UploadedImageRules.detectType(new byte[] {(byte) 0xFF, (byte) 0xD8}));
        assertNull(UploadedImageRules.detectType(null));
    }

    @Test
    void recognisesOnlyOurOwnUploadPaths() {
        assertTrue(UploadedImageRules.isUploadedPath("/api/images/3f2b9c1e-1111-4222-8333-444455556666"));
        assertFalse(UploadedImageRules.isUploadedPath("/api/images/../etc/passwd"));
        assertFalse(UploadedImageRules.isUploadedPath("/api/images/short"));
        assertFalse(UploadedImageRules.isUploadedPath("//evil.com/api/images/3f2b9c1e-1111-4222-8333-444455556666"));
        assertFalse(UploadedImageRules.isUploadedPath(null));
    }

    @Test
    void imageReferencesAcceptHttpsAndUploadedPathsOnly() {
        String up = "/api/images/3f2b9c1e-1111-4222-8333-444455556666";
        assertEquals(List.of("https://cdn.example.com/a.jpg", up),
                ServiceInput.imageUrls(List.of("https://cdn.example.com/a.jpg", up), 8));
        for (String bad : new String[] {"javascript:alert(1)", "data:image/png;base64,AAAA", "http://x.com/a.jpg", "/other/path.png"}) {
            assertThrows(IllegalArgumentException.class, () -> ServiceInput.imageUrls(List.of(bad), 8), bad);
        }
    }
}
