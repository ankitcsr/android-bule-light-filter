package com.anknonimp.amber;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

public final class FilterTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        if (isLocked()) {
            unlockAndRun(this::toggle);
        } else {
            toggle();
        }
    }

    private void toggle() {
        if (FilterService.isRunning()) {
            stopService(new Intent(this, FilterService.class));
        } else if (Settings.canDrawOverlays(this)) {
            try {
                startForegroundService(new Intent(this, FilterService.class).setAction(FilterService.ACTION_START));
            } catch (IllegalStateException | SecurityException exception) {
                Toast.makeText(this, R.string.start_error, Toast.LENGTH_LONG).show();
            }
        } else {
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 2, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else {
                startActivityAndCollapse(open);
            }
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(FilterService.isRunning() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.app_name));
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(getString(FilterService.isRunning() ? R.string.filter_on : R.string.filter_off));
        }
        tile.updateTile();
    }
}
