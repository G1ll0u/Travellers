package com.jubitus.travellers.entity.culture;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Turns a Millénaire villager .txt filename (e.g. "farmer", "merchant_art", "cattlefarmerfemale")
 * into a human-readable occupation label - a generic heuristic, never a maintained per-culture
 * mapping table (see the identity-memory plan's N-1: measured against all 285 base-game villager
 * files, 76% already split cleanly on "_", the rest render as one concatenated word - an accepted
 * limitation of the zero-maintenance option, not a bug to chase).
 */
public final class JobLabelFormatter {

    private JobLabelFormatter() {
    }

    /** Empty string for a missing/blank id - callers should simply omit the fact rather than state nothing. */
    public static String format(@Nullable String jobId) {
        if (jobId == null) return "";
        String s = jobId.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return "";

        s = s.replace('_', ' ');

        // Millénaire-wide gendered-suffix conventions (not a per-job fact) - strip a trailing whole
        // word "female"/"male", or the abbreviated trailing "f" seen on files like "shepherdf".
        if (s.endsWith(" female")) {
            s = s.substring(0, s.length() - " female".length());
        } else if (s.endsWith(" male")) {
            s = s.substring(0, s.length() - " male".length());
        } else if (s.endsWith("female")) {
            s = s.substring(0, s.length() - "female".length());
        } else if (s.endsWith("male") && !s.endsWith("female")) {
            s = s.substring(0, s.length() - "male".length());
        } else if (s.endsWith("f") && s.length() > 1) {
            s = s.substring(0, s.length() - 1);
        }
        s = s.trim();
        if (s.isEmpty()) return "";

        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
