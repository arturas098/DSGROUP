package com.example.wifipresence;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** No camera, cloud, CSI, background capture, or fake occupancy claims. */
public final class MainActivity extends Activity implements SensorEventListener {
    private static final int REQ_PERMISSIONS = 100;
    private static final int REQ_SAVE_CSV = 101;
    private static final int CYAN = Color.rgb(86, 215, 199);
    private static final int TEXT = Color.rgb(237, 243, 250);
    private static final int MUTED = Color.rgb(148, 166, 185);
    private static final int YELLOW = Color.rgb(255, 192, 91);
    private static final int RED = Color.rgb(255, 108, 122);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SignalAnalyzer analyzer = new SignalAnalyzer();
    private final ArrayList<String> csv = new ArrayList<>();
    private final ArrayDeque<Integer> plot = new ArrayDeque<>();
    private WifiManager wifi;
    private volatile WifiInfo callbackWifiInfo;
    private volatile Network callbackWifiNetwork;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private ConnectivityManager connectivity;
    private SensorManager sensors;
    private Sensor accelerometer;
    private WifiChart chart;
    private TextView headline, statRssi, statScore, status, metadata, baselineInfo, rttInfo, sampleInfo, diagnostics;
    private Button toggle, calibrate, measure;
    private boolean running, visible, isRttScanning;
    private long lastPhoneMotionMs = -100000L;
    private double filteredAccel = 9.81;
    private int samples, lastRssi = -127;
    private long lastSampleMs;
    private BroadcastReceiver scanReceiver;
    private final SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.rgb(16, 24, 39));
        getWindow().setNavigationBarColor(Color.rgb(16, 24, 39));
        wifi = (WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
        connectivity = (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        sensors = (SensorManager)getSystemService(SENSOR_SERVICE);
        accelerometer = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        makeUi();
        askPermissions();
    }

    private void makeUi() {
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(Color.rgb(16,24,39));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        root.setPadding(p, dp(24), p, dp(30));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(p, dp(20) + insets.getSystemWindowInsetTop(), p,
                    dp(30) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroller.addView(root);
        setContentView(scroller);

        add(root, "WI-FI PRESENCE LAB", 14, CYAN, true, 0, 5);
        add(root, "Router signal scanner", 27, TEXT, true, 0, 8);
        add(root, "An experiment in signal changes, NOT a verified human-presence sensor.", 13, MUTED, false, 0, 20);

        headline = add(root, "READY TO SCAN", 17, CYAN, true, 0, 8);
        status = add(root, "Connect to Wi-Fi, leave this phone stationary, then calibrate the empty room.", 14, TEXT, false, 0, 18);

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(stats);
        LinearLayout a = panel(stats, 1);
        add(a, "SIGNAL", 11, MUTED, true, 0, 8);
        statRssi = add(a, "-- dBm", 25, TEXT, true, 0, 5);
        add(a, "Router → phone", 11, MUTED, false, 0, 0);
        LinearLayout b = panel(stats, 1);
        add(b, "ANOMALY", 11, MUTED, true, 0, 8);
        statScore = add(b, "-- / 100", 25, TEXT, true, 0, 5);
        add(b, "Not probability", 11, MUTED, false, 0, 0);

        add(root, "SIGNAL HISTORY (RSSI dBm)", 12, MUTED, true, 22, 10);
        chart = new WifiChart(this);
        root.addView(chart, new LinearLayout.LayoutParams(-1, dp(170)));
        sampleInfo = add(root, "No samples yet", 12, MUTED, false, 8, 15);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actions);
        toggle = button(actions, "Start", CYAN, () -> {
            if (!hasPermissions()) { askPermissions(); return; }
            running = !running;
            toggle.setText(running ? "Pause" : "Start");
            if (running) handler.post(ticker);
            else { headline.setText("PAUSED"); headline.setTextColor(MUTED); }
        });
        calibrate = button(actions, "Calibrate (35 s)", Color.rgb(68, 100, 137), () -> {
            if (!hasPermissions()) { askPermissions(); return; }
            if (!running) { running = true; toggle.setText("Pause"); }
            analyzer.beginCalibration();
            csv.clear();
            plot.clear();
            samples = 0;
            lastSampleMs = 0;
            lastPhoneMotionMs = -100000L;
            headline.setText("CALIBRATING");
            status.setText("Room empty. Do not touch the phone for 35 seconds.");
            handler.post(ticker);
        });
        metadata = add(root, "Wi-Fi: waiting for signal reading", 12, MUTED, false, 15, 7);
        diagnostics = add(root, "Diagnostics: press Start to check Wi-Fi.", 12, MUTED, false, 0, 10);
        LinearLayout settingsRow = new LinearLayout(this);
        settingsRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(settingsRow);
        button(settingsRow, "Wi-Fi settings", Color.rgb(68,100,137), () ->
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        button(settingsRow, "Location settings", Color.rgb(68,100,137), () ->
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)));
        baselineInfo = add(root, "Baseline: not calibrated", 12, MUTED, false, 0, 17);

        add(root, "OPTIONAL DISTANCE TEST", 13, CYAN, true, 0, 7);
        rttInfo = add(root, "Wi-Fi RTT needs support in BOTH the phone and router. RSSI alone does not tell distance.", 13, MUTED, false, 0, 8);
        measure = button(root, "Try Wi-Fi RTT distance", Color.rgb(68,100,137), this::tryRtt);
        add(root, "", 7, MUTED, false, 0, 0);
        button(root, "Export measurements (CSV)", Color.rgb(68,100,137), this::exportCsv);
        add(root, "HOW TO TEST", 12, CYAN, true, 20, 8);
        add(root, "1. Put router and phone apart, with the area between them clear.\n"
                + "2. Plug in the phone and keep it still with the app open.\n"
                + "3. Calibrate for 35 seconds with nobody moving.\n"
                + "4. Walk across the line between phone and router.\n"
                + "5. Compare anomalies, repeat, and export the data.\n\n"
                + "Objects, doors, router power control, radio interference, Wi-Fi roaming, "
                + "or a moving phone can all cause false detections. Static presence is not reliably detected. "
                + "Android may cache RSSI readings.", 13, MUTED, false, 0, 10);
        add(root, "Private by design: data stays on this phone until you choose Export. No Internet permission, camera, microphone, or location uploads.",
                12, MUTED, false, 4, 0);
    }

    private LinearLayout panel(LinearLayout row, int weight) {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setPadding(dp(12), dp(12), dp(8), dp(13));
        outer.setBackgroundColor(Color.rgb(29,42,58));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, weight);
        lp.setMargins(0, 0, dp(8), 0);
        row.addView(outer, lp);
        return outer;
    }
    private TextView add(LinearLayout root, String text, int sp, int color, boolean bold, int top, int bottom) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(sp);
        if (bold) v.setTypeface(null, 1);
        v.setLineSpacing(dp(2), 1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(top), 0, dp(bottom));
        root.addView(v, lp);
        return v;
    }
    private Button button(LinearLayout root, String text, int bg, Runnable cb) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(13);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(root.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1, dp(49), 1);
        lp.setMargins(0, 0, dp(7), dp(7));
        root.addView(b, lp);
        b.setOnClickListener(v -> cb.run());
        return b;
    }
    private int dp(float a) { return (int)(a*getResources().getDisplayMetrics().density + .5f); }
    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED);
    }
    private void askPermissions() {
        if (hasPermissions()) return;
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_PERMISSIONS);
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_PERMISSIONS);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (hasPermissions()) {
                registerWifiCallback();
                diagnostics.setText("Permissions granted. Press Start to read Wi-Fi signal.");
            } else {
                headline.setText("PERMISSION NEEDED");
                status.setText("Allow Nearby devices and Precise location. Android uses these for Wi-Fi info; the app does not request GPS coordinates.");
                diagnostics.setText(diagnosticLine("permissions missing"));
            }
        }
    }
    @Override protected void onResume() {
        super.onResume(); visible = true;
        registerWifiCallback();
        if (accelerometer != null) sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        if (running) handler.post(ticker);
    }
    @Override protected void onPause() {
        super.onPause(); visible = false;
        handler.removeCallbacks(ticker);
        unregisterWifiCallback();
        if (sensors != null) sensors.unregisterListener(this);
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        unregisterScanReceiver();
        unregisterWifiCallback();
        super.onDestroy();
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            handler.removeCallbacks(this);
            if (!visible || !running) return;
            readRssi();
            handler.postDelayed(this, 1000);
        }
    };

    /** For Android 12+, callbacks with FLAG_INCLUDE_LOCATION_INFO can receive
     * non-redacted WifiInfo; the synchronous capabilities API may redact it.
     * getNetworkId() is NOT a valid RSSI availability check: it can be -1
     * while connected because network identity has been hidden by Android.
     */
    private void registerWifiCallback() {
        if (wifiCallback != null || !hasPermissions() || connectivity == null) return;
        try {
            wifiCallback = Build.VERSION.SDK_INT >= 31
                    ? new LocationWifiCallback() : new WifiCallback();
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build();
            connectivity.registerNetworkCallback(request, wifiCallback);
        } catch (Exception ex) {
            wifiCallback = null; // Older OEMs may not support the callback route.
        }
    }
    private void unregisterWifiCallback() {
        if (wifiCallback != null) {
            try { connectivity.unregisterNetworkCallback(wifiCallback); }
            catch (Exception ignored) { }
            wifiCallback = null;
        }
        callbackWifiInfo = null;
        callbackWifiNetwork = null;
    }
    private void updateWifiFromCallback(Network network, NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && capabilities.getTransportInfo() instanceof WifiInfo) {
            callbackWifiInfo = (WifiInfo) capabilities.getTransportInfo();
            callbackWifiNetwork = network;
        }
    }
    private void wifiLost(Network network) {
        if (network.equals(callbackWifiNetwork)) {
            callbackWifiInfo = null;
            callbackWifiNetwork = null;
        }
    }
    private class WifiCallback extends ConnectivityManager.NetworkCallback {
        WifiCallback() { super(); }
        WifiCallback(int flags) { super(flags); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) {
            updateWifiFromCallback(n, c);
        }
        @Override public void onLost(Network n) { wifiLost(n); }
    }
    private final class LocationWifiCallback extends WifiCallback {
        // Android 12+ opted in to location-sensitive Wi-Fi information.
        LocationWifiCallback() { super(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO); }
    }

    private boolean validRssi(WifiInfo info) {
        // -127 represents invalid RSSI on Android; networkId=-1 can mean redacted.
        return info != null && info.getRssi() > -126 && info.getRssi() < 0;
    }
    private boolean locationEnabled() {
        try {
            LocationManager lm = (LocationManager)getSystemService(LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } catch (Exception ex) { return false; }
    }
    private boolean wifiEnabled() {
        try { return wifi != null && wifi.isWifiEnabled(); }
        catch (Exception ex) { return false; }
    }
    private boolean anyWifiNetwork() {
        try {
            for (Network n : connectivity.getAllNetworks()) {
                NetworkCapabilities c = connectivity.getNetworkCapabilities(n);
                if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
    private String diagnosticLine(String source) {
        return "Diagnostics: Wi-Fi " + (wifiEnabled() ? "ON" : "OFF")
                + "  •  Location " + (locationEnabled() ? "ON" : "OFF")
                + "  •  Precise + Nearby " + (hasPermissions() ? "OK" : "missing")
                + "  •  Wi-Fi network " + (anyWifiNetwork() ? "found" : "not reported")
                + "  •  source: " + source;
    }
    private void readRssi() {
        if (!hasPermissions()) {
            headline.setText("PERMISSION NEEDED"); headline.setTextColor(YELLOW);
            status.setText("Allow Precise location and Nearby devices in app permissions.");
            diagnostics.setText(diagnosticLine("permissions missing"));
            return;
        }
        if (!wifiEnabled()) {
            headline.setText("WI-FI OFF"); headline.setTextColor(YELLOW);
            status.setText("Enable Wi-Fi in Android settings.");
            diagnostics.setText(diagnosticLine("Wi-Fi disabled"));
            return;
        }
        // On current Android, WifiManager's legacy accessor remains useful for
        // live RSSI. Callback is the second route and can include location info.
        WifiInfo info = null;
        String source = "legacy Wi-Fi manager";
        try {
            info = wifi.getConnectionInfo();
            if (!validRssi(info)) {
                info = callbackWifiInfo;
                source = "Wi-Fi network callback";
            }
            if (!validRssi(info)) {
                Network active = connectivity.getActiveNetwork();
                NetworkCapabilities caps = active == null ? null : connectivity.getNetworkCapabilities(active);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && caps.getTransportInfo() instanceof WifiInfo) {
                    info = (WifiInfo) caps.getTransportInfo();
                    source = "active network";
                }
            }
            if (!validRssi(info)) {
                for (Network network : connectivity.getAllNetworks()) {
                    NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            && caps.getTransportInfo() instanceof WifiInfo) {
                        WifiInfo candidate = (WifiInfo)caps.getTransportInfo();
                        if (validRssi(candidate)) {
                            info = candidate; source = "available Wi-Fi network"; break;
                        }
                    }
                }
            }
        } catch (SecurityException ex) {
            headline.setText("WI-FI ACCESS RESTRICTED"); headline.setTextColor(YELLOW);
            status.setText("Android denied Wi-Fi access. Check Precise location and Nearby devices permissions.");
            diagnostics.setText(diagnosticLine("permission error"));
            return;
        } catch (Exception ex) {
            headline.setText("WI-FI READ ERROR"); headline.setTextColor(YELLOW);
            status.setText("Failed to read Android Wi-Fi data: " + ex.getClass().getSimpleName());
            diagnostics.setText(diagnosticLine("read exception"));
            return;
        }
        if (!validRssi(info)) {
            headline.setText("SIGNAL READING UNAVAILABLE"); headline.setTextColor(YELLOW);
            status.setText(!locationEnabled()
                    ? "Turn ON the phone's Location switch (button below), then reopen the app."
                    : "Wi-Fi is ON, but Android did not provide usable RSSI. Try Wi-Fi off/on, then reopen the app.");
            diagnostics.setText(diagnosticLine("RSSI unavailable"));
            metadata.setText("Wi-Fi signal unavailable; may be blocked by Android or device firmware.");
            return;
        }
        diagnostics.setText(diagnosticLine(source) + "  •  network ID " + info.getNetworkId());
        int rssi = info.getRssi();
        lastRssi = rssi;
        long now = SystemClock.elapsedRealtime();
        boolean phoneMoved = now - lastPhoneMotionMs < 3500;
        if (analyzer.isCalibrating() && phoneMoved) analyzer.beginCalibration();
        analyzer.sample(rssi, now, phoneMoved);
        samples++;
        lastSampleMs = now;
        plot.addLast(rssi);
        while (plot.size() > 150) plot.removeFirst();
        chart.invalidate();
        String ssid = info.getSSID();
        if (ssid == null || ssid.equals("<unknown ssid>")) ssid = "Wi-Fi network";
        metadata.setText("Network: " + ssid + "  •  " + info.getFrequency() + " MHz  •  " + info.getLinkSpeed() + " Mbps");
        statRssi.setText(rssi + " dBm");
        statScore.setText(analyzer.isCalibrated() ? String.format(Locale.US, "%.0f / 100", analyzer.score()) : "-- / 100");
        if (analyzer.isCalibrating()) {
            headline.setText("CALIBRATING  " + analyzer.calibrationCount() + "/" + SignalAnalyzer.CALIBRATION_SAMPLES);
            headline.setTextColor(CYAN);
            status.setText(phoneMoved ? "Phone moved! Restart calibration with phone stationary." : "Empty room, keep phone stationary. Capture baseline RSSI.");
        } else if (!analyzer.isCalibrated()) {
            headline.setText("SCANNING – NO BASELINE"); headline.setTextColor(YELLOW);
            status.setText("Use Calibrate (35 s) to enable anomaly detection.");
        } else if (phoneMoved) {
            headline.setText("PHONE MOVED"); headline.setTextColor(YELLOW);
            status.setText("Motion sensor saw phone movement. Wi-Fi changes cannot be attributed to room activity.");
        } else if (analyzer.disturbance()) {
            headline.setText("SIGNAL DISTURBANCE"); headline.setTextColor(RED);
            status.setText("RSSI deviates from empty-room baseline. Could be movement OR another radio/environmental change.");
        } else {
            headline.setText("NO LARGE CHANGE"); headline.setTextColor(CYAN);
            status.setText("No strong RSSI anomaly detected. This does NOT establish that the area is empty.");
        }
        if (analyzer.isCalibrated()) baselineInfo.setText(String.format(Locale.US,
                "Baseline %.1f dBm  •  noise σ %.1f dB  •  %d distinct RSSI levels during calibration",
                analyzer.baseline(), analyzer.baselineSd(), analyzer.baselineDistinctReadings()));
        sampleInfo.setText(samples + " samples • updated each 1 s • " +
                (analyzer.msSinceChange(now) < 0 ? "RSSI change not observed" :
                        "RSSI value unchanged for " + analyzer.msSinceChange(now)/1000 + " s"));
        csv.add(iso.format(new Date()) + "," + rssi + "," + String.format(Locale.US,"%.2f",analyzer.score())
                + "," + analyzer.disturbance() + "," + phoneMoved + "," + analyzer.isCalibrated());
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() != Sensor.TYPE_ACCELEROMETER) return;
        double mag = Math.sqrt(event.values[0]*event.values[0] + event.values[1]*event.values[1] + event.values[2]*event.values[2]);
        filteredAccel = .85*filteredAccel + .15*mag;
        if (Math.abs(mag - filteredAccel) > .65) lastPhoneMotionMs = SystemClock.elapsedRealtime();
    }
    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void exportCsv() {
        if (csv.isEmpty()) { toast("No samples to export"); return; }
        Intent in = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        in.addCategory(Intent.CATEGORY_OPENABLE);
        in.setType("text/csv");
        in.putExtra(Intent.EXTRA_TITLE, "wifi-presence-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".csv");
        startActivityForResult(in, REQ_SAVE_CSV);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE_CSV || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        StringBuilder content = new StringBuilder("local_time,rssi_dbm,anomaly_score,signal_disturbance,phone_moved,calibrated\n");
        for (String s : csv) content.append(s).append('\n');
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            if (out == null) throw new IllegalStateException("Cannot open destination");
            out.write(content.toString().getBytes(StandardCharsets.UTF_8));
            toast("CSV saved");
        } catch (Exception ex) { toast("Save failed: " + ex.getMessage()); }
    }

    private void tryRtt() {
        if (!hasPermissions()) { askPermissions(); return; }
        if (isRttScanning) { toast("RTT test already in progress"); return; }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_RTT)) {
            rttInfo.setText("Wi-Fi RTT not supported by this phone's exposed Android hardware features."); return;
        }
        WifiRttManager rtt = (WifiRttManager)getSystemService(WIFI_RTT_RANGING_SERVICE);
        if (rtt == null || !rtt.isAvailable()) {
            rttInfo.setText("Wi-Fi RTT unavailable. Enable Wi-Fi and Location in Android Settings."); return;
        }
        if (!wifi.isWifiEnabled()) { rttInfo.setText("Enable Wi-Fi first."); return; }
        isRttScanning = true;
        measure.setEnabled(false);
        rttInfo.setText("Looking for an RTT-capable router in Android scan results…");
        unregisterScanReceiver();
        scanReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                unregisterScanReceiver();
                rangeFromScan(rtt);
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver,
                    new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
            if (!wifi.startScan()) {
                unregisterScanReceiver();
                rangeFromScan(rtt); // cached scan results may be usable
            } else {
                handler.postDelayed(() -> {
                    if (isRttScanning) { unregisterScanReceiver(); rangeFromScan(rtt); }
                }, 7000);
            }
        } catch (Exception ex) {
            unregisterScanReceiver();
            failRtt("Scan blocked: " + ex.getMessage());
        }
    }
    private void rangeFromScan(WifiRttManager rtt) {
        if (!isRttScanning) return;
        try {
            WifiInfo info = wifi.getConnectionInfo();
            String bssid = info == null ? null : info.getBSSID();
            ScanResult target = null;
            for (ScanResult result : wifi.getScanResults()) {
                if (bssid != null && bssid.equalsIgnoreCase(result.BSSID)) { target = result; break; }
            }
            if (target == null) { failRtt("Connected router not in Wi-Fi scan results. Try again with Location on."); return; }
            if (!target.is80211mcResponder()) { failRtt("This router does not advertise 802.11mc RTT/FTM. Distance unavailable; RSSI scan still works."); return; }
            RangingRequest request = new RangingRequest.Builder().addAccessPoint(target).build();
            rttInfo.setText("Measuring round-trip time to your router…");
            rtt.startRanging(request, getMainExecutor(), new RangingResultCallback() {
                @Override public void onRangingFailure(int code) { failRtt("RTT measurement failed: Android code " + code); }
                @Override public void onRangingResults(List<RangingResult> results) {
                    if (results.isEmpty() || results.get(0).getStatus() != RangingResult.STATUS_SUCCESS) {
                        failRtt("Router supports RTT, but no successful distance estimate returned."); return;
                    }
                    RangingResult reading = results.get(0);
                    double meters = reading.getDistanceMm()/1000.0;
                    double error = reading.getDistanceStdDevMm()/1000.0;
                    rttInfo.setText(String.format(Locale.US,
                            "Router range estimate: %.2f m  •  reported std dev: %.2f m.\nNot a precise room-occupancy reading.", meters, error));
                    endRtt();
                }
            });
        } catch (Exception ex) { failRtt("RTT unavailable: " + ex.getMessage()); }
    }
    private void unregisterScanReceiver() {
        if (scanReceiver == null) return;
        try { unregisterReceiver(scanReceiver); } catch (Exception ignored) { }
        scanReceiver = null;
    }
    private void failRtt(String message) { rttInfo.setText(message); endRtt(); }
    private void endRtt() { isRttScanning = false; measure.setEnabled(true); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private final class WifiChart extends View {
        private final Paint pen = new Paint(3);
        WifiChart(Context c) { super(c); setBackgroundColor(Color.rgb(25,36,51)); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float left = dp(44), right = getWidth()-dp(14), top = dp(14), bottom = getHeight()-dp(24);
            pen.setStrokeWidth(dp(1)); pen.setTextSize(dp(10)); pen.setTypeface(null);
            for (int r = -90; r <= -30; r+=15) {
                float y = bottom - (r+90)/60f * (bottom-top);
                pen.setColor(Color.rgb(52,66,83)); canvas.drawLine(left,y,right,y,pen);
                pen.setColor(MUTED); canvas.drawText(r+"",dp(7),y+dp(4),pen);
            }
            pen.setColor(MUTED);
            canvas.drawText("oldest",left,bottom+dp(16),pen);
            canvas.drawText("now",right-dp(21),bottom+dp(16),pen);
            if (plot.size() < 2) return;
            pen.setColor(CYAN); pen.setStrokeWidth(dp(2));
            int count = plot.size(), idx = 0;
            float px = 0, py = 0;
            for (int r : plot) {
                float x = left + idx * (right-left) / (count-1);
                float y = bottom - Math.max(0, Math.min(1, (r+90)/60f)) * (bottom-top);
                if (idx > 0) canvas.drawLine(px,py,x,y,pen);
                px = x; py = y; idx++;
            }
        }
    }
}
