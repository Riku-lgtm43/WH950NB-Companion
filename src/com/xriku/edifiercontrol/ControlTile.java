package com.xriku.edifiercontrol;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.graphics.drawable.Icon;
import android.widget.Toast;
import android.util.Log;

/** Tile state is refreshed from connected device + last observed headphones mode. */
public abstract class ControlTile extends TileService {
    protected abstract String command();
    protected abstract int iconId();
    protected abstract String tileLabel();
    @Override public void onStartListening() {
        super.onStartListening();
        updateTileState(false);
        // Avoid claiming a stale connection from the last time the app was open.
        ConnectedHeadphones.find(this,(device,description) -> {
            if (device!=null) Prefs.mac(this,device.getAddress());
            updateTileState(device!=null);
        });
    }
    private void updateTileState(boolean connected) {
        Tile tile=getQsTile();
        if (tile==null) return;
        tile.setLabel(tileLabel());
        tile.setIcon(Icon.createWithResource(this,iconId()));
        String request=Prefs.notificationAction(this);
        String stage=Prefs.notificationStage(this);
        boolean matches=command().equals(request) || request.startsWith(command()+" ");
        String mode=Prefs.get(this).getString("observed_noise_mode","");
        boolean confirmed=matches && ("verified".equals(stage) || "reported".equals(stage));
        boolean observedNow="observed".equals(stage) && command().equals(mode);
        boolean active=confirmed || observedNow;
        tile.setContentDescription(tileLabel()+(active?" · ✓ Confirmed":
                matches && ("failed".equals(stage) || "unverified".equals(stage)
                        || "different".equals(stage))?" · ✗ Not confirmed":""));
        boolean available=connected && BluetoothController.permitted(this);
        tile.setState(!available?Tile.STATE_UNAVAILABLE:
                active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
    @Override public void onClick() {
        super.onClick();
        Diagnostics.event(this,"TILE_TAP","action="+command());
        if (!BluetoothController.permitted(this)) {
            Toast.makeText(this,"Nearby devices permission required",Toast.LENGTH_SHORT).show();
            return;
        }
        BluetoothController.sendVerified(this,command(),outcome -> {
            Diagnostics.event(this,"TILE_RESULT","action="+command()+" sent="+outcome.sent
                    +" verdict="+outcome.verdict+" message="+outcome.message);
            if (!outcome.sent) {
                Log.w("EdifierControl","Tile: "+outcome.message);
                Toast.makeText(this,"WH950NB: "+outcome.message,Toast.LENGTH_SHORT).show();
            }
            // Tiles.refresh() requests a new listening-state callback.
            Tiles.refresh(this);
        });
    }
}
