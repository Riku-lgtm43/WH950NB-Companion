package com.xriku.edifiercontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;

/** Notification actions enter the same command queue as the app and QS tiles. */
public class NotificationActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action=intent==null?null:intent.getStringExtra("command");
        if (!Protocol.ANC.equals(action) && !Protocol.AMBIENT.equals(action)
                && !Protocol.WIND.equals(action)) return;
        Diagnostics.event(context,"NOTIFICATION_TAP","action="+action);
        Context app=context.getApplicationContext();
        PendingResult pending=goAsync();
        AtomicBoolean finished=new AtomicBoolean(false);
        Handler handler=new Handler(Looper.getMainLooper());
        Runnable finish=() -> {
            if (finished.compareAndSet(false,true)) pending.finish();
        };
        // BroadcastReceiver work has a strict OS lifetime; the coordinator may still
        // finish later if Android keeps the process alive. Avoid hanging the receiver.
        handler.postDelayed(finish,8500);
        BluetoothController.sendVerified(app,action,result -> {
            Diagnostics.event(app,"NOTIFICATION_RESULT","action="+action
                    +" sent="+result.sent+" verdict="+result.verdict+" message="+result.message);
            if (!result.sent) Log.w("EdifierControl","Notification action: "+result.message);
            handler.removeCallbacks(finish);
            finish.run();
        });
    }
}
