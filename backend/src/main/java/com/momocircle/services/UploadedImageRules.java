package com.momocircle.services;

import java.util.regex.Pattern;

/**
 * Pure (framework-free) rules for seller image uploads. The declared Content-Type and file name are
 * NEVER trusted: the type is decided from the file's own leading bytes, and only JPEG, PNG and WebP
 * are accepted (no SVG/GIF/HTML, which can carry scripts or be abused).
 */
public final class UploadedImageRules {

    private UploadedImageRules() {}

    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int MAX_IMAGES_PER_BUSINESS = 300;
    public static final String PATH_PREFIX = "/api/images/";
    private static final Pattern UPLOADED_PATH = Pattern.compile("^/api/images/[0-9a-fA-F-]{36}$");

    /** @return "image/jpeg", "image/png" or "image/webp"; null when the bytes are not one of those. */
    public static String detectType(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) return "image/png";
        if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        return null;
    }

    /** True for a path produced by our own upload endpoint, e.g. /api/images/3f2b…(36 chars). */
    public static boolean isUploadedPath(String v) {
        return v != null && UPLOADED_PATH.matcher(v).matches();
    }
}
