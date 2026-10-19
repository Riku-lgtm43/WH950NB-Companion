package com.xriku.edifiercontrol;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothCodecStatus;
import android.bluetooth.BluetoothCodecConfig;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.provider.Settings;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.IBinder;
import android.os.Parcel;
import android.content.ComponentName;
import android.content.ServiceConnection;
import rikka.shizuku.Shizuku;
import android.os.SystemClock;
import android.view.WindowInsets;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** v0.3.21: three-tab interface; Bluetooth commands live in Protocol and BluetoothController. */
public class MainActivity extends Activity {
    // AMOLED surface with Material You accent. Native views only.
    private final int background = Color.BLACK;
    private final int cardColor = 0xFF1A1A1D;
    private final int chipColor = 0xFF29292D;
    private final int primaryText = 0xFFF5F5F7;
    private final int secondaryText = 0xFFA8A8B0;
    private final int pendingColor = 0xFFFFC66D;
    private final int failedColor = 0xFFFF8989;
    private int accent;
    private int activeChipColor;

    private TextView deviceName, status, noiseStatus, levelTitle;
    private TextView connectionBadge;
    private final ScrollView[] tabScrolls = new ScrollView[3];
    private final LinearLayout[] tabButtons = new LinearLayout[3];
    private final TextView[] tabLabels = new TextView[3];
    private final TextView[] tabSymbols = new TextView[3];
    private int activeTab = 0;
    private TextView batteryValue, batteryHint;
    private TextView codecValue, codecHint;
    private Button codecRefreshButton;
    private final Map<String,Button> codecButtons = new LinkedHashMap<>();
    private String currentCodec = "";
    private String codecAvailable = "";
    private boolean codecBusy = false;
    // Shizuku is used only when the user taps a codec to switch. STATUS stays
    // on the already-working, read-only Android report path.
    private static final int SHIZUKU_CODEC_PERMISSION = 417;
    private String pendingShizukuCodec = "";
    private int codecRequestSequence = 0;
    private final Shizuku.OnRequestPermissionResultListener codecShizukuPermission =
            new CodecPermissionListener(this);

    private static final class CodecPermissionListener
            implements Shizuku.OnRequestPermissionResultListener {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        CodecPermissionListener(MainActivity activity) {
            owner = new java.lang.ref.WeakReference<>(activity);
        }
        @Override public void onRequestPermissionResult(int code,int permission) {
            MainActivity activity=owner.get();
            if (activity!=null) activity.onCodecPermissionResult(code,permission);
        }
    }
    private void onCodecPermissionResult(int code,int permission) {
        if (code!=SHIZUKU_CODEC_PERMISSION) return;
        String requested=pendingShizukuCodec;
        pendingShizukuCodec="";
        Diagnostics.event(this,"CODEC_SHIZUKU_PERMISSION",
                "allowed="+(permission==PackageManager.PERMISSION_GRANTED));
        if (permission!=PackageManager.PERMISSION_GRANTED) {
            updateCodec(currentCodec,codecAvailable,"✗ Allow WH950NB Companion in Shizuku");
        } else if (!requested.isEmpty() && connected) {
            runCodecCommand(requested);
        }
    }

    private boolean codecAutoCheck = false;
    private boolean listeningForCodec = false;
    private final BroadcastReceiver codecEvents = new CodecReceiver(this);
    // A named static receiver is compatible with Termux's older D8 compiler.
    private static final class CodecReceiver extends BroadcastReceiver {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        CodecReceiver(MainActivity activity) {
            owner = new java.lang.ref.WeakReference<>(activity);
        }
        @Override public void onReceive(Context context, Intent intent) {
            MainActivity activity = owner.get();
            if (activity != null) activity.onCodecChanged(intent);
        }
    }
    private void onCodecChanged(Intent intent) {
        if (intent == null || !connected) return;
        try {
            BluetoothDevice source = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (source == null || !source.getAddress().equalsIgnoreCase(Prefs.mac(this))) return;
            BluetoothCodecStatus state = intent.getParcelableExtra(BluetoothCodecStatus.EXTRA_CODEC_STATUS);
            if (state == null) return;
            String name = codecName(state.getCodecConfig());
            String selectable = codecList(state.getCodecsSelectableCapabilities());
            if (!name.isEmpty()) updateCodec(name,selectable,"Current codec from Android");
        } catch (Exception ignored) { }
    }
    private static final String CODEC_BROADCAST =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    // Android's Bluetooth service sends this when it learns a headphone's battery level.
    // The constants and getter are not part of the guaranteed public app API.
    private static final String BATTERY_ACTION = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED";
    private static final String BATTERY_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL";
    private final Map<String, TextView> groupIndicators = new LinkedHashMap<>();
    private final Map<String, String> lastSentByGroup = new LinkedHashMap<>();
    private final Map<String, String> attemptedByGroup = new LinkedHashMap<>();
    private final Map<String, String> stageByGroup = new LinkedHashMap<>();
    private String noiseStage = "";
    private LinearLayout levelPanel;
    private SeekBar ancSeek, ambientSeek;
    private Switch notificationSwitch;
    private Switch diagnosticsSwitch;
    private TextView diagnosticHint;
    private final ArrayList<Button> commandButtons = new ArrayList<>();
    private final Map<String, Button> presetButtons = new LinkedHashMap<>();
    private final LinearLayout[] modeChips = new LinearLayout[4];
    private final TextView[] modeCaptions = new TextView[4];
    private final ImageView[] modeIcons = new ImageView[4];
    private final String[] modes = {Protocol.ANC, Protocol.AMBIENT, Protocol.WIND, Protocol.OFF};
    private final String[] modeNames = {"ANC", "Ambient", "Wind", "Off"};
    private String modeBeingEdited = "";
    private boolean sending = false;
    private boolean readingState = false;
    // Wait five seconds between Sound Mode changes, without blocking any other buttons.
    private static final long SOUND_MODE_WAIT_MS = 5000L;
    private static long soundModeReadyAtMs = 0L;
    private final Handler soundModeTimer = new Handler(Looper.getMainLooper());
    private final Runnable soundModeReady = () -> {
        if (!isFinishing() && !isDestroyed())
            setCommandButtonsEnabled(!sending && !readingState);
    };
    private long latestUiRequest = 0;
    private final SharedPreferences.OnSharedPreferenceChangeListener sharedStatusListener =
            new CoordinatorPrefsListener(this);
    private static final class CoordinatorPrefsListener implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        CoordinatorPrefsListener(MainActivity a) { owner=new java.lang.ref.WeakReference<>(a); }
        @Override public void onSharedPreferenceChanged(SharedPreferences p,String key) {
            if (!"notification_stage".equals(key) && !"observed_noise_at".equals(key)
                    && !"notification_action".equals(key)) return;
            MainActivity a=owner.get();
            if (a!=null && !a.isFinishing()) a.syncCoordinatorState();
        }
    }

    private boolean initialReadPending = false;
    private boolean connected = false;
    private int deviceRefreshId = 0;
    private boolean listeningForConnection = false;
    private boolean listeningForBattery = false;
    private final BroadcastReceiver connectionEvents = new ConnectionReceiver(this);
    private final BroadcastReceiver batteryEvents = new BatteryReceiver(this);
    private static final class ConnectionReceiver extends BroadcastReceiver {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        ConnectionReceiver(MainActivity activity) {
            owner = new java.lang.ref.WeakReference<>(activity);
        }
        @Override public void onReceive(Context context, Intent intent) {
            MainActivity activity = owner.get();
            if (activity != null) activity.refreshDevice();
        }
    }
    private static final class BatteryReceiver extends BroadcastReceiver {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        BatteryReceiver(MainActivity activity) {
            owner = new java.lang.ref.WeakReference<>(activity);
        }
        @Override public void onReceive(Context context, Intent intent) {
            MainActivity activity = owner.get();
            if (activity != null && BATTERY_ACTION.equals(intent.getAction()))
                activity.onBatteryLevelChanged(intent);
        }
    }

    // Reuse a named class: Termux's older D8 had trouble with anonymous listeners.
    // Level labels update while dragging; exactly one RFCOMM command is sent on release.
    private static final class LevelListener implements SeekBar.OnSeekBarChangeListener {
        private final MainActivity activity;
        private final TextView title;
        private final int offset;
        private final String unit;
        private final String mode;
        private int startingProgress;
        LevelListener(MainActivity activity, TextView title, int offset, String unit, String mode) {
            this.activity = activity;
            this.title = title;
            this.offset = offset;
            this.unit = unit;
            this.mode = mode;
        }
        @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            title.setText("Level " + (progress + offset) + unit);
        }
        @Override public void onStartTrackingTouch(SeekBar bar) {
            startingProgress = bar.getProgress();
        }
        @Override public void onStopTrackingTouch(SeekBar bar) {
            if (bar.getProgress() != startingProgress) activity.sendSliderLevel(mode, bar.getProgress());
        }
    }

    private void sendSliderLevel(String mode, int progress) {
        if (!connected || !mode.equals(modeBeingEdited)) return;
        String command = Protocol.ANC.equals(mode) ? "ANC " + (progress + 1)
                : "AMBIENT " + (progress - 3);
        dispatch(command, () -> showMode(mode));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Diagnostics.event(this,"SCREEN_CREATE","app opened");
        accent = systemColor("system_accent1_200", 0xFF8ECFC6);
        activeChipColor = mix(chipColor, accent, 0.22f);
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        buildUi();
        try { Shizuku.addRequestPermissionResultListener(codecShizukuPermission); }
        catch (Throwable ignored) { } // Existing features work without Shizuku.
        // Ask automatically once on the first opening when permission is missing.
        // Subsequent denials are handled through the app's Settings page, not nagging.
        if (Build.VERSION.SDK_INT >= 31 && !BluetoothController.permitted(this)
                && !Prefs.get(this).getBoolean("bt_permission_requested", false)) {
            Prefs.get(this).edit().putBoolean("bt_permission_requested", true).apply();
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 101);
        }
    }

    private int systemColor(String name, int fallback) {
        try {
            int id = getResources().getIdentifier(name, "color", "android");
            if (id != 0) return getResources().getColor(id, getTheme());
        } catch (Exception ignored) { }
        return fallback;
    }
    private static int mix(int base, int overlay, float amount) {
        float keep = 1f - amount;
        return Color.rgb((int)(Color.red(base)*keep+Color.red(overlay)*amount),
                (int)(Color.green(base)*keep+Color.green(overlay)*amount),
                (int)(Color.blue(base)*keep+Color.blue(overlay)*amount));
    }
    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n + 0.5f); }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private GradientDrawable outlined(int color, int radius, int strokeColor) {
        GradientDrawable drawable = rounded(color,radius);
        drawable.setStroke(dp(1),strokeColor);
        return drawable;
    }
    // Android-native touch ripple for every button, independent of Bluetooth success.
    private RippleDrawable ripple(GradientDrawable shape, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF),
                shape, rounded(Color.WHITE, radius));
    }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout line() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private TextView text(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(sp); view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private void gap(LinearLayout parent, int sizeDp) {
        View v = new View(this);
        parent.addView(v, new LinearLayout.LayoutParams(dp(1),dp(sizeDp)));
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setPadding(dp(16),dp(16),dp(16),dp(16));
        card.setBackground(rounded(cardColor,20));
        parent.addView(card,new LinearLayout.LayoutParams(-1,-2));
        return card;
    }
    private Button pill(String name, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(name);
        button.setTextColor(primaryText);
        button.setTextSize(13);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(0);
        button.setPadding(dp(3),0,dp(3),0);
        button.setBackground(ripple(rounded(chipColor,13),13));
        button.setTag(name);
        button.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            action.run();
        });
        commandButtons.add(button);
        return button;
    }
    private Button actionPill(String title, String command) {
        Button button = pill(title, () -> dispatch(command, () -> {}));
        presetButtons.put(command, button);
        return button;
    }

    // Persist only successfully WRITTEN commands. These are not verified device settings.
    private String groupOf(String action) {
        if (action.startsWith("Sound ")) return "Sound";
        if (action.startsWith("EQ ")) return "EQ";
        if (action.startsWith("Timer ")) return "Timer";
        return "";
    }
    private String displayAction(String command) {
        String group = groupOf(command);
        return group.isEmpty() ? command : command.substring(group.length()+1);
    }
    private void restoreGroup(String group) {
        String saved = getSharedPreferences("edifier_settings",MODE_PRIVATE)
                .getString("ui_last_" + group, "");
        if (!saved.isEmpty()) lastSentByGroup.put(group,saved);
        renderGroup(group);
    }
    private void renderGroup(String group) {
        String last = lastSentByGroup.get(group);
        String attempt = attemptedByGroup.get(group);
        String stage = stageByGroup.get(group);
        if (stage == null) stage = "";
        for (Map.Entry<String,Button> entry : presetButtons.entrySet()) {
            String command = entry.getKey();
            if (!group.equals(groupOf(command))) continue;
            Button button = entry.getValue();
            String label = (String)button.getTag();
            boolean isLast = command.equals(last);
            boolean isAttempt = command.equals(attempt);
            int ink = isLast ? accent : primaryText;
            GradientDrawable shape = isLast
                    ? outlined(activeChipColor,13,accent) : rounded(chipColor,13);
            String prefix = isLast ? "✓ " : "";
            if (isAttempt && "sending".equals(stage)) {
                shape = outlined(mix(chipColor,pendingColor,0.18f),13,pendingColor);
                ink = pendingColor;
                prefix = "… ";
            } else if (isAttempt && "failed".equals(stage)) {
                shape = outlined(mix(chipColor,failedColor,0.11f),13,failedColor);
                ink = failedColor;
                prefix = "✗ ";
            }
            button.setBackground(ripple(shape,13));
            button.setTextColor(ink);
            button.setText(prefix + label);
            button.setContentDescription(label + (isAttempt && "failed".equals(stage)
                    ? ", command not sent" : isAttempt && "sending".equals(stage)
                    ? ", sending" : isLast ? ", last sent" : ""));
        }
        TextView indicator = groupIndicators.get(group);
        if (indicator == null) return;
        if ("sending".equals(stage)) {
            indicator.setText("Sending " + displayAction(attempt) + "…");
            indicator.setTextColor(pendingColor);
        } else if ("failed".equals(stage)) {
            indicator.setText("✗ Not sent: " + displayAction(attempt));
            indicator.setTextColor(failedColor);
        } else if (last != null && !last.isEmpty()) {
            indicator.setText("✓ Last sent: " + displayAction(last));
            indicator.setTextColor(accent);
        } else {
            indicator.setText("Tap a setting to apply");
            indicator.setTextColor(secondaryText);
        }
    }
    private void groupFeedback(String command, String stage) {
        String group = groupOf(command);
        if (group.isEmpty()) return;
        attemptedByGroup.put(group,command);
        stageByGroup.put(group,stage);
        if ("sent".equals(stage)) {
            lastSentByGroup.put(group,command);
            getSharedPreferences("edifier_settings",MODE_PRIVATE).edit()
                    .putString("ui_last_" + group, command).apply();
            stageByGroup.put(group,"");
        }
        renderGroup(group);
    }
    private void buttonRow(LinearLayout parent, String[] names, String[] commands, int height) {
        LinearLayout row = line();
        for (int i=0; i<names.length; i++) {
            Button b = actionPill(names[i],commands[i]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,dp(height),1);
            if (i != names.length-1) lp.setMargins(0,0,dp(7),0);
            row.addView(b,lp);
        }
        parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private void heading(LinearLayout parent, String name) {
        gap(parent,14);
        TextView title = text(name,15,true,primaryText);
        parent.addView(title);
        gap(parent,7);
    }
    // Three destinations instead of a single long vertical settings page.
    // Pages are built once, preserving button status, scroll position and ongoing operations.
    private void buildUi() {
        LinearLayout root = column();
        root.setBackgroundColor(background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(0,top,0,bottom);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();

        LinearLayout toolbar = column();
        toolbar.setPadding(dp(20),dp(14),dp(20),dp(14));
        LinearLayout top = line();
        LinearLayout branding = column();
        branding.addView(text("UNOFFICIAL · WH950NB",11,true,accent));
        gap(branding,3);
        branding.addView(text("Headphone control",24,true,primaryText));
        top.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
        connectionBadge = text("Checking…",11,true,secondaryText);
        connectionBadge.setGravity(Gravity.CENTER);
        connectionBadge.setPadding(dp(12),dp(9),dp(12),dp(9));
        connectionBadge.setBackground(rounded(chipColor,18));
        connectionBadge.setContentDescription("Headphone connection: checking");
        top.addView(connectionBadge);
        toolbar.addView(top);
        root.addView(toolbar,new LinearLayout.LayoutParams(-1,-2));

        FrameLayout content = new FrameLayout(this);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        for (int i=0;i<tabScrolls.length;i++) {
            ScrollView tab = new ScrollView(this);
            tab.setFillViewport(true);
            tab.setClipToPadding(false);
            tab.setVerticalScrollBarEnabled(false);
            tab.setBackgroundColor(background);
            tabScrolls[i] = tab;
            content.addView(tab,new FrameLayout.LayoutParams(-1,-1));
            tab.setVisibility(View.GONE);
        }

        LinearLayout nav = line();
        nav.setPadding(dp(12),dp(9),dp(12),dp(10));
        nav.setBackground(outlined(0xFF141416,0,0xFF2D2D31));
        String[] tabNames = {"Controls", "Sound", "Settings"};
        String[] tabIcons = {"◉", "♫", "⚙"};
        for (int i=0;i<3;i++) {
            final int target = i;
            LinearLayout item = column();
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(6),dp(8),dp(6),dp(8));
            item.setBackground(ripple(rounded(0xFF141416,16),16));
            TextView glyph = text(tabIcons[i],22,false,secondaryText);
            glyph.setGravity(Gravity.CENTER);
            item.addView(glyph);
            gap(item,2);
            TextView title = text(tabNames[i],11,true,secondaryText);
            title.setGravity(Gravity.CENTER);
            item.addView(title);
            item.setClickable(true);
            item.setFocusable(true);
            item.setContentDescription(tabNames[i] + " tab");
            item.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                selectTab(target);
            });
            tabButtons[i]=item;
            tabLabels[i]=title;
            tabSymbols[i]=glyph;
            nav.addView(item,new LinearLayout.LayoutParams(0,dp(61),1));
        }
        root.addView(nav,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout controlsPage = createTabPage(0,"Your headphones",
                "Connection and noise control");
        LinearLayout soundPage = createTabPage(1,"Listening",
                "Codecs, sound modes and equalizer");
        LinearLayout settingsPage = createTabPage(2,"Preferences",
                "Sleep timer, notifications and diagnostics");
        // Keep construction order unchanged to preserve initialization and state updates.
        buildHeadphoneSection(controlsPage);
        buildCodecSection(soundPage);
        buildNoiseSection(controlsPage);
        buildSoundSection(soundPage);
        buildSettingsSection(settingsPage);

        // Helpful guidance takes up unused space without changing the top content
        // or the bottom navigation. On short screens it scrolls with the page.
        bottomGuide(controlsPage,"USING NOISE CONTROL",
                "Choose ANC, Ambient, Wind or Off. For ANC and Ambient, release the slider to send the level. 'Current' shows the last state reported by the headphones.");
        bottomGuide(soundPage,"CHECKING SOUND SETTINGS",
                "Check codec shows Android's active codec. Sound mode and EQ show the last command sent, which may not be the confirmed headphone setting.");
        bottomGuide(settingsPage,"ABOUT THIS UNOFFICIAL APP",
                "Independent hobby project for WH950NB. Not made, approved, or supported by Edifier. Diagnostics are optional and stay on your phone until shared.");

        int lastTab = getSharedPreferences("edifier_settings",MODE_PRIVATE).getInt("ui_tab",0);
        selectTab(Math.max(0,Math.min(2,lastTab)));
    }

    private void buildHeadphoneSection(LinearLayout controlsPage) {
        LinearLayout page = controlsPage;

        LinearLayout device = card(page);
        LinearLayout details = line();
        ImageView art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setContentDescription("Illustration of over-ear headphones");
        try (InputStream stream = getAssets().open("headphones.png")) {
            art.setImageBitmap(BitmapFactory.decodeStream(stream));
        } catch (Exception error) {
            art.setImageResource(R.drawable.ic_headphones);
            art.setColorFilter(accent);
        }
        details.addView(art,new LinearLayout.LayoutParams(dp(112),dp(118)));
        LinearLayout info = column();
        info.setPadding(dp(10),0,0,0);
        info.addView(text("WH950NB",19,true,primaryText));
        gap(info,5);
        deviceName = text("Checking connected Bluetooth devices…",12,false,secondaryText);
        info.addView(deviceName);
        gap(info,8);
        batteryValue = text("Battery  —",13,true,secondaryText);
        batteryHint = text("Waiting for Android battery information",10,false,secondaryText);
        info.addView(batteryValue);
        info.addView(batteryHint);
        details.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        device.addView(details);
        gap(device,8);
        Button read = pill("Refresh mode",() -> {
            Diagnostics.event(this,"UI_REFRESH_TAP","manual refresh requested");
            if (!BluetoothController.permitted(this)) { requestBluetoothPermission(); return; }
            if (!connected) { status.setText("Connect WH950NB in Android Bluetooth settings first."); return; }
            readStateAndSync(false);
        });
        device.addView(read,new LinearLayout.LayoutParams(-1,dp(39)));
        gap(device,8);
        status = text("Checking connected WH950NB…",11,false,secondaryText);
        status.setMaxLines(3);
        status.setOnClickListener(v -> {
            if (!BluetoothController.permitted(this)) openAppSettings();
        });
        device.addView(status);
    }

    private void buildCodecSection(LinearLayout soundPage) {
        LinearLayout page = soundPage;
        heading(page,"Bluetooth codec");
        LinearLayout codecCard = card(page);
        codecValue = text("Current codec  —",15,true,primaryText);
        codecHint = text("Tap Check codec. KernelSU may ask for permission.",11,false,secondaryText);
        codecCard.addView(codecValue);
        gap(codecCard,5);
        codecCard.addView(codecHint);
        gap(codecCard,9);
        codecRefreshButton = pill("Check codec",() -> runCodecCommand("STATUS"));
        codecCard.addView(codecRefreshButton,new LinearLayout.LayoutParams(-1,dp(39)));
        gap(codecCard,8);
        LinearLayout codecRow = line();
        String[] codecs = {"LDAC","AAC","SBC"};
        for (int i=0; i<codecs.length; i++) {
            String choice = codecs[i];
            Button b = pill(choice,() -> runCodecCommand(choice));
            codecButtons.put(choice,b);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,dp(39),1);
            if (i<codecs.length-1) lp.setMargins(0,0,dp(7),0);
            codecRow.addView(b,lp);
        }
        codecCard.addView(codecRow);
        updateCodecButtons();
    }

    private void buildNoiseSection(LinearLayout controlsPage) {
        LinearLayout page = controlsPage;
        heading(page,"Noise control");
        LinearLayout noise = card(page);
        LinearLayout noiseRow = line();
        String[] captions = {"ANC","Ambient","Wind","Off"};
        int[] icons = {R.drawable.ic_anc,R.drawable.ic_ambient,R.drawable.ic_wind,R.drawable.ic_off};
        for (int i=0; i<modes.length; i++) {
            final String mode = modes[i];
            LinearLayout chip = column();
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(1),dp(6),dp(1),dp(6));
            chip.setBackground(rounded(chipColor,14));
            chip.setForeground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF),
                    null, rounded(Color.WHITE,14)));
            chip.setClickable(true);
            chip.setFocusable(true);
            ImageView icon = new ImageView(this);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(secondaryText);
            chip.addView(icon,new LinearLayout.LayoutParams(dp(21),dp(21)));
            gap(chip,5);
            TextView caption = text(captions[i],11,true,secondaryText);
            caption.setGravity(Gravity.CENTER);
            chip.addView(caption);
            chip.setContentDescription(captions[i] + " mode");
            chip.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                dispatch(mode,() -> showMode(mode));
            });
            modeChips[i] = chip;
            modeCaptions[i] = caption;
            modeIcons[i] = icon;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,dp(68),1);
            if (i < modes.length-1) lp.setMargins(0,0,dp(6),0);
            noiseRow.addView(chip,lp);
        }
        noise.addView(noiseRow);
        gap(noise,7);
        noiseStatus = text("Mode not confirmed",11,false,secondaryText);
        noise.addView(noiseStatus);

        levelPanel = column();
        gap(levelPanel,8);
        LinearLayout levelLabelRow = line();
        levelTitle = text("",12,true,primaryText);
        levelLabelRow.addView(levelTitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView unverified = text("Release slider to send",10,false,secondaryText);
        levelLabelRow.addView(unverified);
        levelPanel.addView(levelLabelRow);
        LinearLayout input = line();
        ancSeek = new SeekBar(this);
        ancSeek.setMax(7);
        ancSeek.setProgress(5);
        ancSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        ancSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        ambientSeek = new SeekBar(this);
        ambientSeek.setMax(5);
        ambientSeek.setProgress(3);
        ambientSeek.setProgressTintList(android.content.res.ColorStateList.valueOf(accent));
        ambientSeek.setThumbTintList(android.content.res.ColorStateList.valueOf(accent));
        ancSeek.setOnSeekBarChangeListener(new LevelListener(this,levelTitle,1," / 8",Protocol.ANC));
        ambientSeek.setOnSeekBarChangeListener(new LevelListener(this,levelTitle,-3,"",Protocol.AMBIENT));
        input.addView(ancSeek,new LinearLayout.LayoutParams(-1,dp(41)));
        input.addView(ambientSeek,new LinearLayout.LayoutParams(-1,dp(41)));
        levelPanel.addView(input);
        noise.addView(levelPanel);
        String last = getSharedPreferences("edifier_settings",MODE_PRIVATE)
                .getString("ui_last_noise_action","");
        if (last.isEmpty()) last = Prefs.lastRequested(this);
        if (last.startsWith("ANC ")) {
            try { ancSeek.setProgress(Math.max(0,Math.min(7,Integer.parseInt(last.substring(4))-1))); }
            catch (NumberFormatException ignored) { }
        } else if (last.startsWith("AMBIENT ")) {
            try { ambientSeek.setProgress(Math.max(0,Math.min(5,Integer.parseInt(last.substring(8))+3))); }
            catch (NumberFormatException ignored) { }
        }
        showMode(modeOf(last));
        setCommandButtonsEnabled(false); // Before the first connected-device check.
        if (modeOf(last).isEmpty()) noiseStatus.setText("Select a mode to check");
        else noiseStatus.setText("✗ Not confirmed: " + last);
    }

    private void buildSoundSection(LinearLayout soundPage) {
        LinearLayout page = soundPage;
        heading(page,"Sound mode");
        LinearLayout sound = card(page);
        buttonRow(sound,new String[]{"Music","Game","Spatial"},
                new String[]{"Sound Music","Sound Game","Sound Spatial"},41);
        gap(sound,7);
        TextView soundFeedback = text("Tap a setting to apply",11,false,secondaryText);
        sound.addView(soundFeedback);
        groupIndicators.put("Sound",soundFeedback);
        restoreGroup("Sound");

        // Less-used settings start collapsed, keeping everyday controls near the top.
        collapsible(page,"Equalizer presets","Edifier · Jazz · Rock · Bass",new String[][]{
                {"Edifier","Jazz","EQ Edifier","EQ Jazz"},
                {"Rock","Bass","EQ Rock","EQ Bass"}
        });
    }

    private void buildSettingsSection(LinearLayout settingsPage) {
        LinearLayout page = settingsPage;
        collapsible(page,"Sleep timer","10 to 90 minutes · Never",new String[][]{
                {"10 min","20 min","30 min","Timer 10m","Timer 20m","Timer 30m"},
                {"60 min","90 min","Never","Timer 60m","Timer 90m","Timer Never"}
        });

        heading(page,"Quick access");
        LinearLayout quick = card(page);
        notificationSwitch = new Switch(this);
        notificationSwitch.setText("Headphone notification");
        notificationSwitch.setTextSize(13);
        notificationSwitch.setTextColor(primaryText);
        notificationSwitch.setChecked(Prefs.showNotification(this));
        quick.addView(notificationSwitch);
        notificationSwitch.setOnCheckedChangeListener((CompoundButton sw, boolean enabled) -> {
            if (enabled && !NotificationHelper.canShow(this)) {
                sw.setChecked(false);
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},102);
                }
                return;
            }
            Prefs.showNotification(this,enabled);
            if (enabled) {
                // Never show a stale "connected" notification when enabling.
                NotificationHelper.hide(this);
                NotificationConnectionReceiver.refreshConnection(this,null);
            } else NotificationHelper.hide(this);
        });
        gap(quick,4);
        quick.addView(text("Only shown when WH950NB is connected. Add ANC, Ambient and Wind tiles from Android Quick Settings.",11,false,secondaryText));
        gap(page,12);
        LinearLayout diagnosticsCard = card(page);
        diagnosticsCard.addView(text("Diagnostics",15,true,primaryText));
        gap(diagnosticsCard,5);
        diagnosticsSwitch = new Switch(this);
        diagnosticsSwitch.setText("Record Bluetooth trace");
        diagnosticsSwitch.setTextSize(13);
        diagnosticsSwitch.setTextColor(primaryText);
        diagnosticsSwitch.setChecked(Diagnostics.enabled(this));
        diagnosticsCard.addView(diagnosticsSwitch);
        diagnosticHint = text("Off by default · local trace · no background polling",11,false,secondaryText);
        diagnosticsCard.addView(diagnosticHint);
        diagnosticsSwitch.setOnCheckedChangeListener((button,on) -> {
            Diagnostics.setEnabled(this,on);
            diagnosticHint.setText(on ? "Recording · reproduce the issue, then share the trace"
                    : "Logging off · last trace remains available");
        });
        gap(diagnosticsCard,7);
        LinearLayout diagnosticActions=line();
        Button shareTrace = new Button(this);
        shareTrace.setAllCaps(false);
        shareTrace.setText("Share trace");
        shareTrace.setTextColor(primaryText);
        shareTrace.setTextSize(12);
        shareTrace.setBackground(ripple(rounded(chipColor,13),13));
        shareTrace.setOnClickListener(v -> {
            String trace=Diagnostics.read(this);
            if (trace.length()==0) { diagnosticHint.setText("No trace yet. Enable logging and reproduce the issue."); return; }
            Intent send=new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT,"WH950NB Companion Bluetooth diagnostics");
            send.putExtra(Intent.EXTRA_TEXT,trace);
            startActivity(Intent.createChooser(send,"Share WH950NB trace"));
        });
        diagnosticActions.addView(shareTrace,new LinearLayout.LayoutParams(0,dp(42),1));
        View horizontalSpacer=new View(this);
        diagnosticActions.addView(horizontalSpacer,new LinearLayout.LayoutParams(dp(8),1));
        Button clearTrace=new Button(this);
        clearTrace.setAllCaps(false);
        clearTrace.setText("Clear trace");
        clearTrace.setTextColor(primaryText);
        clearTrace.setTextSize(12);
        clearTrace.setBackground(ripple(rounded(chipColor,13),13));
        clearTrace.setOnClickListener(v -> {
            Diagnostics.clear(this);
            diagnosticHint.setText("Trace cleared" + (Diagnostics.enabled(this) ? " · recording continues" : ""));
        });
        diagnosticActions.addView(clearTrace,new LinearLayout.LayoutParams(0,dp(42),1));
        diagnosticsCard.addView(diagnosticActions);
        gap(page,12);
        page.addView(text("Commands are sent on demand. Sending a command does not always confirm the headphones applied it.",11,false,secondaryText));
    }

    private LinearLayout createTabPage(int index, String heading, String description) {
        LinearLayout page = column();
        page.setPadding(dp(18),dp(8),dp(18),dp(28));
        page.addView(text(heading,20,true,primaryText));
        gap(page,3);
        page.addView(text(description,12,false,secondaryText));
        gap(page,12);
        tabScrolls[index].addView(page,new ScrollView.LayoutParams(-1,-2));
        return page;
    }

    private void bottomGuide(LinearLayout page, String heading, String detail) {
        // Weight fills only spare height; it collapses when the page must scroll.
        page.addView(new View(this),new LinearLayout.LayoutParams(-1,0,1f));
        LinearLayout guide = column();
        guide.setPadding(dp(4),dp(16),dp(4),dp(2));
        TextView label = text(heading,10,true,secondaryText);
        guide.addView(label);
        gap(guide,5);
        TextView explanation = text(detail,11,false,secondaryText);
        explanation.setLineSpacing(dp(2),1f);
        guide.addView(explanation);
        page.addView(guide,new LinearLayout.LayoutParams(-1,-2));
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabScrolls.length) return;
        activeTab = index;
        for (int i=0;i<tabScrolls.length;i++) {
            boolean selected=i==index;
            tabScrolls[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            tabButtons[i].setBackground(ripple(rounded(
                    selected ? activeChipColor : 0xFF141416,16),16));
            tabLabels[i].setTextColor(selected ? accent : secondaryText);
            tabSymbols[i].setTextColor(selected ? accent : secondaryText);
            tabButtons[i].setSelected(selected);
            tabButtons[i].setContentDescription((i==0 ? "Controls" : i==1 ? "Sound" : "Settings")
                    + " tab" + (selected ? ", selected" : ""));
        }
        getSharedPreferences("edifier_settings",MODE_PRIVATE).edit().putInt("ui_tab",index).apply();
    }

    private void showConnectionBadge(boolean isConnected, String label) {
        if (connectionBadge == null) return;
        connectionBadge.setText(label);
        connectionBadge.setTextColor(isConnected ? accent : secondaryText);
        connectionBadge.setBackground(rounded(isConnected ? activeChipColor : chipColor,18));
        connectionBadge.setContentDescription("Headphone connection: " + label);
    }

    private void collapsible(LinearLayout page, String title, String summary, String[][] rows) {
        gap(page,11);
        LinearLayout box = card(page);
        LinearLayout header = line();
        LinearLayout headerLabels = column();
        headerLabels.addView(text(title,15,true,primaryText));
        TextView subtitle = text(summary,11,false,secondaryText);
        headerLabels.addView(subtitle);
        header.addView(headerLabels,new LinearLayout.LayoutParams(0,-2,1));
        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_expand_more);
        arrow.setColorFilter(secondaryText);
        arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        header.addView(arrow,new LinearLayout.LayoutParams(dp(24),dp(24)));
        box.addView(header);
        header.setClickable(true);
        header.setFocusable(true);
        header.setContentDescription(title + ", collapsed, double tap to expand");
        LinearLayout content = column();
        gap(content,10);
        for (int i=0;i<rows.length;i++) {
            String[] row = rows[i];
            int half = row.length/2;
            String[] labels = new String[half];
            String[] cmds = new String[half];
            System.arraycopy(row,0,labels,0,half);
            System.arraycopy(row,half,cmds,0,half);
            buttonRow(content,labels,cmds,39);
            if (i != rows.length-1) gap(content,7);
        }
        box.addView(content);
        String group = groupOf(rows[0][rows[0].length/2]);
        if (!group.isEmpty()) {
            groupIndicators.put(group,subtitle);
            restoreGroup(group);
        }
        content.setVisibility(View.GONE);
        header.setOnClickListener(v -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            arrow.setRotation(open ? 180f : 0f);
            header.setContentDescription(title + (open
                    ? ", expanded, double tap to collapse"
                    : ", collapsed, double tap to expand"));
        });
    }

    private String modeOf(String action) {
        if (action == null) return "";
        if (action.equals(Protocol.ANC) || action.startsWith("ANC ")) return Protocol.ANC;
        if (action.equals(Protocol.AMBIENT) || action.startsWith("AMBIENT ")) return Protocol.AMBIENT;
        if (action.equals(Protocol.WIND)) return Protocol.WIND;
        if (action.equals(Protocol.OFF)) return Protocol.OFF;
        return "";
    }
    private void showMode(String mode) {
        modeBeingEdited = mode;
        for (int i=0;i<modeChips.length;i++) {
            boolean selected = modes[i].equals(mode);
            boolean sendingThis = selected && "sending".equals(noiseStage);
            boolean failedThis = selected && ("failed".equals(noiseStage)
                    || "unverified".equals(noiseStage));
            boolean readThis = selected && "observed".equals(noiseStage);
            int ink = failedThis ? failedColor : sendingThis ? pendingColor
                    : selected ? accent : secondaryText;
            GradientDrawable bg = failedThis
                    ? outlined(mix(chipColor,failedColor,0.11f),14,failedColor)
                    : sendingThis ? outlined(mix(chipColor,pendingColor,0.18f),14,pendingColor)
                    : selected ? outlined(activeChipColor,14,accent)
                    : rounded(chipColor,14);
            modeChips[i].setBackground(bg);
            modeCaptions[i].setText((sendingThis ? "… " : failedThis ? "✗ "
                    : readThis ? "✓ " : "") + modeNames[i]);
            modeCaptions[i].setTextColor(ink);
            modeIcons[i].setColorFilter(ink);
            modeChips[i].setContentDescription(modeNames[i]
                    + (sendingThis ? ", sending" : failedThis ? ", command failed"
                    : readThis ? ", last read from headphones"
                    : selected ? ", last requested, not confirmed" : ""));
        }
        if (levelPanel == null) return;
        boolean isAnc = Protocol.ANC.equals(mode);
        boolean isAmbient = Protocol.AMBIENT.equals(mode);
        levelPanel.setVisibility(isAnc || isAmbient ? View.VISIBLE : View.GONE);
        ancSeek.setVisibility(isAnc ? View.VISIBLE : View.GONE);
        ambientSeek.setVisibility(isAmbient ? View.VISIBLE : View.GONE);
        if (isAnc) levelTitle.setText("Level " + (ancSeek.getProgress()+1) + " / 8");
        if (isAmbient) levelTitle.setText("Level " + (ambientSeek.getProgress()-3));
    }
    // A query runs only on entry, manual refresh, or after a noise-control write.
    // It does not poll continuously. Responses (not write acknowledgments) update UI.
    private void readStateAndSync(boolean automatic) {
        Diagnostics.event(this,"UI_REFRESH_REQUEST","automatic="+automatic+" busy_read="+readingState
                +" busy_send="+sending+" connected="+connected);
        if (readingState || sending || !connected || !BluetoothController.permitted(this)) {
            Diagnostics.event(this,"UI_REFRESH_SKIPPED","busy_or_disconnected_or_no_permission");
            return;
        }
        readingState = true;
        status.setText(automatic ? "Checking mode…" : "Refreshing mode…");
        status.setTextColor(secondaryText);
        setCommandButtonsEnabled(false);
        BluetoothController.readStateDetailed(this,(ok,message,state) -> {
            Diagnostics.event(this,"UI_REFRESH_RESULT","ok="+ok+" detail="+message
                    +" state="+(state==null?"none":state.label()));
            readingState = false;
            if (isFinishing() || isDestroyed()) return;
            setCommandButtonsEnabled(!sending);
            if (!ok) {
                // A newer command makes this snapshot obsolete. This is not a
                // Bluetooth failure, and it must not repaint the selected mode.
                boolean superseded = "Refresh superseded by newer command".equals(message);
                if (!superseded)
                    android.util.Log.w("EdifierControl", "State read failed: " + message);
                status.setText(superseded ? "Checking newer mode request…"
                        : "✗ Not confirmed: couldn't read headphone mode");
                status.setTextColor(superseded || automatic ? secondaryText : failedColor);
                return;
            }
            if (!Prefs.validNoiseState(state)) {
                status.setText("✗ Not confirmed: headphone mode not recognized");
                status.setTextColor(secondaryText);
                return;
            }
            try {
                if (Protocol.ANC.equals(state.mode))
                    ancSeek.setProgress(Integer.parseInt(state.level) - 1);
                else if (Protocol.AMBIENT.equals(state.mode))
                    ambientSeek.setProgress(Integer.parseInt(state.level) + 3);
            } catch (NumberFormatException impossible) {
                status.setText("✗ Not confirmed: headphone level not recognized");
                return;
            }
            noiseStage = "observed";
            showMode(state.mode);
            String displayMode = Protocol.AMBIENT.equals(state.mode) ? "Ambient"
                    : Protocol.WIND.equals(state.mode) ? "Wind reduction"
                    : Protocol.OFF.equals(state.mode) ? "Off" : "ANC";
            String detail = displayMode + (state.level.isEmpty() ? "" : " " + state.level);
            noiseStatus.setText("✓ Current: " + detail);
            noiseStatus.setTextColor(accent);
            status.setText("✓ Mode checked");
            status.setTextColor(secondaryText);
        });
    }

    private void dispatch(String command, Runnable whenSent) {
        Diagnostics.event(this,"UI_TAP","screen action="+command+" connected="+connected
                +" sending="+sending+" reading="+readingState);
        boolean slider=command.startsWith("ANC ") || command.startsWith("AMBIENT ");
        if ((sending || readingState) && !slider) {
            Diagnostics.event(this,"UI_TAP_SKIPPED","busy action="+command);
            return;
        }
        if (!connected) {
            status.setText("Connect WH950NB in Android Bluetooth settings first.");
            status.setTextColor(secondaryText);
            return;
        }
        if (command.startsWith("Sound ")) {
            long waitMs = soundModeReadyAtMs - SystemClock.elapsedRealtime();
            if (waitMs > 0L) {
                status.setText("Wait " + ((waitMs + 999L) / 1000L) + "s before another Sound Mode");
                status.setTextColor(secondaryText);
                Diagnostics.event(this,"SOUND_MODE_WAIT","action="+command+" remaining_ms="+waitMs);
                return;
            }
        }
        String requestedMode = modeOf(command);
        if (!BluetoothController.permitted(this)) {
            status.setText("Nearby devices permission required");
            if (!requestedMode.isEmpty()) {
                noiseStage = "failed";
                showMode(requestedMode);
                noiseStatus.setText("✗ Not confirmed: permission required");
                noiseStatus.setTextColor(failedColor);
            } else groupFeedback(command,"failed");
            requestBluetoothPermission();
            return;
        }
        final long requestId=++latestUiRequest;
        sending=true;
        status.setText("Queued: " + command);
        status.setTextColor(pendingColor);
        if (!requestedMode.isEmpty()) {
            noiseStage="sending";
            showMode(requestedMode);
            noiseStatus.setText("Queued: " + command);
            noiseStatus.setTextColor(pendingColor);
        } else groupFeedback(command,"sending");
        setCommandButtonsEnabled(false);
        BluetoothController.sendVerified(this,command,phase -> {
            // Updating UI here never starts a second independent refresh.
            if (requestId!=latestUiRequest || isFinishing() || isDestroyed()) return;
            Diagnostics.event(this,"UI_STAGE","action="+command+" phase="+phase+" ui_request="+requestId);
            if ("checking".equals(phase)) {
                status.setText("Checking current headphone mode…");
                status.setTextColor(pendingColor);
                if (!requestedMode.isEmpty()) {
                    noiseStage="sending";
                    noiseStatus.setText("Checking actual mode…");
                    noiseStatus.setTextColor(pendingColor);
                }
            }
        },outcome -> {
            Diagnostics.event(this,"UI_COMMAND_RESULT","action="+command+" sent="+outcome.sent
                    +" verdict="+outcome.verdict+" result="+outcome.message
                    +" readback="+(outcome.state==null?"none":outcome.state.label())
                    +" ui_request="+requestId+" latest_request="+latestUiRequest);
            // Newer slider requests win. An older queued response must not repaint the UI.
            if (requestId!=latestUiRequest || isFinishing() || isDestroyed()) {
                Diagnostics.event(this,"UI_RESULT_SKIPPED","stale_callback action="+command);
                return;
            }
            sending=false;
            if (command.startsWith("Sound ") && outcome.sent) {
                soundModeReadyAtMs = SystemClock.elapsedRealtime() + SOUND_MODE_WAIT_MS;
                Diagnostics.event(this,"SOUND_MODE_COOLDOWN",
                        "action="+command+" delay_ms="+SOUND_MODE_WAIT_MS);
            }
            setCommandButtonsEnabled(true);
            // Sound, EQ and Timer support only command-delivery status here.
            // A check means the command was sent, NOT that the setting was read back.
            if (requestedMode.isEmpty()) {
                status.setText((outcome.sent ? "✓ Sent: " : "✗ Not sent: ") + displayAction(command));
                status.setTextColor(outcome.sent ? accent : failedColor);
            } else {
                status.setText(outcome.message);
                status.setTextColor(outcome.sent ? secondaryText : failedColor);
            }
            if ("superseded".equals(outcome.verdict)) return;
            if (!requestedMode.isEmpty()) {
                if (Prefs.validNoiseState(outcome.state)) {
                    updateObservedUi(outcome.state);
                    if ("different".equals(outcome.verdict)) {
                        noiseStatus.setText("Headphones are set to a different mode");
                        noiseStatus.setTextColor(failedColor);
                    }
                } else if (outcome.sent) {
                    noiseStage="unverified";
                    showMode(requestedMode);
                    noiseStatus.setText("Command sent — confirmation unavailable: " + command);
                    noiseStatus.setTextColor(secondaryText);
                } else {
                    noiseStage="failed";
                    showMode(requestedMode);
                    noiseStatus.setText("✗ Not confirmed: " + command);
                    noiseStatus.setTextColor(failedColor);
                }
            } else {
                groupFeedback(command,outcome.sent?"sent":"failed");
                // EQ / Sound / Timer get a sent/not-sent check; their settings
                // have no independently confirmed readback in this app.
                if (outcome.sent && Prefs.validNoiseState(outcome.state))
                    updateObservedUi(outcome.state);
            }
            // The shared coordinator already queried after the command. No duplicate read.
            if (outcome.sent && !Prefs.validNoiseState(outcome.state)) whenSent.run();
        });
    }

    /** Reflect observed state, not merely the last tapped button. */
    private void updateObservedUi(Protocol.State state) {
        Diagnostics.event(this,"UI_APPLY_READBACK","valid="+Prefs.validNoiseState(state)
                +" state="+(state==null?"none":state.label()));
        if (!Prefs.validNoiseState(state)) return;
        try {
            if (Protocol.ANC.equals(state.mode))
                ancSeek.setProgress(Integer.parseInt(state.level)-1);
            if (Protocol.AMBIENT.equals(state.mode))
                ambientSeek.setProgress(Integer.parseInt(state.level)+3);
        } catch (NumberFormatException ignored) { return; }
        noiseStage="observed";
        showMode(state.mode);
        String label=Protocol.AMBIENT.equals(state.mode)?"Ambient":
                Protocol.WIND.equals(state.mode)?"Wind reduction":
                Protocol.OFF.equals(state.mode)?"Off":"ANC";
        String detail=label+(state.level.isEmpty()?"":" "+state.level);
        noiseStatus.setText("✓ Current: "+detail);
        noiseStatus.setTextColor(accent);
    }

    /** Keep main app in sync when a notification or QS tile sends a command. */
    private void syncCoordinatorState() {
        if (!connected || noiseStatus==null || sending || readingState) return;
        Diagnostics.event(this,"UI_SYNC_EVENT","notif_stage="+Prefs.notificationStage(this)
                +" action="+Prefs.notificationAction(this));
        SharedPreferences p=Prefs.get(this);
        String stage=Prefs.notificationStage(this);
        String action=p.getString("notification_action","");
        String mode=modeOf(action);
        if ("queued".equals(stage) || "sending".equals(stage)
                || "checking".equals(stage)) {
            if (!mode.isEmpty()) {
                noiseStage="sending";
                showMode(mode);
                noiseStatus.setText(Prefs.noiseNotificationStatus(this));
                noiseStatus.setTextColor(pendingColor);
            }
            return;
        }
        String observed=p.getString("observed_noise_mode","");
        String level=p.getString("observed_noise_level","");
        String confidence=p.getString("observed_noise_confidence","UNKNOWN");
        // Old cached observations must not turn a failed or unverified
        // new request into an apparently current selected mode.
        boolean freshRead="observed".equals(stage) || "verified".equals(stage)
                || "reported".equals(stage) || "different".equals(stage);
        if (freshRead && !observed.isEmpty()) {
            Protocol.State state=new Protocol.State(observed,level,confidence,"");
            if (Prefs.validNoiseState(state)) updateObservedUi(state);
        }
        if ("failed".equals(stage) || "different".equals(stage) || "unverified".equals(stage)) {
            if ("failed".equals(stage) && !mode.isEmpty()) {
                noiseStage="failed";
                showMode(mode);
            }
            noiseStatus.setText(Prefs.noiseNotificationStatus(this));
            noiseStatus.setTextColor(failedColor);
        }
    }
    // Preserve v0.3.1 disabled-state feedback.
    private void setCommandButtonsEnabled(boolean enabled) {
        boolean canInteract = enabled && connected;
        float opacity = connected ? 1.0f : 0.45f;
        for (Button button : commandButtons) {
            button.setEnabled(canInteract);
            button.setAlpha(opacity);
        }
        // Only Music/Game/Spatial are held during the five-second wait.
        // ANC, Ambient, Wind, Off, EQ, and sleep controls keep normal timing.
        long left = soundModeReadyAtMs - SystemClock.elapsedRealtime();
        boolean soundReady = left <= 0L;
        for (Map.Entry<String,Button> entry : presetButtons.entrySet()) {
            if (!entry.getKey().startsWith("Sound ")) continue;
            Button b = entry.getValue();
            b.setEnabled(canInteract && soundReady);
            b.setAlpha(!connected ? opacity : soundReady ? 1.0f : 0.50f);
        }
        soundModeTimer.removeCallbacks(soundModeReady);
        if (left > 0L && connected) soundModeTimer.postDelayed(soundModeReady,left);
        for (LinearLayout chip : modeChips) {
            if (chip != null) {
                chip.setEnabled(canInteract);
                chip.setAlpha(opacity);
            }
        }
        if (ancSeek != null) {
            ancSeek.setEnabled(connected);
            ancSeek.setAlpha(opacity);
        }
        if (ambientSeek != null) {
            ambientSeek.setEnabled(connected);
            ambientSeek.setAlpha(opacity);
        }
    }
    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package",getPackageName(),null));
        startActivity(intent);
    }
    private void requestBluetoothPermission() {
        if (BluetoothController.permitted(this)) return;
        if (Build.VERSION.SDK_INT < 31) return;
        if (!Prefs.get(this).getBoolean("bt_permission_requested",false)) {
            Prefs.get(this).edit().putBoolean("bt_permission_requested",true).apply();
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},101);
        } else {
            status.setText("Nearby devices permission needed · tap here for Settings");
            status.setTextColor(failedColor);
        }
    }
    private static String codecName(BluetoothCodecConfig config) {
        if (config==null) return "";
        switch (config.getCodecType()) {
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC: return "LDAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC: return "AAC";
            case BluetoothCodecConfig.SOURCE_CODEC_TYPE_SBC: return "SBC";
            default: return "Other";
        }
    }
    private static String codecList(List<BluetoothCodecConfig> list) {
        if (list==null) return "";
        StringBuilder b = new StringBuilder();
        for (BluetoothCodecConfig config : list) {
            String name = codecName(config);
            if (("LDAC".equals(name) || "AAC".equals(name) || "SBC".equals(name))
                    && b.indexOf(name)<0) {
                if (b.length()>0) b.append(',');
                b.append(name);
            }
        }
        return b.toString();
    }
    private void updateCodec(String selected,String available,String message) {
        if (!connected) return;
        if (selected != null && !selected.isEmpty()) currentCodec=selected;
        if (available!=null) codecAvailable=available;
        if (codecValue!=null) codecValue.setText("Current codec  "
                + (currentCodec.isEmpty()?"—":currentCodec));
        if (codecHint!=null && message!=null) codecHint.setText(message);
        updateCodecButtons();
    }
    private void updateCodecButtons() {
        if (codecRefreshButton!=null) codecRefreshButton.setEnabled(connected && !codecBusy);
        for (Map.Entry<String,Button> entry : codecButtons.entrySet()) {
            String label=entry.getKey();
            Button button=entry.getValue();
            boolean selectable=connected && !codecBusy && !codecAvailable.isEmpty()
                    && (","+codecAvailable+",").contains(","+label+",");
            button.setEnabled(selectable);
            button.setAlpha(selectable?1.0f:0.38f);
            boolean active=label.equals(currentCodec);
            button.setText((active?"✓ ":"")+label);
            button.setTextColor(active?accent:primaryText);
        }
    }
    /** Root is requested only by a user tap, or after an earlier successful opt-in. */
    private void runCodecCommand(String wanted) {
        if (codecBusy || !connected) return;
        if (!"STATUS".equals(wanted) && !codecAvailable.isEmpty()
                && !( ","+codecAvailable+",").contains(","+wanted+",")) {
            codecHint.setText("✗ This codec is not available for WH950NB");
            return;
        }
        final String mac=Prefs.mac(this);
        if (mac == null || !mac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
            codecHint.setText("✗ WH950NB not connected"); return;
        }
        // Switching uses Shizuku user service, not the separate root app_process
        // which could not access the Bluetooth service on this Android 16 build.
        if (!"STATUS".equals(wanted)) {
            runCodecSwitchWithShizuku(wanted, mac);
            return;
        }
        codecBusy=true;
        codecHint.setText("Checking codec…");
        updateCodecButtons();
        Diagnostics.event(this,"CODEC_REQUEST","action="+wanted);
        final String apk=getPackageCodePath();
        new Thread(() -> {
            String line="";
            Process proc=null;
            try {
                // The helper runs only after KernelSU grants this app root access.
                // No codec or system settings are modified for a STATUS query.
                String command="CLASSPATH='"+apk.replace("'","")+"' /system/bin/app_process "
                        +"/system/bin com.xriku.edifiercontrol.CodecRootHelper "
                        +wanted+" "+mac;
                proc=new ProcessBuilder("su","-c",command).redirectErrorStream(true).start();
                boolean ended=proc.waitFor(14,TimeUnit.SECONDS);
                if (!ended) { proc.destroyForcibly(); line="ERROR|—|—|Root request timed out"; }
                else {
                    StringBuilder captured = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                        String item;
                        while ((item=reader.readLine())!=null && captured.length()<5000) {
                            if (item.startsWith("CODEC_RESULT|")) { line=item; break; }
                            captured.append(item).append(' ');
                        }
                    }
                    if (line.isEmpty()) line="ERROR|—|—|Root access or codec service unavailable";
                }
            } catch (Exception error) { line="ERROR|—|—|Root access unavailable"; }
            final String response=line;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                codecBusy=false;
                String[] parts=response.startsWith("CODEC_RESULT|")
                    ? response.substring("CODEC_RESULT|".length()).split("\\|",4)
                    : response.split("\\|",4);
                if (parts.length>=4) {
                    String verdict=parts[0];
                    if ("OK".equals(verdict) || "SWITCHED".equals(verdict) || "UNCHANGED".equals(verdict)) {
                        currentCodec=parts[1];
                        codecAvailable=parts[2];
                        Prefs.get(this).edit().putBoolean("codec_root_opt_in",true).apply();
                        updateCodec(currentCodec,codecAvailable,
                                "SWITCHED".equals(verdict)?"✓ Switched to "+currentCodec
                              : "UNCHANGED".equals(verdict)?"✗ Not switched; still "+currentCodec
                              : "Current codec checked");
                    } else {
                        updateCodec(currentCodec,codecAvailable,"✗ " + parts[3]);
                    }
                    Diagnostics.event(this,"CODEC_RESULT", "action="+wanted+" result="+verdict
                            +" current="+parts[1]);
                } else {
                    updateCodec(currentCodec,codecAvailable,"✗ Could not read codec. Check KernelSU permission.");
                    Diagnostics.event(this,"CODEC_RESULT","action="+wanted+" failed");
                }
                updateCodecButtons();
            });
        },"EdifierCodecControl").start();
    }
    /** Experimental switching through the Shizuku user service API. */
    private void runCodecSwitchWithShizuku(final String wanted, final String mac) {
        try {
            if (!Shizuku.pingBinder()) {
                Diagnostics.event(this,"CODEC_SHIZUKU_CHECK","status=NOT_RUNNING");
                updateCodec(currentCodec,codecAvailable,"✗ Start Shizuku first (root mode)");
                return;
            }
            final int serviceUid=Shizuku.getUid();
            final int permission=Shizuku.checkSelfPermission();
            Diagnostics.event(this,"CODEC_SHIZUKU_CHECK",
                    "status=RUNNING uid="+serviceUid+" allowed="
                    +(permission==PackageManager.PERMISSION_GRANTED));
            if (serviceUid!=0) {
                updateCodec(currentCodec,codecAvailable,"✗ Restart Shizuku using root mode");
                return;
            }
            if (permission!=PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    updateCodec(currentCodec,codecAvailable,"✗ Allow WH950NB Companion in Shizuku");
                } else {
                    pendingShizukuCodec=wanted;
                    updateCodec(currentCodec,codecAvailable,"Approve WH950NB Companion in Shizuku");
                    Shizuku.requestPermission(SHIZUKU_CODEC_PERMISSION);
                }
                return;
            }
        } catch (Throwable error) {
            Diagnostics.event(this,"CODEC_SHIZUKU_CHECK","status=CHECK_FAILED");
            updateCodec(currentCodec,codecAvailable,"✗ Shizuku is not available");
            return;
        }
        codecBusy=true;
        codecHint.setText("Switching to "+wanted+"…");
        updateCodecButtons();
        Diagnostics.event(this,"CODEC_REQUEST","action="+wanted+" via=shizuku");
        final int requestId=++codecRequestSequence;
        final Shizuku.UserServiceArgs args=new Shizuku.UserServiceArgs(
                new ComponentName(getPackageName(),CodecShizukuService.class.getName()))
                .processNameSuffix("codec_service").daemon(false).version(3);
        final Handler handler=new Handler(Looper.getMainLooper());
        final CodecServiceConnection connection=new CodecServiceConnection(
                this,args,handler,requestId,wanted,mac);
        try {
            handler.postDelayed(connection.watchdog,12000L);
            Diagnostics.event(this,"CODEC_SHIZUKU_STAGE","stage=BIND_REQUESTED");
            Shizuku.bindUserService(args,connection);
        } catch (Throwable error) {
            Diagnostics.event(this,"CODEC_SHIZUKU_STAGE","stage=BIND_FAILED");
            handler.removeCallbacks(connection.watchdog);
            finishShizukuSwitch(requestId,wanted,
                    "CODEC_RESULT|ERROR|—||Cannot start Shizuku codec service");
        }
    }

    // Shizuku callback classes are explicitly named for the older Termux D8 compiler.
    private static final class CodecServiceConnection implements ServiceConnection {
        private final java.lang.ref.WeakReference<MainActivity> owner;
        private final Shizuku.UserServiceArgs args;
        private final Handler handler;
        private final int requestId;
        private final String wanted;
        private final String mac;
        final Runnable watchdog;

        CodecServiceConnection(MainActivity activity,Shizuku.UserServiceArgs args,
                Handler handler,int requestId,String wanted,String mac) {
            this.owner=new java.lang.ref.WeakReference<>(activity);
            this.args=args;
            this.handler=handler;
            this.requestId=requestId;
            this.wanted=wanted;
            this.mac=mac;
            this.watchdog=new CodecServiceWatchdog(this);
        }
        void deliver(String reply) {
            MainActivity activity=owner.get();
            if (activity!=null) activity.finishShizukuSwitch(requestId,wanted,reply);
        }
        void unbind() {
            try { Shizuku.unbindUserService(args,this,true); }
            catch (Throwable ignored) { }
        }
        @Override public void onServiceConnected(ComponentName component,IBinder remote) {
            MainActivity activity=owner.get();
            if (activity!=null) Diagnostics.event(activity,"CODEC_SHIZUKU_STAGE",
                    "stage=SERVICE_CONNECTED remote_alive="+(remote!=null && remote.pingBinder()));
            new CodecServiceWorker(this,remote).start();
        }
        @Override public void onServiceDisconnected(ComponentName component) {
            handler.removeCallbacks(watchdog);
            deliver("CODEC_RESULT|ERROR|—||SERVICE_DISCONNECTED");
        }
    }

    private static final class CodecServiceWatchdog implements Runnable {
        private final CodecServiceConnection connection;
        CodecServiceWatchdog(CodecServiceConnection connection) {
            this.connection=connection;
        }
        @Override public void run() {
            MainActivity activity=connection.owner.get();
            if (activity!=null && connection.requestId==activity.codecRequestSequence) {
                connection.deliver("CODEC_RESULT|ERROR|—||SERVICE_TIMED_OUT");
                connection.unbind();
            }
        }
    }

    private static final class CodecServiceWorker extends Thread {
        private final CodecServiceConnection connection;
        private final IBinder remote;
        CodecServiceWorker(CodecServiceConnection connection, IBinder remote) {
            super("EdifierCodecShizuku");
            this.connection=connection;
            this.remote=remote;
        }
        @Override public void run() {
            String response="CODEC_RESULT|ERROR|—||SERVICE_RESPONSE_MISSING";
            Parcel input=Parcel.obtain();
            Parcel output=Parcel.obtain();
            try {
                if (remote==null || !remote.pingBinder()) throw new IllegalStateException();
                input.writeString(connection.wanted);
                input.writeString(connection.mac);
                if (!remote.transact(CodecShizukuService.REQUEST,input,output,0))
                    throw new IllegalStateException();
                output.readException();
                response=output.readString();
                if (response==null) throw new IllegalStateException();
            } catch (SecurityException denied) {
                response="CODEC_RESULT|ERROR|—||SERVICE_PERMISSION_DENIED";
            } catch (Exception failed) {
                response="CODEC_RESULT|ERROR|—||SERVICE_TRANSACT_FAILED";
            }
            finally { input.recycle(); output.recycle(); }
            connection.handler.post(new CodecServiceResponse(connection,response));
        }
    }

    private static final class CodecServiceResponse implements Runnable {
        private final CodecServiceConnection connection;
        private final String response;
        CodecServiceResponse(CodecServiceConnection connection,String response) {
            this.connection=connection;
            this.response=response;
        }
        @Override public void run() {
            connection.handler.removeCallbacks(connection.watchdog);
            connection.deliver(response);
            connection.unbind();
        }
    }
    private static String codecProblemText(String reason) {
        if ("CONTEXT_MISSING".equals(reason))
            return "Shizuku cannot open Bluetooth controls here";
        if ("BLUETOOTH_BRIDGE_UNSUPPORTED".equals(reason))
            return "This Android version needs another codec method";
        if ("BLUETOOTH_BRIDGE_DENIED".equals(reason))
            return "Android blocked Bluetooth access";
        if ("BLUETOOTH_BRIDGE_FAILED".equals(reason)
                || "BLUETOOTH_BRIDGE_STILL_UNAVAILABLE".equals(reason)
                || "ADAPTER_UNAVAILABLE".equals(reason))
            return "Android Bluetooth controls are unavailable here";
        if ("PROFILE_REQUEST_REJECTED".equals(reason)
                || "PROFILE_CONNECTION_TIMEOUT".equals(reason)
                || "PROFILE_UNAVAILABLE".equals(reason))
            return "Android did not open the headphone controls";
        if ("HEADPHONES_NOT_ACTIVE".equals(reason))
            return "Headphones are not connected for audio";
        if ("CODEC_DETAILS_UNAVAILABLE".equals(reason)
                || "REPORT_NOT_AVAILABLE".equals(reason))
            return "Cannot check the headphone codec";
        if ("CODEC_NOT_SELECTABLE".equals(reason))
            return "This codec is not available right now";
        if ("PERMISSION_BLOCKED".equals(reason)
                || "SERVICE_PERMISSION_DENIED".equals(reason))
            return "Android denied permission to change the codec";
        if ("ANDROID_METHOD_MISSING".equals(reason))
            return "This Android version does not support this switch method";
        if ("CODEC_STAYED_THE_SAME".equals(reason))
            return "Codec did not change";
        if ("SERVICE_TIMED_OUT".equals(reason))
            return "Shizuku did not respond in time";
        if ("SERVICE_DISCONNECTED".equals(reason)
                || "SERVICE_TRANSACT_FAILED".equals(reason)
                || "SERVICE_RESPONSE_MISSING".equals(reason))
            return "Shizuku couldn't complete the request";
        return "Android could not change the codec";
    }
    private void finishShizukuSwitch(int requestId,String wanted,String response) {
        if (requestId != codecRequestSequence || isFinishing() || isDestroyed()) return;
        codecRequestSequence++;
        codecBusy=false;
        String[] parts = response != null && response.startsWith("CODEC_RESULT|")
                ? response.substring(13).split("\\|",4) : new String[0];
        if (parts.length==4) {
            String outcome=parts[0];
            if (!"—".equals(parts[1]) && !parts[1].isEmpty()) currentCodec=parts[1];
            if (!parts[2].isEmpty()) codecAvailable=parts[2];
            String reason=parts[3];
            String message = "SWITCHED".equals(outcome) ? "✓ Switched to " + wanted
                    : "UNCHANGED".equals(outcome) ? "✗ Still using " + currentCodec
                    : "✗ " + codecProblemText(reason);
            updateCodec(currentCodec,codecAvailable,message);
            // Never include MAC addresses, device names or sensitive system output.
            Diagnostics.event(this,"CODEC_RESULT","action="+wanted+" via=shizuku result="
                    +outcome+" current="+currentCodec+" reason="+reason);
        } else {
            updateCodec(currentCodec,codecAvailable,"✗ Shizuku codec response unavailable");
        }
        updateCodecButtons();
    }
    // Only accept battery updates from the connected WH950NB, not another device.
    private void onBatteryLevelChanged(Intent intent) {
        if (!connected || !BluetoothController.permitted(this)) return;
        try {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String expected = Prefs.mac(this);
            if (device == null || expected == null || expected.isEmpty()
                    || !expected.equalsIgnoreCase(device.getAddress())) return;
            int level = intent.getIntExtra(BATTERY_EXTRA, -1);
            if (level >= 0 && level <= 100) {
                showBattery(level);
                Diagnostics.event(this,"BATTERY_UPDATE","source=android_broadcast level="+level);
            } else {
                showBatteryUnavailable("Android did not report a percentage");
            }
        } catch (Exception e) {
            showBatteryUnavailable("Android battery data unavailable");
        }
    }
    private void showBattery(int level) {
        if (batteryValue != null) batteryValue.setText("Battery  " + level + "%");
        if (batteryHint != null) batteryHint.setText("Reported by Android Bluetooth");
    }
    private void showBatteryUnavailable(String reason) {
        if (batteryValue != null) batteryValue.setText("Battery  —");
        if (batteryHint != null) batteryHint.setText(reason);
    }
    // The battery getter is a restricted Android interface. Try it safely, then
    // listen for system battery broadcasts if this device doesn't allow the call.
    private void readAndroidBattery(BluetoothDevice device) {
        if (device == null || !BluetoothController.permitted(this)) {
            showBatteryUnavailable("Connect WH950NB to see battery");
            return;
        }
        try {
            java.lang.reflect.Method getter = BluetoothDevice.class.getMethod("getBatteryLevel");
            Object value = getter.invoke(device);
            if (value instanceof Integer) {
                int level = ((Integer) value).intValue();
                if (level >= 0 && level <= 100) {
                    showBattery(level);
                    Diagnostics.event(this,"BATTERY_READ","source=android level="+level);
                    return;
                }
            }
            showBatteryUnavailable("Waiting for Android battery update");
        } catch (Exception | LinkageError error) {
            showBatteryUnavailable("Waiting for Android battery update");
            Diagnostics.event(this,"BATTERY_READ","Android blocks direct battery access");
        }
    }
    private void refreshDevice() {
        Diagnostics.event(this,"CONNECTION_CHECK","screen requests connected headset check");
        if (deviceName == null || status == null) return;
        int requestId = ++deviceRefreshId;
        connected = false;
        showConnectionBadge(false,"Checking…");
        setCommandButtonsEnabled(false);
        if (!BluetoothController.permitted(this)) {
            Prefs.mac(this,"");
            Prefs.headphonesConnected(this,false);
            Prefs.clearObservedNoise(this);
            NotificationHelper.refreshIfEnabled(this);
            deviceName.setText("Nearby devices permission required");
            showConnectionBadge(false,"Permission");
            showBatteryUnavailable("Nearby devices permission required");
            status.setText("Grant Nearby devices permission · tap here for Settings");
            status.setTextColor(failedColor);
            updateCodecButtons();
            return;
        }
        deviceName.setText("Checking connection…");
        ConnectedHeadphones.find(this,(device,message) -> {
            if (requestId != deviceRefreshId || isFinishing()) return;
            connected = device != null;
            Diagnostics.event(this,"CONNECTION_RESULT","connected="+connected+" detail="
                    +(connected?"WH950NB active":message));
            if (device != null) {
                Prefs.mac(this,device.getAddress());
                Prefs.rememberConnectedMac(this,device.getAddress());
                Prefs.headphonesConnected(this,true);
                NotificationHelper.refreshIfEnabled(this);
                String label = device.getName();
                deviceName.setText(label==null?"WH950NB":label);
                showConnectionBadge(true,"Connected");
                readAndroidBattery(device);
                if (codecAutoCheck && !codecBusy && Prefs.get(this).getBoolean("codec_root_opt_in",false)) {
                    codecAutoCheck=false;
                    runCodecCommand("STATUS");
                }
                status.setText("Connected · ready for commands");
                status.setTextColor(secondaryText);
            } else {
                Prefs.mac(this,"");
                Prefs.headphonesConnected(this,false);
                Prefs.clearObservedNoise(this);
                NotificationHelper.refreshIfEnabled(this);
                deviceName.setText(message);
                showConnectionBadge(false,"Disconnected");
                showBatteryUnavailable("Connect WH950NB to see battery");
                currentCodec="";
                codecAvailable="";
                if (codecValue!=null) codecValue.setText("Current codec  —");
                if (codecHint!=null) codecHint.setText("Connect WH950NB to check codec");
                updateCodecButtons();
                status.setText(message.equals("Bluetooth is off") ? "Turn on Bluetooth to use controls"
                    : "Connect your WH950NB in Android Bluetooth settings");
                status.setTextColor(secondaryText);
            }
            setCommandButtonsEnabled(!sending && !readingState);
            updateCodecButtons();
            if (connected) syncCoordinatorState();
            Tiles.refresh(this);
            if (connected && initialReadPending && !sending && !readingState) {
                initialReadPending = false;
                readStateAndSync(true);
            }
        });
    }
    @Override protected void onResume() {
        super.onResume();
        Prefs.get(this).registerOnSharedPreferenceChangeListener(sharedStatusListener);
        initialReadPending = true;
        codecAutoCheck = true;
        if (!listeningForCodec) {
            try {
                IntentFilter codecFilter=new IntentFilter(CODEC_BROADCAST);
                if (Build.VERSION.SDK_INT>=33)
                    registerReceiver(codecEvents,codecFilter,Context.RECEIVER_EXPORTED);
                else registerReceiver(codecEvents,codecFilter);
                listeningForCodec=true;
            } catch (Exception ignored) { }
        }
        if (!listeningForConnection) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            try {
                // System Bluetooth broadcasts only. No background monitoring.
                registerReceiver(connectionEvents,filter);
                listeningForConnection = true;
            } catch (Exception ignored) { }
        }
        if (!listeningForBattery) {
            try {
                IntentFilter batteryFilter = new IntentFilter(BATTERY_ACTION);
                if (Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(batteryEvents, batteryFilter, Context.RECEIVER_EXPORTED);
                } else {
                    registerReceiver(batteryEvents, batteryFilter);
                }
                listeningForBattery = true;
            } catch (Exception e) {
                Diagnostics.event(this,"BATTERY_LISTENER","Android blocked battery notifications");
            }
        }
        refreshDevice();
    }
    @Override protected void onDestroy() {
        soundModeTimer.removeCallbacks(soundModeReady);
        try { Shizuku.removeRequestPermissionResultListener(codecShizukuPermission); }
        catch (Throwable ignored) { }
        super.onDestroy();
    }
    @Override protected void onPause() {
        super.onPause();
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(sharedStatusListener);
        if (listeningForConnection) {
            try { unregisterReceiver(connectionEvents); } catch (Exception ignored) { }
            listeningForConnection = false;
        }
        if (listeningForBattery) {
            try { unregisterReceiver(batteryEvents); } catch (Exception ignored) { }
            listeningForBattery = false;
        }
        if (listeningForCodec) {
            try { unregisterReceiver(codecEvents); } catch (Exception ignored) { }
            listeningForCodec=false;
        }
        codecAutoCheck=false;
        initialReadPending = false;
        deviceRefreshId++; // Ignore late Bluetooth proxy callbacks.
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(code,permissions,results);
        if (code==101) refreshDevice();
        if (code==102 && results.length>0 && results[0]==PackageManager.PERMISSION_GRANTED) {
            notificationSwitch.setChecked(true);
        }
    }
}
