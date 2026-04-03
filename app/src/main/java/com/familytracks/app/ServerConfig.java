package com.familytracks.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

/**
 * Stores server connection info parsed from the QR code.
 * Persisted in SharedPreferences so it survives app restarts.
 */
public class ServerConfig
{
    private static final String TAG = "ServerConfig";
    private static final String PREFS_NAME = "family_tracks_config";

    private String theHost;
    private int    thePort;
    private byte[] theAesKey;
    private String theUserId;

    public ServerConfig()
    {
        theHost = null;
        thePort = 5555;
        theAesKey = null;
        theUserId = null;
    }

    public String getHost()   { return theHost; }
    public int    getPort()   { return thePort; }
    public byte[] getAesKey() { return theAesKey; }
    public String getUserId() { return theUserId; }

    public boolean isConfigured()
    {
        return theHost != null && theAesKey != null && theUserId != null;
    }

    /**
     * Parse the JSON payload from a QR code.
     * Expected format:
     * {
     *     "host": "your-server.com",
     *     "port": 5555,
     *     "key": "<base64 AES-256 key>",
     *     "user_id": "<uuid>"
     * }
     */
    public boolean parseQrJson(String jsonStr)
    {
        try
        {
            JSONObject obj = new JSONObject(jsonStr);
            theHost = obj.getString("host");
            thePort = obj.getInt("port");
            theUserId = obj.getString("user_id");

            String keyB64 = obj.getString("key");
            theAesKey = Base64.decode(keyB64, Base64.DEFAULT);

            if (theAesKey.length != 32)
            {
                Log.e(TAG, "AES key is not 32 bytes, got " + theAesKey.length);
                theAesKey = null;
                return false;
            }

            Log.i(TAG, "Parsed QR: host=" + theHost + " port=" + thePort
                    + " userId=" + theUserId);
            return true;
        }
        catch (Exception e)
        {
            Log.e(TAG, "Failed to parse QR JSON: " + e.getMessage());
            return false;
        }
    }

    public void save(Context context)
    {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString("host", theHost);
        editor.putInt("port", thePort);
        editor.putString("userId", theUserId);

        if (theAesKey != null)
        {
            editor.putString("aesKey", Base64.encodeToString(theAesKey, Base64.NO_WRAP));
        }

        editor.apply();
        Log.i(TAG, "Config saved");
    }

    public boolean load(Context context)
    {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        theHost = prefs.getString("host", null);
        thePort = prefs.getInt("port", 5555);
        theUserId = prefs.getString("userId", null);

        String keyB64 = prefs.getString("aesKey", null);
        if (keyB64 != null)
        {
            theAesKey = Base64.decode(keyB64, Base64.DEFAULT);
        }

        return isConfigured();
    }

    public void clear(Context context)
    {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        theHost = null;
        theAesKey = null;
        theUserId = null;
    }
}
