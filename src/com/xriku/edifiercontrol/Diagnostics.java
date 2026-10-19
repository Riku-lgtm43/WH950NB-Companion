package com.xriku.edifiercontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Optional, local, bounded diagnostic trace. Disabled by default. Never logs Bluetooth MACs. */
public final class Diagnostics {
    private Diagnostics() {}
    private static final int MAX_BYTES = 128 * 1024;
    private static final String FILE = "edifier_trace.log";
    private static final Object LOCK = new Object();

    public static boolean enabled(Context c) {
        return c.getSharedPreferences("edifier_diagnostics",Context.MODE_PRIVATE)
                .getBoolean("enabled",false);
    }
    public static void setEnabled(Context c,boolean on) {
        synchronized (LOCK) {
            if (on) {
                // Every enabled session starts fresh, so old events can't be confused with new ones.
                clearLocked(c);
                c.getSharedPreferences("edifier_diagnostics",Context.MODE_PRIVATE)
                        .edit().putBoolean("enabled",true).apply();
                writeLocked(c,"START", "v0.3.21 sdk="+Build.VERSION.SDK_INT
                        +" uptime_ms="+SystemClock.elapsedRealtime());
            } else {
                if (enabled(c)) writeLocked(c,"STOP","Diagnostic recording disabled");
                c.getSharedPreferences("edifier_diagnostics",Context.MODE_PRIVATE)
                        .edit().putBoolean("enabled",false).apply();
            }
        }
    }
    public static void event(Context c,String type,String details) {
        if (!enabled(c)) return;
        synchronized (LOCK) {
            // Recheck in case diagnostics was disabled while waiting for this lock.
            if (enabled(c)) writeLocked(c,type,details);
        }
    }
    public static String hex(byte[] data,int start,int length) {
        if (data == null) return "none";
        StringBuilder sb = new StringBuilder();
        int to = Math.min(data.length,start+Math.max(0,length));
        for (int i = Math.max(0,start); i<to; i++) {
            int b=data[i]&255;
            if (sb.length()>0) sb.append(' ');
            sb.append(Character.forDigit((b>>>4)&15,16));
            sb.append(Character.forDigit(b&15,16));
        }
        return sb.toString().toUpperCase(Locale.ROOT);
    }
    private static File file(Context c) { return new File(c.getFilesDir(),FILE); }
    public static void clear(Context c) { synchronized(LOCK) { clearLocked(c); } }
    private static void clearLocked(Context c) { File f=file(c); if(f.exists()) f.delete(); }
    private static void writeLocked(Context c,String type,String details) {
        try {
            String date=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS",Locale.US).format(new Date());
            String data=(date+" | t="+SystemClock.elapsedRealtime()+"ms | "+type+" | "
                    +String.valueOf(details).replace('\n',' ').replace('\r',' ')+"\n");
            byte[] bytes=data.getBytes(StandardCharsets.UTF_8);
            File f=file(c);
            if (f.length()+bytes.length>MAX_BYTES) {
                // Keep the newest events; do not allow an unlimited log to fill phone storage.
                byte[] existing=readBytes(f);
                int keep=Math.min(existing.length,MAX_BYTES/2);
                int start=existing.length-keep;
                while (start<existing.length && existing[start]!='\n') start++;
                if(start<existing.length) start++;
                try (FileOutputStream trim=new FileOutputStream(f,false)) {
                    trim.write(existing,start,existing.length-start);
                }
            }
            try (FileOutputStream os=new FileOutputStream(f,true)) { os.write(bytes); }
        } catch (Exception ignored) {
            // Logging must never prevent a headphone command from executing.
        }
    }
    private static byte[] readBytes(File f) throws Exception {
        try (FileInputStream in=new FileInputStream(f);
             ByteArrayOutputStream os=new ByteArrayOutputStream()) {
            byte[] buf=new byte[4096]; int n;
            while((n=in.read(buf))!=-1) os.write(buf,0,n);
            return os.toByteArray();
        }
    }
    public static String read(Context c) {
        synchronized (LOCK) {
            File f=file(c);
            if(!f.exists()) return "";
            try { return new String(readBytes(f),StandardCharsets.UTF_8); }
            catch(Exception e) { return "Log could not be read: "+e.getClass().getSimpleName(); }
        }
    }
}
