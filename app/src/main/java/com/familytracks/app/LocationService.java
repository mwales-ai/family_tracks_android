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
 * data from the server once per hour.
 */
public class LocationService extends Service
{
    private static final String TAG = "LocationService";
    private static final String CHANNEL_ID = "family_tracks_location";
    private static final int NOTIFICATION_ID = 1;
    private static final long SYNC_INTERVAL_MS = 60 * 60 * 1000;  // 1 hour

    public static final String ACTION_START = "com.familytracks.app.START";
    public static final String ACTION_STOP = "com.familytracks.app.STOP";

    private FusedLocationProviderClient theLocationClient;
    private LocationCallback theLocationCallback;
    private PacketSender theSender;
    private ServerConfig theConfig;
    private SimpleDateFormat theDateFormat;
    private Handler theSyncHandler;
    private Runnable theSyncRunnable;

    @Override
    public void onCreate()
    {
        super.onCreate();

        theConfig = new ServerConfig();
        theConfig.load(this);
        theSender = new PacketSender(theConfig);
        theDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);

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

        // Periodic sync handler
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
            stopSelf();
            return START_NOT_STICKY;
        }

        Log.i(TAG, "Starting location service");
        startForeground(NOTIFICATION_ID, buildNotification());
        startLocationUpdates();

        // Start periodic sync — first sync immediately, then every hour
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
        super.onDestroy();
    }

    private void startLocationUpdates()
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String intervalStr = prefs.getString("reporting_interval", "60");
        long intervalMs = Long.parseLong(intervalStr) * 1000;

        boolean fineLoc = prefs.getBoolean("fine_location", true);
        int priority = fineLoc ? Priority.PRIORITY_HIGH_ACCURACY : Priority.PRIORITY_BALANCED_POWER_ACCURACY;

        LocationRequest request = new LocationRequest.Builder(priority, intervalMs)
                .setMinUpdateIntervalMillis(intervalMs / 2)
                .build();

        try
        {
            theLocationClient.requestLocationUpdates(request, theLocationCallback,
                    Looper.getMainLooper());
            Log.i(TAG, "Location updates started, interval=" + intervalMs + "ms"
                    + " priority=" + (fineLoc ? "HIGH" : "BALANCED"));
        }
        catch (SecurityException e)
        {
            Log.e(TAG, "Missing location permission: " + e.getMessage());
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

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

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
                }
            }).start();
        }
        catch (Exception e)
        {
            Log.e(TAG, "Error building payload: " + e.getMessage());
        }
    }

    /**
     * Sync geofence data from the server. Authenticates with the token
     * endpoint then fetches all geofences. Stores them in SharedPreferences
     * as JSON so they survive offline periods.
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

                    // Authenticate
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
                        authConn.disconnect();
                        return;
                    }

                    // Get session cookie
                    String sessionCookie = authConn.getHeaderField("Set-Cookie");
                    authConn.disconnect();

                    // Fetch all geofences
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

                        // Store locally
                        SharedPreferences prefs = getSharedPreferences(
                                "family_tracks_sync", MODE_PRIVATE);
                        prefs.edit()
                                .putString("geofences", sb.toString())
                                .putLong("lastSync", System.currentTimeMillis())
                                .apply();

                        JSONArray fences = new JSONArray(sb.toString());
                        Log.i(TAG, "Synced " + fences.length() + " geofences from server");
                    }
                    else
                    {
                        Log.w(TAG, "Geofence fetch failed: HTTP " + fenceCode);
                    }

                    fenceConn.disconnect();
                }
                catch (Exception e)
                {
                    Log.w(TAG, "Sync failed: " + e.getMessage());
                }
            }
        }).start();
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
