package com.anknonimp.amber;

import android.content.Context;
import android.content.SharedPreferences;

final class FilterSettings {
    static final String WARMTH = "warmth";
    static final String STRENGTH = "strength";
    static final String ENABLED = "enabled";

    private FilterSettings() {}

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("filter", Context.MODE_PRIVATE);
    }

    static void save(Context context, int warmth, int strength) {
        preferences(context).edit()
                .putInt(WARMTH, FilterMath.clamp(warmth))
                .putInt(STRENGTH, FilterMath.clamp(strength))
                .apply();
    }
}
