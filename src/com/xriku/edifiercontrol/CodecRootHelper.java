package com.xriku.edifiercontrol;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Method;
import java.util.List;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Invoked ONLY by an explicit app action through KernelSU's su command.
 * A separate, short-lived privileged process; not a background service.
 * Prints a machine-readable, fact-checked result. Does not change anything
 * unless the selected codec is in the device's selectable capability list.
 */
public final class CodecRootHelper {
    private static void result(String type,String codec,String available,String detail) {
        System.out.println("CODEC_RESULT|" + type + "|" + codec + "|" + available + "|" + detail);
        System.out.flush();
    }
    private static String label(BluetoothCodecConfig c) {
        if (c==null) return "—";
        switch (c.getCodecType()) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC: return "LDAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC: return "AAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC: return "SBC";
            default: return "Other";
        }
    }
    private static boolean isSupportedCodec(String name) {
        return "LDAC".equals(name) || "AAC".equals(name) || "SBC".equals(name);
    }

    private static boolean containsCodec(String available, String codec) {
        return ("," + available + ",").contains("," + codec + ",");
    }

    private static String capabilities(List<BluetoothCodecConfig> configs) {
        if (configs==null) return "";
        StringBuilder b=new StringBuilder();
        for (BluetoothCodecConfig c:configs) {
            String name=label(c);
            if (!isSupportedCodec(name)) continue;
            if (containsCodec(b.toString(), name)) continue;
            if (b.length()>0) b.append(',');
            b.append(name);
        }
        return b.toString();
    }
    private static BluetoothCodecStatus read(BluetoothA2dp proxy,BluetoothDevice dev) throws Exception {
        Method m=BluetoothA2dp.class.getDeclaredMethod("getCodecStatus",BluetoothDevice.class);
        m.setAccessible(true);
        return (BluetoothCodecStatus) m.invoke(proxy,dev);
    }
    private static void switchTo(BluetoothA2dp proxy,BluetoothDevice dev,String name) throws Exception {
        int type = "LDAC".equals(name) ? BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
                 : "AAC".equals(name) ? BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC
                 : BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC;
        BluetoothCodecConfig config=new BluetoothCodecConfig.Builder()
                .setCodecType(type)
                .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                .build();
        Method m=BluetoothA2dp.class.getDeclaredMethod("setCodecConfigPreference",
                BluetoothDevice.class,BluetoothCodecConfig.class);
        m.setAccessible(true);
        m.invoke(proxy,dev,config);
    }
    private static void handle(BluetoothA2dp proxy,BluetoothDevice device,String wanted) {
        String current="—",available="";
        try {
            boolean connected=false;
            for (BluetoothDevice d:proxy.getConnectedDevices()) {
                if (device.getAddress().equalsIgnoreCase(d.getAddress())) { connected=true;break; }
            }
            if (!connected) { result("ERROR",current,available,"WH950NB is not connected for music audio"); return; }
            BluetoothCodecStatus state=read(proxy,device);
            if (state==null) { result("ERROR",current,available,"Android did not provide codec details"); return; }
            current=label(state.getCodecConfig());
            available=capabilities(state.getCodecsSelectableCapabilities());
            if ("STATUS".equals(wanted) || wanted.equals(current)) {
                result("OK",current,available,"Codec read"); return;
            }
            if (!containsCodec(available, wanted)) {
                result("ERROR",current,available,"Codec not selectable for WH950NB"); return;
            }
            switchTo(proxy,device,wanted);
            // A request being accepted is not proof of a completed switch.
            // Read the actual negotiated codec until it matches or times out.
            for (int i=0;i<5;i++) {
                Thread.sleep(450L);
                BluetoothCodecStatus after=read(proxy,device);
                if (after!=null) {
                    current=label(after.getCodecConfig());
                    available=capabilities(after.getCodecsSelectableCapabilities());
                    if (wanted.equals(current)) {
                        result("SWITCHED",current,available,"Switch confirmed"); return;
                    }
                }
            }
            result("UNCHANGED",current,available,"Headphones still report previous codec");
        } catch (Exception error) {
            result("ERROR",current,available,"Android blocked codec access");
        }
    }
    // Avoid anonymous classes that can crash the older D8 used in Termux.
    private static final class CodecProfileListener implements BluetoothProfile.ServiceListener {
        private final BluetoothAdapter adapter;
        private final BluetoothDevice device;
        private final String wanted;
        CodecProfileListener(BluetoothAdapter adapter,BluetoothDevice device,String wanted) {
            this.adapter=adapter;
            this.device=device;
            this.wanted=wanted;
        }
        @Override public void onServiceConnected(int type,BluetoothProfile profile) {
            try {
                if (type==BluetoothProfile.A2DP && profile instanceof BluetoothA2dp)
                    handle((BluetoothA2dp)profile,device,wanted);
                else result("ERROR","—","","Music audio service unavailable");
            } finally {
                try { adapter.closeProfileProxy(BluetoothProfile.A2DP,profile); }
                catch (Exception ignored) { }
                System.exit(0);
            }
        }
        @Override public void onServiceDisconnected(int type) { }
    }
    public static void main(String[] args) {
        if (args.length!=2) { result("ERROR","—","","Invalid request"); return; }
        final String wanted=args[0],mac=args[1];
        if (!("STATUS".equals(wanted)||"LDAC".equals(wanted)||"AAC".equals(wanted)||"SBC".equals(wanted))
                || !mac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
            result("ERROR","—","","Invalid codec or device"); return;
        }
        // Read current codec through the same Android report that succeeds
        // from root Termux. The separate app_process Bluetooth API is not
        // reliable on this Android 16 build.
        if ("STATUS".equals(wanted)) {
            Process report = null;
            try {
                report = new ProcessBuilder("/system/bin/dumpsys", "bluetooth_manager")
                        .redirectErrorStream(true).start();
                CodecDumpReader.Snapshot snapshot = CodecDumpReader.parse(
                        new InputStreamReader(report.getInputStream(), "UTF-8"));
                if (!report.waitFor(7L, TimeUnit.SECONDS)) {
                    report.destroyForcibly();
                    result("ERROR", "—", "", "Codec check timed out");
                } else if (report.exitValue() != 0 || snapshot.current.isEmpty()) {
                    result("ERROR", "—", "", "Android did not report an active music codec");
                } else {
                    result("OK", snapshot.current, snapshot.available, "Codec read");
                }
            } catch (Exception failure) {
                if (report != null) report.destroyForcibly();
                result("ERROR", "—", "", "Unable to read Android Bluetooth codec report");
            }
            return;
        }
        try {
            if (Looper.myLooper()==null) Looper.prepareMainLooper();
            // app_process is a privileged process, not the user app process.
            // Android 16 may still disallow these internal platform calls.
            Class<?> cls=Class.forName("android.app.ActivityThread");
            Method systemMain=cls.getDeclaredMethod("systemMain");
            systemMain.setAccessible(true);
            Object activityThread=systemMain.invoke(null);
            Method getSystemContext=cls.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Context context=(Context)getSystemContext.invoke(activityThread);
            BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
            if (adapter==null) {
                result("ERROR","—","","Android codec service unavailable"); return;
            }
            // Android 16 can report 'off' to a separate root app_process even
            // when Bluetooth is connected in the actual app. Do not use this
            // unreliable check to reject the request; try the music service.
            BluetoothDevice device=adapter.getRemoteDevice(mac);
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                result("ERROR","—","","Bluetooth codec request timed out");
                System.exit(1);
            },5500L);
            boolean asked=adapter.getProfileProxy(context,
                    new CodecProfileListener(adapter,device,wanted),BluetoothProfile.A2DP);
            if (!asked) { result("ERROR","—","","Android refused codec service access"); return; }
            Looper.loop();
        } catch (SecurityException denied) {
            result("ERROR","—","","Android denied codec access");
        } catch (Throwable failure) {
            result("ERROR","—","","Codec service could not start");
        }
    }
}
