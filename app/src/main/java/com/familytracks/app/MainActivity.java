package com.familytracks.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Main screen showing connection status with buttons to
 * scan QR, start/stop tracking, and open settings.
 */
public class MainActivity extends AppCompatActivity
{
    private static final int REQUEST_LOCATION = 200;

    private ServerConfig theConfig;
    private boolean theTracking;

    private TextView theStatusText;
    private TextView theServerText;
    private Button theScanButton;
    private Button theTrackButton;
    private Button theSettingsButton;

    private ActivityResultLauncher<Intent> theQrLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        theConfig = new ServerConfig();
        theTracking = false;

        theStatusText = findViewById(R.id.statusText);
        theServerText = findViewById(R.id.serverText);
        theScanButton = findViewById(R.id.scanButton);
        theTrackButton = findViewById(R.id.trackButton);
        theSettingsButton = findViewById(R.id.settingsButton);

        theQrLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result ->
                {
                    // Reload config after QR scan
                    refreshStatus();
                }
        );

        theScanButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                Intent intent = new Intent(MainActivity.this, QrScanActivity.class);
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

        theSettingsButton.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus()
    {
        boolean configured = theConfig.load(this);

        if (configured)
        {
            theServerText.setText("Server: " + theConfig.getHost()
                    + ":" + theConfig.getPort());
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
        // Check location permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
        {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    }, REQUEST_LOCATION);
            return;
        }

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED)
            {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 300);
            }
        }

        Intent intent = new Intent(this, LocationService.class);
        intent.setAction(LocationService.ACTION_START);
        ContextCompat.startForegroundService(this, intent);

        theTracking = true;
        refreshStatus();
    }

    private void stopTracking()
    {
        Intent intent = new Intent(this, LocationService.class);
        intent.setAction(LocationService.ACTION_STOP);
        startService(intent);

        theTracking = false;
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_LOCATION)
        {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            {
                startTracking();
            }
        }
    }
}
