package com.familytracks.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * Status tab showing connection info with buttons to scan QR
 * and start/stop location tracking.
 */
public class StatusFragment extends Fragment
{
    private ServerConfig theConfig;
    private boolean theTracking;

    private TextView theStatusText;
    private TextView theServerText;
    private Button theScanButton;
    private Button theTrackButton;

    private ActivityResultLauncher<Intent> theQrLauncher;
    private ActivityResultLauncher<String[]> theLocationPermLauncher;
    private ActivityResultLauncher<String[]> theBgLocationPermLauncher;
    private ActivityResultLauncher<String> theNotifPermLauncher;

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
                        // Foreground location granted, now ask for background
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
                grants ->
                {
                    // Whether they granted background or not, proceed with tracking.
                    // Foreground location is enough, background is a bonus.
                    requestBatteryOptExemption();
                }
        );

        theNotifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> { }
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
        theScanButton = view.findViewById(R.id.scanButton);
        theTrackButton = view.findViewById(R.id.trackButton);

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
            theTrackButton.setText("Start Tracking");
        }
    }

    private void startTracking()
    {
        // Step 1: Check foreground location permission
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

        // Already have foreground location, check background
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            requestBackgroundLocation();
            return;
        }

        // Have all permissions, check battery optimization
        if (!isBatteryOptExempt())
        {
            requestBatteryOptExemption();
            return;
        }

        // Everything is set, start the service
        launchService();
    }

    private void requestBackgroundLocation()
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
            {
                // Already have it
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
                        // Service will start on next button press after they return
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
        // Request notification permission on Android 13+
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
}
