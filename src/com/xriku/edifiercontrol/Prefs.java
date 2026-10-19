package com.xriku.edifiercontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores user settings and the last state confirmed by the headphones. */
final class Prefs {
    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("edifier_settings", Context.MODE_PRIVATE);
    }
    static String mac(Context c) { return get(c).getString("mac",""); }
    static void mac(Context c,String value) { get(c).edit().putString("mac",value).apply(); }
    static boolean isNoiseCommand(String action) {
        return action!=null && (action.equals(Protocol.ANC) || action.startsWith("ANC ")
                || action.equals(Protocol.AMBIENT) || action.startsWith("AMBIENT ")
                || action.equals(Protocol.WIND) || action.equals(Protocol.OFF));
    }
    static boolean validNoiseState(Protocol.State state) {
        if (state==null) return false;
        if (Protocol.WIND.equals(state.mode) || Protocol.OFF.equals(state.mode)) return true;
        if (!Protocol.ANC.equals(state.mode) && !Protocol.AMBIENT.equals(state.mode)) return false;
        try {
            int n=Integer.parseInt(state.level);
            return Protocol.ANC.equals(state.mode) ? n>=1 && n<=8 : n>=-3 && n<=2;
        } catch (NumberFormatException error) { return false; }
    }
    static void clearObservedNoise(Context c) {
        get(c).edit().remove("observed_noise_mode").remove("observed_noise_level")
                .remove("observed_noise_confidence").remove("observed_noise_at")
                .remove("notification_stage").remove("notification_action").apply();
    }
    static void noiseRequested(Context c,String action) {
        if (!isNoiseCommand(action)) return;
        get(c).edit().putString("last_noise_request",action).apply();
    }
    static void observedNoise(Context c,Protocol.State state) {
        if (!validNoiseState(state)) return;
        get(c).edit().putString("observed_noise_mode",state.mode)
                .putString("observed_noise_level",state.level)
                .putString("observed_noise_confidence",state.confidence)
                .putLong("observed_noise_at",System.currentTimeMillis()).apply();
    }
    static void commandFeedback(Context c,String action,String stage) {
        if (!isNoiseCommand(action)) return;
        get(c).edit().putString("notification_action",action)
                .putString("notification_stage",stage)
                .putLong("notification_feedback_at",System.currentTimeMillis()).apply();
    }
    static String notificationAction(Context c) {
        String requested=get(c).getString("notification_action","");
        if (!requested.isEmpty()) return requested;
        String observed=get(c).getString("observed_noise_mode","");
        return observed.isEmpty()?get(c).getString("last_noise_request",""):observed;
    }
    static String notificationStage(Context c) {
        String stage=get(c).getString("notification_stage","");
        long at=get(c).getLong("notification_feedback_at",0);
        if (("sending".equals(stage) || "queued".equals(stage) || "checking".equals(stage))
                && System.currentTimeMillis()-at>20000L) return "unverified";
        return stage;
    }
    private static String pretty(String command) {
        if (command==null || command.isEmpty()) return "noise control";
        if (command.startsWith("AMBIENT")) return "Ambient"+command.substring(7);
        if (command.equals("WIND")) return "Wind";
        if (command.equals("OFF")) return "Off";
        return command;
    }
    static String noiseNotificationStatus(Context c) {
        if (!headphonesConnected(c)) return "WH950NB disconnected";
        String action=get(c).getString("notification_action","");
        String stage=notificationStage(c);
        if (!action.isEmpty()) {
            String label=pretty(action);
            if ("queued".equals(stage)) return "Queued: "+label;
            if ("sending".equals(stage)) return "Switching to "+label+"…";
            if ("checking".equals(stage)) return "Checking "+label+"…";
            if ("different".equals(stage) || "unverified".equals(stage))
                return "Not confirmed: "+label;
            if ("failed".equals(stage)) return "Could not switch: "+label;
        }
        // Only report a current mode after the headphones confirm it.
        String mode=get(c).getString("observed_noise_mode","");
        String level=get(c).getString("observed_noise_level","");
        return mode.isEmpty()? "Connected · check mode in app" :
                "Current: "+pretty(mode+(level.isEmpty()?"":" "+level));
    }
    static String lastRequested(Context c) { return get(c).getString("last_requested","None"); }
    static void lastRequested(Context c,String value) {
        get(c).edit().putString("last_requested",value).apply();
    }
    static boolean headphonesConnected(Context c) {
        return get(c).getBoolean("headphones_connected", false);
    }
    static void headphonesConnected(Context c, boolean active) {
        get(c).edit().putBoolean("headphones_connected", active).apply();
    }
    static void rememberConnectedMac(Context c, String address) {
        if (address != null && !address.isEmpty())
            get(c).edit().putString("last_headphone_mac",address).apply();
    }
    static String lastConnectedMac(Context c) {
        return get(c).getString("last_headphone_mac", "");
    }
    static boolean showNotification(Context c) { return get(c).getBoolean("notification",false); }
    static void showNotification(Context c,boolean on) { get(c).edit().putBoolean("notification",on).apply(); }
}
