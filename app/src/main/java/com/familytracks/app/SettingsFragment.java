package com.familytracks.app;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Settings fragment for the bottom nav tab.
 * Handles the Disconnect button to clear server config.
 */
public class SettingsFragment extends PreferenceFragmentCompat
{
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState)
    {
        super.onViewCreated(view, savedInstanceState);
        // Set opaque background so fragments underneath don't bleed through
        android.util.TypedValue bg = new android.util.TypedValue();
        view.getContext().getTheme().resolveAttribute(android.R.attr.windowBackground, bg, true);
        view.setBackgroundResource(bg.resourceId);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey)
    {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        Preference disconnectPref = findPreference("disconnect");
        if (disconnectPref != null)
        {
            disconnectPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener()
            {
                @Override
                public boolean onPreferenceClick(Preference preference)
                {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("Disconnect Server")
                            .setMessage("This will remove the server configuration and stop "
                                    + "tracking. You will need to scan a new QR code to reconnect.")
                            .setPositiveButton("Disconnect", new DialogInterface.OnClickListener()
                            {
                                @Override
                                public void onClick(DialogInterface dialog, int which)
                                {
                                    // Stop tracking
                                    SharedPreferences trackPrefs = requireContext().getSharedPreferences(
                                            LocationService.PREFS_TRACKING, Context.MODE_PRIVATE);
                                    boolean tracking = trackPrefs.getBoolean("tracking", false);

                                    if (tracking)
                                    {
                                        Intent intent = new Intent(requireContext(), LocationService.class);
                                        intent.setAction(LocationService.ACTION_STOP);
                                        requireContext().startService(intent);
                                    }

                                    // Clear server config
                                    ServerConfig config = new ServerConfig();
                                    config.clear(requireContext());

                                    // Clear tracking state
                                    trackPrefs.edit().clear().apply();

                                    // Update the summary
                                    updateServerInfo();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return true;
                }
            });
        }

        updateServerInfo();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        updateServerInfo();
    }

    private void updateServerInfo()
    {
        Preference serverInfoPref = findPreference("server_info");
        if (serverInfoPref != null)
        {
            ServerConfig config = new ServerConfig();
            if (config.load(requireContext()))
            {
                serverInfoPref.setSummary(config.getScheme() + "://" + config.getHost()
                        + " (UDP:" + config.getPort() + ")");
            }
            else
            {
                serverInfoPref.setSummary("Not connected");
            }
        }

        Preference disconnectPref = findPreference("disconnect");
        if (disconnectPref != null)
        {
            ServerConfig config = new ServerConfig();
            disconnectPref.setEnabled(config.load(requireContext()));
        }
    }
}
