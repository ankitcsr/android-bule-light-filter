package com.anknonimp.amber;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

public final class FilterService extends Service {
    static final String ACTION_START = "com.anknonimp.amber.START";
    static final String ACTION_UPDATE = "com.anknonimp.amber.UPDATE";
    static final String ACTION_STOP = "com.anknonimp.amber.STOP";
    private static final String CHANNEL = "screen_filter";
    private static volatile boolean running;
    private WindowManager windowManager;
    private WindowManager.LayoutParams layout;
    private View overlay;

    static boolean isRunning() {
        return running;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = getSystemService(WindowManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!Settings.canDrawOverlays(this)
                || (intent == null && !FilterSettings.preferences(this).getBoolean(FilterSettings.ENABLED, false))
                || (intent != null && ACTION_UPDATE.equals(intent.getAction()) && !running)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification notification = notification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, notification);
            }
            applyOverlay();
            running = true;
            FilterSettings.preferences(this).edit().putBoolean(FilterSettings.ENABLED, true).apply();
            refreshTile();
            return START_STICKY;
        } catch (RuntimeException exception) {
            Toast.makeText(this, R.string.overlay_error, Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void applyOverlay() {
        SharedPreferences preferences = FilterSettings.preferences(this);
        if (overlay == null) {
            overlay = new View(this);
            overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            layout.gravity = Gravity.TOP | Gravity.START;
            if (Build.VERSION.SDK_INT >= 28) {
                layout.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            if (Build.VERSION.SDK_INT >= 30) layout.setFitInsetsTypes(0);
        }
        overlay.setBackgroundColor(FilterMath.color(preferences.getInt(FilterSettings.WARMTH, 60)));
        layout.alpha = FilterMath.opacity(preferences.getInt(FilterSettings.STRENGTH, 45));
        if (overlay.isAttachedToWindow()) {
            windowManager.updateViewLayout(overlay, layout);
        } else {
            windowManager.addView(overlay, layout);
        }
    }

    private Notification notification() {
        int immutable = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        Intent activity = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, activity, immutable);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, FilterService.class).setAction(ACTION_STOP), immutable);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_moon)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_detail))
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(R.drawable.ic_moon, getString(R.string.stop), stop).build())
                .build();
    }

    private void refreshTile() {
        TileService.requestListeningState(this, new ComponentName(this, FilterTileService.class));
    }

    @Override
    public void onDestroy() {
        if (overlay != null && overlay.isAttachedToWindow()) windowManager.removeViewImmediate(overlay);
        overlay = null;
        running = false;
        FilterSettings.preferences(this).edit().putBoolean(FilterSettings.ENABLED, false).apply();
        refreshTile();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
