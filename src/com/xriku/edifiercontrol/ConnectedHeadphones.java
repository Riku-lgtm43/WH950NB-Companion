package com.xriku.edifiercontrol;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Detect connected WH950NB using A2DP / Headset profiles (not the bonded list).
 * Uses no Bluetooth scan and no background polling. All profile proxies close.
 */
final class ConnectedHeadphones {
    interface Result {
        void onResult(BluetoothDevice device, String description);
    }
    private ConnectedHeadphones() { }

    static void find(Context c, Result result) {
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (!BluetoothController.permitted(app)) {
            main.post(() -> result.onResult(null, "Nearby devices permission needed"));
            return;
        }
        BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            main.post(() -> result.onResult(null, "Bluetooth is off"));
            return;
        }
        main.post(() -> new Session(app, adapter, result, main).start());
    }

    // Named implementation avoids Termux JDK21/older D8 anonymous class issues.
    private static final class Session implements BluetoothProfile.ServiceListener, Runnable {
        private final Context app;
        private final BluetoothAdapter adapter;
        private final Result callback;
        private final Handler handler;
        private final Map<String, BluetoothDevice> connected = new LinkedHashMap<>();
        private int remaining = 2;
        private boolean finished = false;

        Session(Context app, BluetoothAdapter adapter, Result callback, Handler handler) {
            this.app = app;
            this.adapter = adapter;
            this.callback = callback;
            this.handler = handler;
        }
        void start() {
            try {
                if (!adapter.getProfileProxy(app, this, BluetoothProfile.A2DP)) remaining--;
                if (!adapter.getProfileProxy(app, this, BluetoothProfile.HEADSET)) remaining--;
            } catch (Exception error) {
                remaining = 0;
            }
            if (remaining <= 0) run();
            else handler.postDelayed(this, 2500); // Never wait indefinitely.
        }
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            try {
                if (!finished) {
                    for (BluetoothDevice device : proxy.getConnectedDevices()) {
                        String name = device.getName();
                        if (name != null && name.toUpperCase(Locale.ROOT).contains("WH950NB")) {
                            connected.put(device.getAddress(), device);
                        }
                    }
                }
            } catch (SecurityException ignored) {
            } finally {
                try { adapter.closeProfileProxy(profile, proxy); }
                catch (Exception ignored) { }
            }
            remaining--;
            if (remaining <= 0) run();
        }
        @Override public void onServiceDisconnected(int profile) {
            remaining--;
            if (remaining <= 0) run();
        }
        @Override public void run() {
            if (finished) return;
            finished = true;
            handler.removeCallbacks(this);
            BluetoothDevice previous = connected.get(Prefs.mac(app));
            BluetoothDevice chosen = previous;
            if (chosen == null && connected.size() == 1) {
                chosen = connected.values().iterator().next();
            }
            if (chosen != null) callback.onResult(chosen, "WH950NB connected");
            else if (connected.size() > 1) callback.onResult(null, "Multiple WH950NB devices connected");
            else callback.onResult(null, "No connected WH950NB");
        }
    }
}
