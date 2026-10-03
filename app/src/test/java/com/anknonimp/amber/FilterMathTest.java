package com.anknonimp.amber;

import org.junit.Test;
import static org.junit.Assert.*;

public class FilterMathTest {
    @Test public void zeroStrengthLeavesScreenUnchanged() {
        assertEquals(0f, FilterMath.opacity(0), 0f);
    }

    @Test public void everyStrengthAllowsAndroidTouchPassThrough() {
        for (int strength = -20; strength <= 120; strength++) {
            float opacity = FilterMath.opacity(strength);
            assertTrue(opacity >= 0f);
            assertTrue("Opacity must stay below Android's 0.8 limit", opacity < 0.8f);
        }
        assertEquals(FilterMath.MAX_OPACITY, FilterMath.opacity(100), 0.00001f);
    }

    @Test public void strengthAlwaysReducesBlueChannel() {
        for (int strength = 0; strength <= 100; strength++) {
            for (int blue = 0; blue <= 255; blue++) {
                float blended = blue * (1f - FilterMath.opacity(strength));
                assertTrue(blended <= blue);
                if (strength > 0 && blue > 0) assertTrue(blended < blue);
            }
        }
    }

    @Test public void warmthNeverAddsBlueAndGetsProgressivelyWarmer() {
        int previousGreen = 256;
        for (int warmth = 0; warmth <= 100; warmth++) {
            int color = FilterMath.color(warmth);
            assertEquals(255, color >>> 24);
            assertEquals(255, (color >>> 16) & 255);
            assertEquals(0, color & 255);
            int green = (color >>> 8) & 255;
            assertTrue(green <= previousGreen);
            previousGreen = green;
        }
    }

    @Test public void invalidSavedSettingsAreClamped() {
        assertEquals(FilterMath.color(0), FilterMath.color(-100));
        assertEquals(FilterMath.color(100), FilterMath.color(1000));
        assertEquals(0f, FilterMath.opacity(-100), 0f);
        assertEquals(FilterMath.MAX_OPACITY, FilterMath.opacity(1000), 0.00001f);
    }
}
