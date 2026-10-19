package com.xriku.edifiercontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.RemoteViews;

/** Optional ongoing notification; does not start a foreground service. */
public final class NotificationHelper {
    private NotificationHelper() {}
    private static final String CHANNEL_ID = "edifier_quick_controls";
    private static final int ID = 1950;
    static boolean canShow(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }
    public static void show(Context c) {
        // Do not keep a disconnected-headphones notification in the shade.
        if (!Prefs.showNotification(c) || !Prefs.headphonesConnected(c)) {
            hide(c);
            return;
        }
        if (!canShow(c)) return;
        NotificationManager manager = (NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "WH950NB quick controls", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Manual noise control shortcuts");
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);

        Intent main = new Intent(c, MainActivity.class);
        main.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(c, 0, main, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        RemoteViews panel = new RemoteViews(c.getPackageName(), R.layout.notification_controls);
        boolean connected = Prefs.headphonesConnected(c);
        String status = Prefs.noiseNotificationStatus(c);
        panel.setTextViewText(R.id.notif_status, status);
        String selected = connected ? Prefs.notificationAction(c) : "";
        String stage = connected ? Prefs.notificationStage(c) : "";
        styleNotificationButton(panel, R.id.notif_anc_label, Protocol.ANC, "ANC", selected, stage);
        styleNotificationButton(panel, R.id.notif_ambient_label, Protocol.AMBIENT, "Ambient", selected, stage);
        styleNotificationButton(panel, R.id.notif_wind_label, Protocol.WIND, "Wind", selected, stage);
        if (connected) {
            panel.setOnClickPendingIntent(R.id.notif_anc, action(c, Protocol.ANC, 1001));
            panel.setOnClickPendingIntent(R.id.notif_ambient, action(c, Protocol.AMBIENT, 1002));
            panel.setOnClickPendingIntent(R.id.notif_wind, action(c, Protocol.WIND, 1003));
        } else {
            // Remove any tap handlers retained when the system reuses a view.
            panel.setOnClickPendingIntent(R.id.notif_anc, null);
            panel.setOnClickPendingIntent(R.id.notif_ambient, null);
            panel.setOnClickPendingIntent(R.id.notif_wind, null);
            panel.setTextColor(R.id.notif_anc_label, 0xFF77777D);
            panel.setTextColor(R.id.notif_ambient_label, 0xFF77777D);
            panel.setTextColor(R.id.notif_wind_label, 0xFF77777D);
        }

        Notification notice = new Notification.Builder(c, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_headphones)
                .setContentTitle("WH950NB Companion")
                .setContentText(status)
                .setContentIntent(open)
                .setCustomBigContentView(panel)
                .setStyle(new Notification.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
        manager.notify(ID, notice);
    }
    // RemoteViews-safe text styling works in system notification shades.
    // The prefix and color show the outcome without falsely claiming a verified mode.
    private static void styleNotificationButton(RemoteViews panel, int id, String command,
                                                 String label, String selected, String stage) {
        boolean active = command.equals(selected) || selected.startsWith(command + " ");
        String prefix = "";
        int color = 0xFFF0F0F4;
        if (active) {
            if ("sending".equals(stage) || "queued".equals(stage)
                    || "checking".equals(stage)) {
                prefix="… "; color=0xFFFFC66D;
            } else if ("failed".equals(stage) || "different".equals(stage)
                    || "unverified".equals(stage)) {
                prefix="✗ "; color=0xFFFF8989;
            } else if ("verified".equals(stage) || "reported".equals(stage)
                    || "observed".equals(stage)) {
                prefix="✓ "; color=0xFF8ED5C8;
            }
        }
        panel.setTextViewText(id, prefix + label);
        panel.setTextColor(id, color);
    }
    private static PendingIntent action(Context c, String name, int code) {
        Intent i = new Intent(c, NotificationActionReceiver.class);
        i.setAction("com.xriku.edifiercontrol.SEND_" + name);
        i.putExtra("command", name);
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    public static void refreshIfEnabled(Context c) {
        if (Prefs.showNotification(c) && Prefs.headphonesConnected(c)) show(c);
        else hide(c);
    }
    public static void hide(Context c) {
        NotificationManager manager = (NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.cancel(ID);
    }
}
