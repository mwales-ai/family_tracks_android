package com.familytracks.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Status tab showing connection info with buttons to scan QR,
 * start/stop location tracking, and change avatar.
 */
public class StatusFragment extends Fragment
{
    private static final String TAG = "StatusFragment";

    private ServerConfig theConfig;
    private boolean theTracking;

    private TextView theStatusText;
    private TextView theServerText;
    private ImageView theAvatarImage;
    private Button theScanButton;
    private Button theTrackButton;
    private Button theAvatarButton;
    private Button theDisconnectButton;

    private ActivityResultLauncher<Intent> theQrLauncher;
    private ActivityResultLauncher<String[]> theLocationPermLauncher;
    private ActivityResultLauncher<String[]> theBgLocationPermLauncher;
    private ActivityResultLauncher<String> theNotifPermLauncher;
    private ActivityResultLauncher<String> theImagePickerLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        theConfig = new ServerConfig();
        theTracking = false;

        theQrLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> refreshStatus()
        );

        theLocationPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                grants ->
                {
                    Boolean fine = grants.get(Manifest.permission.ACCESS_FINE_LOCATION);
                    if (fine != null && fine)
                    {
                        requestBackgroundLocation();
                    }
                    else
                    {
                        theStatusText.setText("Location permission denied. Tracking requires location access.");
                    }
                }
        );

        theBgLocationPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                grants -> requestBatteryOptExemption()
        );

        theNotifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { }
        );

        theImagePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri ->
                {
                    if (uri != null)
                    {
                        uploadAvatar(uri);
                    }
                }
        );
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState)
    {
        View view = inflater.inflate(R.layout.fragment_status, container, false);

        theStatusText = view.findViewById(R.id.statusText);
        theServerText = view.findViewById(R.id.serverText);
        theAvatarImage = view.findViewById(R.id.avatarImage);
        theScanButton = view.findViewById(R.id.scanButton);
        theTrackButton = view.findViewById(R.id.trackButton);
        theAvatarButton = view.findViewById(R.id.avatarButton);
        theDisconnectButton = view.findViewById(R.id.disconnectButton);

        theScanButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                Intent intent = new Intent(requireContext(), QrScanActivity.class);
                theQrLauncher.launch(intent);
            }
        });

        theTrackButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                if (theTracking)
                {
                    stopTracking();
                }
                else
                {
                    startTracking();
                }
            }
        });

        theAvatarButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                theImagePickerLauncher.launch("image/*");
            }
        });

        theDisconnectButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                new AlertDialog.Builder(requireContext())
                        .setTitle("Disconnect Server")
                        .setMessage("This will remove the server configuration and stop tracking. "
                                + "You will need to scan a new QR code to reconnect.")
                        .setPositiveButton("Disconnect", new DialogInterface.OnClickListener()
                        {
                            @Override
                            public void onClick(DialogInterface dialog, int which)
                            {
                                if (theTracking)
                                {
                                    stopTracking();
                                }
                                theConfig.clear(requireContext());
                                theAvatarImage.setImageResource(android.R.drawable.ic_menu_gallery);
                                refreshStatus();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });

        // Make avatar image clickable too
        theAvatarImage.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                theImagePickerLauncher.launch("image/*");
            }
        });

        // Round the avatar image
        theAvatarImage.setClipToOutline(true);
        theAvatarImage.setOutlineProvider(new android.view.ViewOutlineProvider()
        {
            @Override
            public void getOutline(View view, android.graphics.Outline outline)
            {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });

        return view;
    }

    @Override
    public void onResume()
    {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus()
    {
        boolean configured = theConfig.load(requireContext());

        if (configured)
        {
            theServerText.setText("Server: " + theConfig.getHost()
                    + " (UDP:" + theConfig.getPort()
                    + " Web:" + theConfig.getWebPort() + ")");
            theTrackButton.setEnabled(true);
            theAvatarButton.setEnabled(true);
            theDisconnectButton.setVisibility(View.VISIBLE);

            if (theTracking)
            {
                theStatusText.setText("Tracking active");
                theTrackButton.setText("Stop Tracking");
            }
            else
            {
                theStatusText.setText("Ready");
                theTrackButton.setText("Start Tracking");
            }
        }
        else
        {
            theServerText.setText("Not connected to a server");
            theStatusText.setText("Scan a QR code to connect");
            theTrackButton.setEnabled(false);
            theAvatarButton.setEnabled(false);
            theDisconnectButton.setVisibility(View.GONE);
            theTrackButton.setText("Start Tracking");
        }
    }

    private void startTracking()
    {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            new AlertDialog.Builder(requireContext())
                    .setTitle("Location Permission Needed")
                    .setMessage("Family Tracks needs location access to share your position "
                            + "with your family. Your location is sent encrypted directly to "
                            + "your private server — no third parties involved.")
                    .setPositiveButton("Grant", new DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialog, int which)
                        {
                            theLocationPermLauncher.launch(new String[]{
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                            });
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            requestBackgroundLocation();
            return;
        }

        if (!isBatteryOptExempt())
        {
            requestBatteryOptExemption();
            return;
        }

        launchService();
    }

    private void requestBackgroundLocation()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
            {
                requestBatteryOptExemption();
                return;
            }

            new AlertDialog.Builder(requireContext())
                    .setTitle("Background Location")
                    .setMessage("To keep tracking when the app is in the background or the "
                            + "screen is off, please select \"Allow all the time\" on the "
                            + "next screen.")
                    .setPositiveButton("Continue", new DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialog, int which)
                        {
                            theBgLocationPermLauncher.launch(new String[]{
                                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            });
                        }
                    })
                    .setNegativeButton("Skip", new DialogInterface.OnClickListener()
                    {
                        @Override
                        public void onClick(DialogInterface dialog, int which)
                        {
                            requestBatteryOptExemption();
                        }
                    })
                    .show();
        }
        else
        {
            requestBatteryOptExemption();
        }
    }

    private boolean isBatteryOptExempt()
    {
        PowerManager pm = (PowerManager) requireContext().getSystemService(Context.POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(requireContext().getPackageName());
    }

    private void requestBatteryOptExemption()
    {
        if (isBatteryOptExempt())
        {
            launchService();
            return;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Battery Optimization")
                .setMessage("Android may stop location tracking to save battery. "
                        + "To prevent this, please disable battery optimization "
                        + "for Family Tracks on the next screen.")
                .setPositiveButton("Open Settings", new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + requireContext().getPackageName()));
                        startActivity(intent);
                        launchService();
                    }
                })
                .setNegativeButton("Skip", new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        launchService();
                    }
                })
                .show();
    }

    private void launchService()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            {
                theNotifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        Intent intent = new Intent(requireContext(), LocationService.class);
        intent.setAction(LocationService.ACTION_START);
        ContextCompat.startForegroundService(requireContext(), intent);

        theTracking = true;
        refreshStatus();
    }

    private void stopTracking()
    {
        Intent intent = new Intent(requireContext(), LocationService.class);
        intent.setAction(LocationService.ACTION_STOP);
        requireContext().startService(intent);

        theTracking = false;
        refreshStatus();
    }

    /**
     * Upload a selected image to the server as the user's avatar.
     * Authenticates with the token endpoint, then POSTs the image
     * as a multipart form upload to /settings/avatar.
     */
    private void uploadAvatar(Uri imageUri)
    {
        theStatusText.setText("Uploading avatar...");

        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    // Read image bytes from the content URI
                    InputStream is = requireContext().getContentResolver().openInputStream(imageUri);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1)
                    {
                        baos.write(buf, 0, n);
                    }
                    is.close();
                    byte[] imageBytes = baos.toByteArray();

                    // Figure out the filename
                    String filename = "avatar.jpg";
                    Cursor cursor = requireContext().getContentResolver().query(
                            imageUri, null, null, null, null);
                    if (cursor != null && cursor.moveToFirst())
                    {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx >= 0)
                        {
                            filename = cursor.getString(idx);
                        }
                        cursor.close();
                    }

                    String baseUrl = theConfig.getWebBaseUrl();
                    String boundary = "----FamilyTracks" + System.currentTimeMillis();

                    // Step 1: Authenticate
                    URL authUrl = new URL(baseUrl + "/api/auth/token");
                    HttpURLConnection authConn = (HttpURLConnection) authUrl.openConnection();
                    authConn.setRequestMethod("POST");
                    authConn.setRequestProperty("Content-Type", "application/json");
                    authConn.setDoOutput(true);

                    String authJson = "{\"user_id\":\"" + theConfig.getUserId()
                            + "\",\"key\":\"" + Base64.encodeToString(
                            theConfig.getAesKey(), Base64.NO_WRAP) + "\"}";
                    authConn.getOutputStream().write(authJson.getBytes());

                    int authCode = authConn.getResponseCode();
                    if (authCode != 200)
                    {
                        showToast("Auth failed");
                        authConn.disconnect();
                        return;
                    }

                    String sessionCookie = authConn.getHeaderField("Set-Cookie");
                    authConn.disconnect();

                    // Step 2: Upload avatar as multipart/form-data
                    URL uploadUrl = new URL(baseUrl + "/settings/avatar");
                    HttpURLConnection conn = (HttpURLConnection) uploadUrl.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type",
                            "multipart/form-data; boundary=" + boundary);
                    conn.setRequestProperty("Cookie", sessionCookie);
                    conn.setDoOutput(true);
                    conn.setInstanceFollowRedirects(false);

                    DataOutputStream dos = new DataOutputStream(conn.getOutputStream());

                    // Write the file part
                    dos.writeBytes("--" + boundary + "\r\n");
                    dos.writeBytes("Content-Disposition: form-data; name=\"avatar\"; filename=\""
                            + filename + "\"\r\n");
                    dos.writeBytes("Content-Type: image/jpeg\r\n\r\n");
                    dos.write(imageBytes);
                    dos.writeBytes("\r\n");
                    dos.writeBytes("--" + boundary + "--\r\n");
                    dos.flush();
                    dos.close();

                    int code = conn.getResponseCode();
                    conn.disconnect();

                    if (code == 200 || code == 302)
                    {
                        // Show the selected image as preview
                        if (isAdded())
                        {
                            Bitmap bmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
                            requireActivity().runOnUiThread(new Runnable()
                            {
                                @Override
                                public void run()
                                {
                                    theAvatarImage.setImageBitmap(bmp);
                                    theStatusText.setText("Avatar updated!");
                                }
                            });
                        }
                        showToast("Avatar uploaded");
                    }
                    else
                    {
                        Log.e(TAG, "Avatar upload failed: HTTP " + code);
                        showToast("Upload failed (HTTP " + code + ")");
                    }
                }
                catch (Exception e)
                {
                    Log.e(TAG, "Avatar upload error: " + e.getMessage());
                    showToast("Upload failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private void showToast(String msg)
    {
        if (!isAdded()) return;
        requireActivity().runOnUiThread(new Runnable()
        {
            @Override
            public void run()
            {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show();
                refreshStatus();
            }
        });
    }
}
