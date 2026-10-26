package com.momocircle.services;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Input hygiene for the services marketplace. Throws IllegalArgumentException; callers translate to 400. */
public final class ServiceInput {

    private ServiceInput() {}

    /** Areas are stored trimmed and lower-cased so discovery matching is case-insensitive and exact. */
    public static List<String> areas(List<String> raw, int max) {
        if (raw == null) return new ArrayList<>();
        Set<String> out = new LinkedHashSet<>();
        for (String a : raw) {
            if (a == null) continue;
            String v = a.trim().toLowerCase(Locale.ROOT);
            if (v.isEmpty()) continue;
            if (v.length() > 80 || hasControl(v)) throw new IllegalArgumentException("Invalid service area");
            out.add(v);
        }
        if (out.size() > max) throw new IllegalArgumentException("Too many service areas (max " + max + ")");
        return new ArrayList<>(out);
    }

    /**
     * Images are either https URLs or the path of an image uploaded through POST /api/images (/api/images/{id}).
     * Anything else (data:, javascript:, http:, other relative paths, whitespace, control characters) is refused.
     */
    public static List<String> imageUrls(List<String> raw, int max) {
        if (raw == null) return new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (String u : raw) {
            if (u == null || u.isBlank()) continue;
            String v = u.trim();
            if (UploadedImageRules.isUploadedPath(v)) { out.add(v); continue; }
            if (v.length() > 500 || !v.regionMatches(true, 0, "https://", 0, 8) || hasControl(v) || v.chars().anyMatch(Character::isWhitespace)
                    || v.contains("<") || v.contains(">") || v.contains("\"")) {
                throw new IllegalArgumentException("Images must be https links or uploaded images");
            }
            out.add(v);
        }
        if (out.size() > max) throw new IllegalArgumentException("Too many images (max " + max + ")");
        return out;
    }

    public static String text(String raw, int max, String field, boolean required) {
        String v = raw == null ? null : raw.trim();
        if (v == null || v.isEmpty()) {
            if (required) throw new IllegalArgumentException(field + " is required");
            return null;
        }
        if (v.length() > max) throw new IllegalArgumentException(field + " is too long");
        return v;
    }

    public static String slug(String name) {
        String s = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (s.isEmpty()) throw new IllegalArgumentException("Name must contain letters or digits");
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static boolean hasControl(String s) {
        return s.chars().anyMatch(Character::isISOControl);
    }
}
