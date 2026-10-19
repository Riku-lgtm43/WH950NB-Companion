package com.xriku.edifiercontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && Prefs.showNotification(c)) {
            Prefs.headphonesConnected(c, false);
            Prefs.clearObservedNoise(c);
            NotificationHelper.hide(c);
            // Bluetooth may already be connected by the time boot completes.
            NotificationConnectionReceiver.refreshConnection(c, goAsync());
        }
    }
}
