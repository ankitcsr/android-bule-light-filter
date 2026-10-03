package com.anknonimp.amber;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SeekBar warmth;
    private SeekBar strength;
    private boolean pendingEnable;
    private int permissionChecksRemaining;
    private SharedPreferences preferences;
    private final Runnable permissionResult = this::checkOverlayPermissionResult;
    private final SharedPreferences.OnSharedPreferenceChangeListener stateListener =
            (prefs, key) -> {
                if (FilterSettings.ENABLED.equals(key)) refreshState();
            };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        preferences = FilterSettings.preferences(this);
        if (savedInstanceState != null) {
            pendingEnable = savedInstanceState.getBoolean("pendingEnable");
        }
        applyInsets();
        warmth = findViewById(R.id.warmth);
        strength = findViewById(R.id.strength);
        warmth.setProgress(preferences.getInt(FilterSettings.WARMTH, 60));
        strength.setProgress(preferences.getInt(FilterSettings.STRENGTH, 45));
        SeekBar.OnSeekBarChangeListener sliderListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                updateLabels();
                if (fromUser) saveAndApply();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        warmth.setOnSeekBarChangeListener(sliderListener);
        strength.setOnSeekBarChangeListener(sliderListener);
        findViewById(R.id.gentle).setOnClickListener(view -> preset(25, 25));
        findViewById(R.id.evening).setOnClickListener(view -> preset(60, 45));
        findViewById(R.id.deep).setOnClickListener(view -> preset(90, 80));
        findViewById(R.id.permission_button).setOnClickListener(view -> openOverlaySettings());
        findViewById(R.id.toggle).setOnClickListener(view -> {
            if (FilterService.isRunning()) {
                stopService(new Intent(this, FilterService.class));
                handler.postDelayed(this::refreshState, 100);
            } else if (!Settings.canDrawOverlays(this)) {
                pendingEnable = true;
                openOverlaySettings();
            } else {
                enableFilter();
            }
        });
        updateLabels();
        refreshState();
    }

    private void applyInsets() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    private void preset(int warmthValue, int strengthValue) {
        warmth.setProgress(warmthValue);
        strength.setProgress(strengthValue);
        updateLabels();
        saveAndApply();
    }

    private void updateLabels() {
        ((TextView) findViewById(R.id.warmth_value)).setText(getString(R.string.percentage, warmth.getProgress()));
        ((TextView) findViewById(R.id.strength_value)).setText(getString(R.string.percentage, strength.getProgress()));
    }

    private void saveAndApply() {
        FilterSettings.save(this, warmth.getProgress(), strength.getProgress());
        if (FilterService.isRunning()) {
            startService(new Intent(this, FilterService.class).setAction(FilterService.ACTION_UPDATE));
        }
    }

    private void enableFilter() {
        pendingEnable = false;
        handler.removeCallbacks(permissionResult);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !preferences.getBoolean("notificationPrompted", false)) {
            preferences.edit().putBoolean("notificationPrompted", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        try {
            startForegroundService(new Intent(this, FilterService.class).setAction(FilterService.ACTION_START));
            handler.postDelayed(this::refreshState, 200);
        } catch (IllegalStateException | SecurityException exception) {
            Toast.makeText(this, R.string.start_error, Toast.LENGTH_LONG).show();
        }
    }

    private void checkOverlayPermissionResult() {
        if (!pendingEnable) return;
        if (Settings.canDrawOverlays(this)) {
            pendingEnable = false;
            enableFilter();
            refreshState();
        } else if (permissionChecksRemaining-- > 0) {
            // Permission settings can temporarily suppress overlays during exit.
            // Retry while this activity is visible, always honoring Android's check.
            handler.postDelayed(permissionResult, 500);
        } else {
            pendingEnable = false;
            refreshState();
        }
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException exception) {
            pendingEnable = false;
            Toast.makeText(this, R.string.settings_error, Toast.LENGTH_LONG).show();
        }
    }

    private void refreshState() {
        boolean active = FilterService.isRunning();
        ((TextView) findViewById(R.id.status)).setText(active ? R.string.filter_on : R.string.filter_off);
        ((TextView) findViewById(R.id.status_detail)).setText(active ? R.string.on_detail : R.string.off_detail);
        ((Button) findViewById(R.id.toggle)).setText(active ? R.string.turn_off : R.string.turn_on);
        findViewById(R.id.permission_card).setVisibility(Settings.canDrawOverlays(this) ? View.GONE : View.VISIBLE);
        boolean notifications = getSystemService(NotificationManager.class).areNotificationsEnabled();
        findViewById(R.id.notification_tip).setVisibility(notifications ? View.GONE : View.VISIBLE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        preferences.registerOnSharedPreferenceChangeListener(stateListener);
        warmth.setProgress(preferences.getInt(FilterSettings.WARMTH, 60));
        strength.setProgress(preferences.getInt(FilterSettings.STRENGTH, 45));
        updateLabels();
        if (pendingEnable) {
            permissionChecksRemaining = 20;
            handler.postDelayed(permissionResult, 200);
        }
        refreshState();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(permissionResult);
        preferences.unregisterOnSharedPreferenceChangeListener(stateListener);
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("pendingEnable", pendingEnable);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
