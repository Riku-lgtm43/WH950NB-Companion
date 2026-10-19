package com.xriku.edifiercontrol;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothCodecConfig;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Binder;
import android.os.Parcel;
import android.os.RemoteException;

import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs as a Shizuku User Service, only after explicit user authorization.
 * Experimental on Android 16: the service may not be able to obtain an A2DP proxy.
 * It never reports success without the Android codec report confirming it.
 */
public final class CodecShizukuService extends Binder {
    public static final int REQUEST = 100;
    private final Context context;

    public CodecShizukuService(Context context) { this.context = context; }
    public CodecShizukuService() { this.context = null; }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        if (code == REQUEST) {
            String wanted = data.readString();
            String mac = data.readString();
            String response;
            try { response = execute(wanted,mac); }
            catch (Throwable error) {
                response = result("ERROR","—","","UNEXPECTED_SERVICE_ERROR");
            }
            reply.writeNoException();
            reply.writeString(response);
            return true;
        }
        if (code == 16777114 || code == 16777115) {
            reply.writeNoException();
            new CodecExitThread().start();
            return true;
        }
        return super.onTransact(code,data,reply,flags);
    }

    // Named listener and worker avoid anonymous classes that older Termux D8 cannot process.
    private static final class CodecExitThread extends Thread {
        @Override public void run() { System.exit(0); }
    }

    private static final class CodecProfileListener implements BluetoothProfile.ServiceListener {
        private final BluetoothA2dp[] proxy;
        private final CountDownLatch ready;
        CodecProfileListener(BluetoothA2dp[] proxy, CountDownLatch ready) {
            this.proxy=proxy;
            this.ready=ready;
        }
        @Override public void onServiceConnected(int type, BluetoothProfile service) {
            if (type==BluetoothProfile.A2DP && service instanceof BluetoothA2dp)
                proxy[0]=(BluetoothA2dp)service;
            ready.countDown();
        }
        @Override public void onServiceDisconnected(int type) { ready.countDown(); }
    }

    private static String result(String kind,String current,String available,String message) {
        return "CODEC_RESULT|"+kind+"|"+current+"|"+available+"|"+message;
    }

    // In Shizuku's separate process, Android may not initialize its Bluetooth
    // framework automatically. Initialize only this process's local registration;
    // never change device Bluetooth state or Android system settings.
    private static String prepareBluetoothFramework() {
        try {
            Class<?> initializer=Class.forName("android.bluetooth.BluetoothFrameworkInitializer");
            Method get=initializer.getDeclaredMethod("getBluetoothServiceManager");
            get.setAccessible(true);
            if (get.invoke(null)!=null) return "READY";
            Class<?> managerClass=Class.forName("android.os.BluetoothServiceManager");
            java.lang.reflect.Constructor<?> constructor=managerClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object manager=constructor.newInstance();
            Method set=initializer.getDeclaredMethod("setBluetoothServiceManager",managerClass);
            set.setAccessible(true);
            set.invoke(null,manager);
            return "INITIALIZED";
        } catch (ClassNotFoundException missing) {
            return "BLUETOOTH_BRIDGE_UNSUPPORTED";
        } catch (NoSuchMethodException missing) {
            return "BLUETOOTH_BRIDGE_UNSUPPORTED";
        } catch (SecurityException denied) {
            return "BLUETOOTH_BRIDGE_DENIED";
        } catch (Throwable failed) {
            return "BLUETOOTH_BRIDGE_FAILED";
        }
    }

    private static CodecDumpReader.Snapshot snapshot() throws Exception {
        Process proc=new ProcessBuilder("/system/bin/dumpsys","bluetooth_manager")
                .redirectErrorStream(true).start();
        CodecDumpReader.Snapshot state;
        try (InputStreamReader reader=new InputStreamReader(proc.getInputStream(),"UTF-8")) {
            state=CodecDumpReader.parse(reader);
        }
        if (!proc.waitFor(6,TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IllegalStateException("report timeout");
        }
        if (proc.exitValue()!=0) throw new IllegalStateException("report error");
        return state;
    }

    private String execute(String wanted,String mac) {
        if (!("LDAC".equals(wanted)||"AAC".equals(wanted)||"SBC".equals(wanted))
                || mac==null || !mac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))
            return result("ERROR","—","","Invalid codec request");
        CodecDumpReader.Snapshot before;
        try { before=snapshot(); }
        catch (Exception ignored) { return result("ERROR","—","","REPORT_NOT_AVAILABLE"); }
        if (wanted.equals(before.current))
            return result("SWITCHED",before.current,before.available,"Already selected");
        if (!( ","+before.available+",").contains(","+wanted+","))
            return result("ERROR",before.current,before.available,"Codec unavailable for headphones");
        if (context==null)
            return result("ERROR",before.current,before.available,"CONTEXT_MISSING");
        // A normal app has this manager ready. A root Shizuku User Service
        // can start without it, causing getDefaultAdapter() to return null.
        BluetoothAdapter adapter;
        try { adapter=BluetoothAdapter.getDefaultAdapter(); }
        catch (Throwable ignored) { adapter=null; }
        if (adapter==null) {
            String initialized=prepareBluetoothFramework();
            if (!"READY".equals(initialized) && !"INITIALIZED".equals(initialized))
                return result("ERROR",before.current,before.available,initialized);
            try { adapter=BluetoothAdapter.getDefaultAdapter(); }
            catch (Throwable ignored) { adapter=null; }
        }
        if (adapter==null)
            return result("ERROR",before.current,before.available,"BLUETOOTH_BRIDGE_STILL_UNAVAILABLE");
        final BluetoothA2dp[] proxy = new BluetoothA2dp[1];
        final CountDownLatch ready=new CountDownLatch(1);
        final BluetoothProfile.ServiceListener listener = new CodecProfileListener(proxy,ready);
        try {
            boolean requested=adapter.getProfileProxy(context,listener,BluetoothProfile.A2DP);
            if (!requested)
                return result("ERROR",before.current,before.available,"PROFILE_REQUEST_REJECTED");
            if (!ready.await(4L,TimeUnit.SECONDS))
                return result("ERROR",before.current,before.available,"PROFILE_CONNECTION_TIMEOUT");
            if (proxy[0]==null)
                return result("ERROR",before.current,before.available,"PROFILE_UNAVAILABLE");
            BluetoothDevice device=adapter.getRemoteDevice(mac);
            boolean matched=false;
            for (BluetoothDevice connected:proxy[0].getConnectedDevices()) {
                if (mac.equalsIgnoreCase(connected.getAddress())) { matched=true; break; }
            }
            if (!matched)
                return result("ERROR",before.current,before.available,"HEADPHONES_NOT_ACTIVE");
            Method read=BluetoothA2dp.class.getDeclaredMethod("getCodecStatus",BluetoothDevice.class);
            read.setAccessible(true);
            BluetoothCodecStatus state=(BluetoothCodecStatus)read.invoke(proxy[0],device);
            if (state==null)
                return result("ERROR",before.current,before.available,"CODEC_DETAILS_UNAVAILABLE");
            int type="LDAC".equals(wanted)?BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC
                    :"AAC".equals(wanted)?BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC
                    :BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC;
            boolean selectable=false;
            List<BluetoothCodecConfig> configs=state.getCodecsSelectableCapabilities();
            if (configs!=null) {
                for (BluetoothCodecConfig c:configs) {
                    if (c.getCodecType()==type) { selectable=true; break; }
                }
            }
            if (!selectable)
                return result("ERROR",before.current,before.available,"CODEC_NOT_SELECTABLE");
            BluetoothCodecConfig preference=new BluetoothCodecConfig.Builder()
                    .setCodecType(type)
                    .setCodecPriority(BluetoothCodecConfig.CODEC_PRIORITY_HIGHEST)
                    .build();
            Method write=BluetoothA2dp.class.getDeclaredMethod("setCodecConfigPreference",
                    BluetoothDevice.class,BluetoothCodecConfig.class);
            write.setAccessible(true);
            write.invoke(proxy[0],device,preference);
        } catch (SecurityException denied) {
            return result("ERROR",before.current,before.available,"PERMISSION_BLOCKED");
        } catch (NoSuchMethodException missing) {
            return result("ERROR",before.current,before.available,"ANDROID_METHOD_MISSING");
        } catch (java.lang.reflect.InvocationTargetException callFailed) {
            Throwable cause=callFailed.getCause();
            return result("ERROR",before.current,before.available,
                    cause instanceof SecurityException ? "PERMISSION_BLOCKED" : "ANDROID_CALL_FAILED");
        } catch (Exception error) {
            return result("ERROR",before.current,before.available,"ANDROID_CALL_FAILED");
        } finally {
            try { if (proxy[0]!=null) adapter.closeProfileProxy(BluetoothProfile.A2DP,proxy[0]); }
            catch (Exception ignored) { }
        }
        // Verify what Android actually negotiated, not whether a command was sent.
        CodecDumpReader.Snapshot latest=before;
        for (int i=0;i<5;i++) {
            try {
                Thread.sleep(450L);
                latest=snapshot();
                if (wanted.equals(latest.current))
                    return result("SWITCHED",latest.current,latest.available,"Verified");
            } catch (Exception ignored) { }
        }
        return result("UNCHANGED",latest.current,latest.available,"CODEC_STAYED_THE_SAME");
    }
}
