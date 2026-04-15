package com.familytracks.app;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Shows geofence entry/exit events from the server.
 */
public class EventsFragment extends Fragment
{
    private static final String TAG = "EventsFragment";

    private SwipeRefreshLayout theSwipeRefresh;
    private TextView theEventsText;
    private ServerConfig theConfig;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
        View view = inflater.inflate(R.layout.fragment_events, container, false);

        theSwipeRefresh = view.findViewById(R.id.swipeRefresh);
        theEventsText = view.findViewById(R.id.eventsText);
        theConfig = new ServerConfig();

        theSwipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener()
        {
            @Override
            public void onRefresh()
            {
                loadEvents();
            }
        });

        return view;
    }

    @Override
    public void onResume()
    {
        super.onResume();
        loadEvents();
    }

    private void loadEvents()
    {
        if (!theConfig.load(requireContext()))
        {
            theEventsText.setText("Not connected to a server.");
            theSwipeRefresh.setRefreshing(false);
            return;
        }

        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    // Only fetch events from the last 48 hours
                    long cutoffMs = System.currentTimeMillis() - (48 * 60 * 60 * 1000);
                    SimpleDateFormat isoFmt = new SimpleDateFormat(
                            "yyyy-MM-dd'T'HH:mm:ss", Locale.US);
                    isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
                    String since = isoFmt.format(new Date(cutoffMs));

                    String urlStr = theConfig.getWebBaseUrl()
                            + "/api/geofence-events?limit=50&since="
                            + URLEncoder.encode(since, "UTF-8");
                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                    // Use cookies from the WebView auth
                    String cookies = android.webkit.CookieManager.getInstance()
                            .getCookie(theConfig.getWebBaseUrl());
                    if (cookies != null)
                    {
                        conn.setRequestProperty("Cookie", cookies);
                    }

                    int code = conn.getResponseCode();
                    if (code != 200)
                    {
                        showText("Could not load events (HTTP " + code + ").\nTry opening the Map tab first to authenticate.");
                        return;
                    }

                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null)
                    {
                        sb.append(line);
                    }
                    reader.close();
                    conn.disconnect();

                    JSONArray events = new JSONArray(sb.toString());

                    if (events.length() == 0)
                    {
                        showText("No geofence events yet.\n\nEvents will appear here when family members enter or leave geofenced locations.");
                        return;
                    }

                    StringBuilder display = new StringBuilder();
                    String lastDate = "";

                    for (int i = 0; i < events.length(); i++)
                    {
                        JSONObject evt = events.getJSONObject(i);
                        String ts = evt.getString("timestamp");
                        String msg = evt.getString("message");

                        // Parse date for grouping
                        String dateStr = ts.length() >= 10 ? ts.substring(0, 10) : ts;
                        String timeStr = ts.length() >= 16 ? ts.substring(11, 16) : "";

                        if (!dateStr.equals(lastDate))
                        {
                            if (display.length() > 0)
                            {
                                display.append("\n");
                            }
                            display.append("--- ").append(dateStr).append(" ---\n");
                            lastDate = dateStr;
                        }

                        String icon = evt.getString("eventType").equals("enter") ? "\u25B6" : "\u25C0";
                        display.append(timeStr).append("  ").append(icon).append("  ").append(msg).append("\n");
                    }

                    showText(display.toString());
                }
                catch (Exception e)
                {
                    Log.e(TAG, "Error loading events: " + e.getMessage());
                    showText("Error loading events: " + e.getMessage());
                }
            }
        }).start();
    }

    private void showText(String text)
    {
        if (!isAdded())
        {
            return;
        }

        requireActivity().runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                theEventsText.setText(text);
                theSwipeRefresh.setRefreshing(false);
            }
        });
    }
}
