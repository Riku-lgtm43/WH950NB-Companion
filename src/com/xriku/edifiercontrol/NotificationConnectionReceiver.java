package com.xriku.edifiercontrol;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/** Reacts to Android's Bluetooth events while the optional notification is enabled.
 * No polling, foreground service, or background Bluetooth socket is started.
 */
public final class NotificationConnectionReceiver extends BroadcastReceiver {
    private static final AtomicInteger EVENTS = new AtomicInteger();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !Prefs.showNotification(context)) return;
        final Context app = context.getApplicationContext();
        final String action = intent.getAction();
        if (action == null) return;

        if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state != BluetoothAdapter.STATE_ON && state != BluetoothAdapter.STATE_OFF) return;
        } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)
                || BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)
                || BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                || BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
            BluetoothDevice device;
            try { device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE); }
            catch (Exception exception) { return; }
            if (device == null || !isOurHeadset(app,device)) return;
            if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,-1);
                if (state != BluetoothProfile.STATE_CONNECTED
                        && state != BluetoothProfile.STATE_DISCONNECTED) return;
            }
        } else return;

        refreshConnection(app, goAsync());
    }

    /** Recheck actual A2DP/Headset state rather than trusting an old saved flag.
     * The optional PendingResult keeps manifest-delivered broadcasts alive during
     * the bounded profile lookup; in-app refreshes can pass null.
     */
    static void refreshConnection(Context context, PendingResult pending) {
        final Context app = context.getApplicationContext();
        final int generation = EVENTS.incrementAndGet();
        if (!Prefs.showNotification(app)) {
            try { NotificationHelper.hide(app); }
            finally { if (pending != null) pending.finish(); }
            return;
        }
        if (!BluetoothController.permitted(app)) {
            try { setDisconnected(app); }
            finally { if (pending != null) pending.finish(); }
            return;
        }
        try {
            // Profile state, not ACL alone, determines notification visibility.
            ConnectedHeadphones.find(app,(device,description) -> {
                try {
                    if (generation != EVENTS.get() || !Prefs.showNotification(app)) {
                        if (!Prefs.showNotification(app)) NotificationHelper.hide(app);
                        return;
                    }
                    if (device == null) setDisconnected(app);
                    else {
                        String address = device.getAddress();
                        Prefs.mac(app,address);
                        Prefs.rememberConnectedMac(app,address);
                        Prefs.headphonesConnected(app,true);
                        NotificationHelper.refreshIfEnabled(app);
                        Tiles.refresh(app);
                    }
                    Diagnostics.event(app,"NOTIFICATION_CONNECTION",
                            "connected="+(device!=null)+" detail="+description);
                } catch (Exception ignored) {
                } finally { if (pending != null) pending.finish(); }
            });
        } catch (Exception exception) {
            if (pending != null) pending.finish();
        }
    }

    static void setDisconnected(Context app) {
        Prefs.mac(app,"");
        Prefs.headphonesConnected(app,false);
        Prefs.clearObservedNoise(app);
        NotificationHelper.refreshIfEnabled(app);
        Tiles.refresh(app);
    }

    private static boolean isOurHeadset(Context app,BluetoothDevice device) {
        try {
            String address=device.getAddress();
            if (address != null && !address.isEmpty()) {
                if (address.equalsIgnoreCase(Prefs.mac(app))
                        || address.equalsIgnoreCase(Prefs.lastConnectedMac(app))) return true;
            }
            String name=device.getName();
            return name != null && name.toUpperCase(Locale.ROOT).contains("WH950NB");
        } catch (Exception exception) { return false; }
    }
}
