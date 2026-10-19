package com.xriku.edifiercontrol;

import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.service.quicksettings.TileService;

final class Tiles {
    private Tiles() {
    }

    static void refresh(Context context) {
        if (Build.VERSION.SDK_INT < 24) {
            return;
        }

        Class<?>[] tileClasses = {AncTile.class, AmbientTile.class, WindTile.class};
        for (Class<?> tileClass : tileClasses) {
            try {
                ComponentName component = new ComponentName(context, tileClass);
                TileService.requestListeningState(context, component);
            } catch (Exception ignored) {
            }
        }
    }
}
