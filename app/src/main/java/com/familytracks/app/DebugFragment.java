package com.familytracks.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Debug diagnostics screen showing service stats, packet counts,
 * sync status, and runtime info. Refreshes every 5 seconds.
 */
public class DebugFragment extends Fragment
{
    private TextView theDebugText;
    private Handler theRefreshHandler;
    private Runnable theRefreshRunnable;
    private SimpleDateFormat theDateFormat;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
        View view = inflater.inflate(R.layout.fragment_debug, container, false);
        theDebugText = view.findViewById(R.id.debugText);
        theDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

        theRefreshHandler = new Handler(Looper.getMainLooper());
        theRefreshRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                refreshDebugInfo();
                theRefreshHandler.postDelayed(theRefreshRunnable, 5000);
            }
        };

        return view;
    }

    @Override
    public void onResume()
    {
        super.onResume();
        theRefreshHandler.post(theRefreshRunnable);
    }

    @Override
    public void onPause()
    {
        super.onPause();
        theRefreshHandler.removeCallbacks(theRefreshRunnable);
    }

    private void refreshDebugInfo()
    {
        Context ctx = requireContext();

        SharedPreferences debug = ctx.getSharedPreferences(
                LocationService.PREFS_DEBUG, Context.MODE_PRIVATE);
        SharedPreferences track = ctx.getSharedPreferences(
                LocationService.PREFS_TRACKING, Context.MODE_PRIVATE);
        SharedPreferences sync = ctx.getSharedPreferences(
                "family_tracks_sync", Context.MODE_PRIVATE);
        SharedPreferences userPrefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        ServerConfig config = new ServerConfig();
        boolean configured = config.load(ctx);

        StringBuilder sb = new StringBuilder();

        // --- Server Config ---
        sb.append("=== SERVER ===\n");
        if (configured)
        {
            sb.append("Host: ").append(config.getHost()).append("\n");
            sb.append("UDP Port: ").append(config.getPort()).append("\n");
            sb.append("Web: ").append(config.getWebBaseUrl()).append("\n");
            sb.append("User ID: ").append(config.getUserId()).append("\n");
        }
        else
        {
            sb.append("Not configured\n");
        }

        // --- Service State ---
        sb.append("\n=== SERVICE ===\n");
        boolean tracking = track.getBoolean("tracking", false);
        sb.append("Tracking: ").append(tracking ? "ACTIVE" : "STOPPED").append("\n");

        long startedAt = debug.getLong("serviceStarted", 0);
        if (startedAt > 0)
        {
            sb.append("Started: ").append(formatTime(startedAt)).append("\n");
            long uptimeMs = System.currentTimeMillis() - startedAt;
            sb.append("Uptime: ").append(formatDuration(uptimeMs)).append("\n");
        }

        long stoppedAt = debug.getLong("serviceStopped", 0);
        if (stoppedAt > 0 && !tracking)
        {
            sb.append("Stopped: ").append(formatTime(stoppedAt)).append("\n");
        }

        // --- Mode ---
        sb.append("\n=== TRACKING MODE ===\n");
        boolean coarse = track.getBoolean("coarseMode", false);
        String geofence = track.getString("geofenceName", null);
        String interval = userPrefs.getString("reporting_interval", "60");
        boolean finePref = userPrefs.getBoolean("fine_location", true);

        sb.append("User setting: ").append(finePref ? "Fine GPS" : "Coarse GPS").append("\n");
        sb.append("Interval setting: ").append(interval).append("s\n");
        sb.append("Active mode: ").append(coarse ? "COARSE (auto)" : "FINE").append("\n");
        if (geofence != null)
        {
            long enteredAt = track.getLong("geofenceEnteredAt", 0);
            long insideMs = System.currentTimeMillis() - enteredAt;
            sb.append("Inside geofence: ").append(geofence)
              .append(" (").append(formatDuration(insideMs)).append(")\n");
        }

        // --- Packets ---
        sb.append("\n=== PACKETS ===\n");
        long packetsSent = debug.getLong("packetsSent", 0);
        long locationUpdates = debug.getLong("locationUpdates", 0);
        sb.append("Location updates: ").append(locationUpdates).append("\n");
        sb.append("UDP packets sent: ").append(packetsSent).append("\n");

        long lastPacket = debug.getLong("lastPacketTime", 0);
        if (lastPacket > 0)
        {
            sb.append("Last packet: ").append(formatTime(lastPacket));
            sb.append(" (").append(formatAgo(lastPacket)).append(" ago)\n");
        }

        long lastLoc = debug.getLong("lastLocationTime", 0);
        if (lastLoc > 0)
        {
            sb.append("Last location: ").append(formatTime(lastLoc));
            sb.append(" (").append(formatAgo(lastLoc)).append(" ago)\n");
        }

        String lastCoords = debug.getString("lastLocationCoords", null);
        if (lastCoords != null)
        {
            sb.append("Last coords: ").append(lastCoords).append("\n");
        }

        // --- Sync ---
        sb.append("\n=== SERVER SYNC ===\n");
        long syncCount = debug.getLong("syncCount", 0);
        sb.append("Sync count: ").append(syncCount).append("\n");

        long lastSync = debug.getLong("lastSyncTime", 0);
        if (lastSync > 0)
        {
            sb.append("Last sync: ").append(formatTime(lastSync));
            sb.append(" (").append(formatAgo(lastSync)).append(" ago)\n");
        }

        String syncResult = debug.getString("lastSyncResult", null);
        if (syncResult != null)
        {
            sb.append("Result: ").append(syncResult).append("\n");
        }

        long lastSyncPref = sync.getLong("lastSync", 0);
        String geofencesJson = sync.getString("geofences", "[]");
        int fenceCount = 0;
        try
        {
            fenceCount = new org.json.JSONArray(geofencesJson).length();
        }
        catch (Exception ignored) { }
        sb.append("Cached geofences: ").append(fenceCount).append("\n");

        // --- Errors ---
        sb.append("\n=== ERRORS ===\n");
        long errors = debug.getLong("errors", 0);
        sb.append("Error count: ").append(errors).append("\n");

        String lastError = debug.getString("lastError", null);
        if (lastError != null)
        {
            sb.append("Last error: ").append(lastError).append("\n");
        }
        else
        {
            sb.append("No errors\n");
        }

        // --- Battery / Permissions ---
        sb.append("\n=== SYSTEM ===\n");
        boolean sendBattery = userPrefs.getBoolean("send_battery", true);
        sb.append("Send battery: ").append(sendBattery).append("\n");
        sb.append("Send speed: ").append(userPrefs.getBoolean("send_speed", true)).append("\n");

        android.os.PowerManager pm = (android.os.PowerManager)
                ctx.getSystemService(Context.POWER_SERVICE);
        sb.append("Battery opt exempt: ")
          .append(pm.isIgnoringBatteryOptimizations(ctx.getPackageName())).append("\n");

        theDebugText.setText(sb.toString());
    }

    private String formatTime(long ms)
    {
        return theDateFormat.format(new Date(ms));
    }

    private String formatAgo(long ms)
    {
        long ago = System.currentTimeMillis() - ms;
        return formatDuration(ago);
    }

    private String formatDuration(long ms)
    {
        long sec = ms / 1000;
        if (sec < 60)
        {
            return sec + "s";
        }
        long min = sec / 60;
        if (min < 60)
        {
            return min + "m " + (sec % 60) + "s";
        }
        long hr = min / 60;
        return hr + "h " + (min % 60) + "m";
    }
}
