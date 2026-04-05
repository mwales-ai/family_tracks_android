package com.familytracks.app;

import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Displays the family map. Currently uses a WebView pointing at the
 * server dashboard. Designed to be swapped to a native MapView later
 * without changing the rest of the app.
 */
public class MapViewFragment extends Fragment
{
    private static final String TAG = "MapViewFragment";

    private WebView theWebView;
    private ServerConfig theConfig;
    private boolean theAuthenticated;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
        View view = inflater.inflate(R.layout.fragment_map, container, false);

        theWebView = view.findViewById(R.id.mapWebView);
        theConfig = new ServerConfig();
        theAuthenticated = false;

        setupWebView();

        return view;
    }

    private void setupWebView()
    {
        WebSettings settings = theWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(theWebView, true);

        // Intercept redirects to login page — means session expired
        theWebView.setWebViewClient(new WebViewClient()
        {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request)
            {
                String url = request.getUrl().toString();
                if (url.contains("/login"))
                {
                    // Session expired, re-authenticate
                    theAuthenticated = false;
                    authenticate();
                    return true;
                }
                return false;
            }
        });

        theWebView.setWebChromeClient(new WebChromeClient());
    }

    private void loadMap()
    {
        if (!theConfig.load(requireContext()))
        {
            theWebView.loadData(
                    "<html><body style='display:flex;align-items:center;justify-content:center;"
                    + "height:100%;font-family:sans-serif;color:#666;'>"
                    + "<p>Not connected. Scan a QR code from the Status tab.</p>"
                    + "</body></html>",
                    "text/html", "utf-8"
            );
            return;
        }

        if (theAuthenticated)
        {
            String url = theConfig.getWebBaseUrl() + "/dashboard?mobile=1";
            theWebView.loadUrl(url);
        }
        else
        {
            authenticate();
        }
    }

    /**
     * Authenticate with the server using QR code credentials,
     * then load the dashboard once the session cookie is set.
     */
    private void authenticate()
    {
        if (!theConfig.load(requireContext()))
        {
            return;
        }

        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    String baseUrl = theConfig.getWebBaseUrl();
                    URL url = new URL(baseUrl + "/api/auth/token");

                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setDoOutput(true);

                    JSONObject body = new JSONObject();
                    body.put("user_id", theConfig.getUserId());
                    body.put("key", Base64.encodeToString(theConfig.getAesKey(), Base64.NO_WRAP));

                    OutputStream out = conn.getOutputStream();
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                    out.close();

                    int code = conn.getResponseCode();

                    if (code == 200)
                    {
                        // Extract the session cookie and set it in the WebView
                        Map<String, List<String>> headers = conn.getHeaderFields();
                        List<String> cookies = headers.get("Set-Cookie");
                        if (cookies != null)
                        {
                            CookieManager cm = CookieManager.getInstance();
                            for (String cookie : cookies)
                            {
                                cm.setCookie(baseUrl, cookie);
                            }
                            cm.flush();
                        }

                        theAuthenticated = true;
                        Log.i(TAG, "Authenticated with server");
                    }
                    else
                    {
                        Log.e(TAG, "Auth failed: HTTP " + code);
                    }

                    conn.disconnect();

                    // Load dashboard on the UI thread
                    if (theWebView != null && isAdded())
                    {
                        requireActivity().runOnUiThread(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                if (theAuthenticated)
                                {
                                    theWebView.loadUrl(baseUrl + "/dashboard?mobile=1");
                                }
                                else
                                {
                                    theWebView.loadData(
                                            "<html><body style='display:flex;align-items:center;"
                                            + "justify-content:center;height:100%;"
                                            + "font-family:sans-serif;color:#c00;'>"
                                            + "<p>Could not authenticate with server.</p>"
                                            + "</body></html>",
                                            "text/html", "utf-8"
                                    );
                                }
                            }
                        });
                    }
                }
                catch (Exception e)
                {
                    Log.e(TAG, "Auth error: " + e.getMessage());
                }
            }
        }).start();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        loadMap();
    }
}
