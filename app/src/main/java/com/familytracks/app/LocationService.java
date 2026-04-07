package com.familytracks.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that collects GPS locations and sends them
 * to the server as encrypted UDP packets. Also syncs geofence
 * data from the server once per hour and does smart mode switching
 * (fine -> coarse when sitting inside a geofence).
 */
public class LocationService extends Service
{
    private static final String TAG = "LocationService";
    private static final String CHANNEL_ID = "family_tracks_location";
    private static final int NOTIFICATION_ID = 1;
    private static final long SYNC_INTERVAL_MS = 60 * 60 * 1000;  // 1 hour
    private static final long COARSE_SWITCH_MS = 30 * 60 * 1000;  // 30 minutes

    public static final String ACTION_START = "com.familytracks.app.START";
    public static final String ACTION_STOP = "com.familytracks.app.STOP";

    // SharedPreferences keys (read by StatusFragment and DebugFragment)
    public static final String PREFS_TRACKING = "family_tracks_tracking";
    public static final String PREFS_DEBUG = "family_tracks_debug";

    private FusedLocationProviderClient theLocationClient;
    private LocationCallback theLocationCallback;
    private PacketSender theSender;
    private ServerConfig theConfig;
    private SimpleDateFormat theDateFormat;
    private Handler theSyncHandler;
    private Runnable theSyncRunnable;

    // Smart mode tracking
    private String theCurrentGeofenceName;
    private long theGeofenceEnteredAt;
    private boolean theCoarseMode;

    @Override
    public void onCreate()
    {
        super.onCreate();

        theConfig = new ServerConfig();
        theConfig.load(this);
        theSender = new PacketSender(theConfig);
        theDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

        theCurrentGeofenceName = null;
        theGeofenceEnteredAt = 0;
        theCoarseMode = false;

        theLocationClient = LocationServices.getFusedLocationProviderClient(this);

        theLocationCallback = new LocationCallback()
        {
            @Override
            public void onLocationResult(LocationResult result)
            {
                Location loc = result.getLastLocation();
                if (loc != null)
                {
                    onNewLocation(loc);
                }
            }
        };

        theSyncHandler = new Handler(Looper.getMainLooper());
        theSyncRunnable = new Runnable()
        {
            @Override
            public void run()
            {
                syncWithServer();
                theSyncHandler.postDelayed(theSyncRunnable, SYNC_INTERVAL_MS);
            }
        };

        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        if (intent != null && ACTION_STOP.equals(intent.getAction()))
        {
            Log.i(TAG, "Stopping location service");
            stopLocationUpdates();
            theSyncHandler.removeCallbacks(theSyncRunnable);
            stopForeground(STOP_FOREGROUND_REMOVE);
            writeTrackingState(false, false, null, 0);
            debugLog("serviceStopped", System.currentTimeMillis());
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.i(TAG, "Starting location service");
        startForeground(NOTIFICATION_ID, buildNotification());
        startLocationUpdates(false);
        writeTrackingState(true, false, null, 0);
        debugLog("serviceStarted", System.currentTimeMillis());
        debugLog("packetsSent", 0);
        debugLog("locationUpdates", 0);
        debugLog("errors", 0);

        theSyncHandler.post(theSyncRunnable);

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return null;
    }

    @Override
    public void onDestroy()
    {
        stopLocationUpdates();
        theSyncHandler.removeCallbacks(theSyncRunnable);
        writeTrackingState(false, false, null, 0);
        super.onDestroy();
    }

    private void startLocationUpdates(boolean forceCoarse)
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String intervalStr = prefs.getString("reporting_interval", "60");
        long intervalMs = Long.parseLong(intervalStr) * 1000;

        boolean userWantsFine = prefs.getBoolean("fine_location", true);
        boolean useFine = userWantsFine && !forceCoarse;

        int priority = useFine ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;

        // When in coarse mode, also slow down the reporting interval
        if (forceCoarse)
        {
            intervalMs = Math.max(intervalMs, 5 * 60 * 1000);  // at least 5 minutes
        }

        // Stop existing updates before requesting new ones
        stopLocationUpdates();

        LocationRequest request = new LocationRequest.Builder(priority, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build();

        try
        {
            theLocationClient.requestLocationUpdates(request, theLocationCallback,
                    Looper.getMainLooper());
            Log.i(TAG, "Location updates: interval=" + intervalMs + "ms"
                    + " mode=" + (useFine ? "FINE" : "COARSE")
                    + (forceCoarse ? " (geofence auto-switch)" : ""));
        }
        catch (SecurityException e)
        {
            Log.e(TAG, "Missing location permission: " + e.getMessage());
            debugLogString("lastError", "Permission: " + e.getMessage());
            debugIncrement("errors");
        }
    }

    private void stopLocationUpdates()
    {
        if (theLocationClient != null && theLocationCallback != null)
        {
            theLocationClient.removeLocationUpdates(theLocationCallback);
        }
    }

    private void onNewLocation(Location loc)
    {
        Log.d(TAG, "Location: " + loc.getLatitude() + ", " + loc.getLongitude());

        debugIncrement("locationUpdates");
        debugLog("lastLocationTime", System.currentTimeMillis());
        debugLogString("lastLocationCoords",
                String.format(Locale.US, "%.6f, %.6f", loc.getLatitude(), loc.getLongitude()));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean userWantsFine = prefs.getBoolean("fine_location", true);

        if (userWantsFine)
        {
            checkSmartMode(loc);
        }

        try
        {
            JSONObject payload = new JSONObject();
            payload.put("uid", theConfig.getUserId());
            payload.put("lat", loc.getLatitude());
            payload.put("lon", loc.getLongitude());
            payload.put("ts", theDateFormat.format(new Date(loc.getTime())));

            if (loc.hasAltitude())
            {
                payload.put("alt", loc.getAltitude());
            }

            if (loc.hasSpeed())
            {
                payload.put("spd", loc.getSpeed());
            }

            if (loc.hasBearing())
            {
                payload.put("brg", loc.getBearing());
            }

            if (loc.hasAccuracy())
            {
                payload.put("acc", loc.getAccuracy());
            }

            if (prefs.getBoolean("send_battery", true))
            {
                BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
                int batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (batteryPct >= 0)
                {
                    payload.put("bat", batteryPct);
                }
            }

            new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    theSender.sendLocation(payload);
                    debugIncrement("packetsSent");
                    debugLog("lastPacketTime", System.currentTimeMillis());
                }
            }).start();
        }
        catch (Exception e)
        {
            Log.e(TAG, "Error building payload: " + e.getMessage());
            debugLogString("lastError", "Payload: " + e.getMessage());
            debugIncrement("errors");
        }
    }

    /**
     * Check if we're inside a geofence and switch to coarse mode
     * after 30 minutes. Switch back to fine when we leave.
     */
    private void checkSmartMode(Location loc)
    {
        SharedPreferences syncPrefs = getSharedPreferences("family_tracks_sync", MODE_PRIVATE);
        String geofencesJson = syncPrefs.getString("geofences", "[]");

        try
        {
            JSONArray fences = new JSONArray(geofencesJson);
            String insideName = null;

            for (int i = 0; i < fences.length(); i++)
            {
                JSONObject f = fences.getJSONObject(i);
                double fLat = f.getDouble("latitude");
                double fLon = f.getDouble("longitude");
                double fRadius = f.getDouble("radiusMeters");

                float[] results = new float[1];
                Location.distanceBetween(loc.getLatitude(), loc.getLongitude(),
                        fLat, fLon, results);

                if (results[0] <= fRadius)
                {
                    insideName = f.getString("name");
                    break;
                }
            }

            if (insideName != null)
            {
                // Inside a geofence
                if (theCurrentGeofenceName == null
                        || !theCurrentGeofenceName.equals(insideName))
                {
                    // Just entered a new geofence
                    theCurrentGeofenceName = insideName;
                    theGeofenceEnteredAt = System.currentTimeMillis();
                    Log.i(TAG, "Entered geofence: " + insideName);
                }

                long insideMs = System.currentTimeMillis() - theGeofenceEnteredAt;

                if (!theCoarseMode && insideMs >= COARSE_SWITCH_MS)
                {
                    // Switch to coarse
                    theCoarseMode = true;
                    startLocationUpdates(true);
                    Log.i(TAG, "Switching to coarse mode — in " + insideName
                            + " for " + (insideMs / 60000) + " min");
                }

                writeTrackingState(true, theCoarseMode, theCurrentGeofenceName,
                        theGeofenceEnteredAt);
            }
            else
            {
                // Outside all geofences
                if (theCurrentGeofenceName != null)
                {
                    Log.i(TAG, "Left geofence: " + theCurrentGeofenceName);
                    theCurrentGeofenceName = null;
                    theGeofenceEnteredAt = 0;

                    if (theCoarseMode)
                    {
                        theCoarseMode = false;
                        startLocationUpdates(false);
                        Log.i(TAG, "Switching back to fine mode");
                    }
                }

                writeTrackingState(true, false, null, 0);
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "Error checking geofences: " + e.getMessage());
        }
    }

    /**
     * Write tracking state to SharedPreferences so StatusFragment can display it.
     */
    private void writeTrackingState(boolean tracking, boolean coarseMode,
                                     String geofenceName, long enteredAt)
    {
        SharedPreferences prefs = getSharedPreferences(PREFS_TRACKING, MODE_PRIVATE);
        prefs.edit()
                .putBoolean("tracking", tracking)
                .putBoolean("coarseMode", coarseMode)
                .putString("geofenceName", geofenceName)
                .putLong("geofenceEnteredAt", enteredAt)
                .apply();
    }

    /**
     * Sync geofence data from the server.
     */
    private void syncWithServer()
    {
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    String baseUrl = theConfig.getWebBaseUrl();

                    URL authUrl = new URL(baseUrl + "/api/auth/token");
                    HttpURLConnection authConn = (HttpURLConnection) authUrl.openConnection();
                    authConn.setRequestMethod("POST");
                    authConn.setRequestProperty("Content-Type", "application/json");
                    authConn.setDoOutput(true);

                    JSONObject authBody = new JSONObject();
                    authBody.put("user_id", theConfig.getUserId());
                    authBody.put("key", android.util.Base64.encodeToString(
                            theConfig.getAesKey(), android.util.Base64.NO_WRAP));

                    OutputStream out = authConn.getOutputStream();
                    out.write(authBody.toString().getBytes(StandardCharsets.UTF_8));
                    out.close();

                    int authCode = authConn.getResponseCode();
                    if (authCode != 200)
                    {
                        Log.w(TAG, "Sync auth failed: HTTP " + authCode);
                        debugLogString("lastSyncResult", "Auth failed: HTTP " + authCode);
                        authConn.disconnect();
                        return;
                    }

                    String sessionCookie = authConn.getHeaderField("Set-Cookie");
                    authConn.disconnect();

                    URL fenceUrl = new URL(baseUrl + "/api/geofences/all");
                    HttpURLConnection fenceConn = (HttpURLConnection) fenceUrl.openConnection();
                    if (sessionCookie != null)
                    {
                        fenceConn.setRequestProperty("Cookie", sessionCookie);
                    }

                    int fenceCode = fenceConn.getResponseCode();
                    if (fenceCode == 200)
                    {
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(fenceConn.getInputStream()));
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null)
                        {
                            sb.append(line);
                        }
                        reader.close();

                        SharedPreferences prefs = getSharedPreferences(
                                "family_tracks_sync", MODE_PRIVATE);
                        prefs.edit()
                                .putString("geofences", sb.toString())
                                .putLong("lastSync", System.currentTimeMillis())
                                .apply();

                        JSONArray fences = new JSONArray(sb.toString());
                        Log.i(TAG, "Synced " + fences.length() + " geofences from server");
                        debugLog("lastSyncTime", System.currentTimeMillis());
                        debugLogString("lastSyncResult", "OK — " + fences.length() + " geofences");
                        debugIncrement("syncCount");
                    }
                    else
                    {
                        Log.w(TAG, "Geofence fetch failed: HTTP " + fenceCode);
                        debugLogString("lastSyncResult", "Failed: HTTP " + fenceCode);
                    }

                    fenceConn.disconnect();
                }
                catch (Exception e)
                {
                    Log.w(TAG, "Sync failed: " + e.getMessage());
                    debugLogString("lastSyncResult", "Error: " + e.getMessage());
                }
            }
        }).start();
    }

    private void debugLog(String key, long value)
    {
        SharedPreferences prefs = getSharedPreferences(PREFS_DEBUG, MODE_PRIVATE);
        prefs.edit().putLong(key, value).apply();
    }

    private void debugLogString(String key, String value)
    {
        SharedPreferences prefs = getSharedPreferences(PREFS_DEBUG, MODE_PRIVATE);
        prefs.edit().putString(key, value).apply();
    }

    private void debugIncrement(String key)
    {
        SharedPreferences prefs = getSharedPreferences(PREFS_DEBUG, MODE_PRIVATE);
        long val = prefs.getLong(key, 0);
        prefs.edit().putLong(key, val + 1).apply();
    }

    private void createNotificationChannel()
    {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Shows when Family Tracks is sharing your location");

        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private Notification buildNotification()
    {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingOpen = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, LocationService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pendingStop = PendingIntent.getService(this, 0, stopIntent,
                PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Family Tracks")
                .setContentText("Sharing your location")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingOpen)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", pendingStop)
                .setOngoing(true)
                .build();
    }
}
