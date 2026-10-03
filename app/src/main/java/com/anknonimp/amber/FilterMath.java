package com.anknonimp.amber;

/** Pure color calculations, independent of Android so they can be unit tested. */
final class FilterMath {
    // Android 12+ blocks touches behind untrusted overlays above 0.8 opacity.
    // Keep a margin, and use ONE non-touchable overlay with window-level alpha.
    static final float MAX_OPACITY = 0.70f;

    private FilterMath() {}

    static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    static float opacity(int strength) {
        return MAX_OPACITY * clamp(strength) / 100f;
    }

    static int color(int warmth) {
        int green = Math.round(205f - 105f * clamp(warmth) / 100f);
        // A zero blue component attenuates the screen's blue channel during blending.
        return 0xFF000000 | (255 << 16) | (green << 8);
    }
}
