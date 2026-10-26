package com.momocircle.branch;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Pure validation/normalisation for branch fields. Throws {@link IllegalArgumentException} with a user-safe message. */
public final class BranchRules {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,19}$");
    private static final Pattern PHONE = Pattern.compile("^[0-9+()\\- ]{7,20}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern POSTAL = Pattern.compile("^[A-Za-z0-9 -]{3,10}$");

    private BranchRules() { }

    /** Trim, upper-case, spaces to '-'. Null for blank (a code is optional). */
    public static String normaliseCode(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replaceAll("\\s+", "-").toUpperCase(java.util.Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    /** Normalises and validates; returns null for blank. */
    public static String requireValidCode(String raw) {
        String c = normaliseCode(raw);
        if (c == null) return null;
        if (!CODE.matcher(c).matches()) {
            throw new IllegalArgumentException("Branch code must be 2-20 characters: letters, digits, '-' or '_', starting with a letter or digit");
        }
        return c;
    }

    public static String requireValidName(String raw) {
        String n = raw == null ? "" : raw.trim();
        if (n.isEmpty()) throw new IllegalArgumentException("Branch name is required");
        if (n.length() > 255) throw new IllegalArgumentException("Branch name is too long");
        return n;
    }

    public static void requireValidCoordinates(BigDecimal lat, BigDecimal lon) {
        if (lat == null && lon == null) return;
        if (lat == null || lon == null) throw new IllegalArgumentException("Provide both latitude and longitude, or neither");
        if (lat.abs().compareTo(BigDecimal.valueOf(90)) > 0 || lon.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("Latitude or longitude is out of range");
        }
    }

    public static String optionalPhone(String raw) {
        String p = blankToNull(raw);
        if (p != null && !PHONE.matcher(p).matches()) throw new IllegalArgumentException("Telephone number is not valid");
        return p;
    }

    public static String optionalEmail(String raw) {
        String e = blankToNull(raw);
        if (e != null && (e.length() > 255 || !EMAIL.matcher(e).matches())) throw new IllegalArgumentException("Contact email is not valid");
        return e;
    }

    public static String optionalPostalCode(String raw) {
        String p = blankToNull(raw);
        if (p != null && !POSTAL.matcher(p).matches()) throw new IllegalArgumentException("Postal code is not valid");
        return p;
    }

    public static String optionalText(String raw, int max, String field) {
        String t = blankToNull(raw);
        if (t != null && t.length() > max) throw new IllegalArgumentException(field + " is too long");
        return t;
    }

    private static String blankToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
