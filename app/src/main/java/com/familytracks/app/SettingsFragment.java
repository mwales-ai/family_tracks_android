package com.familytracks.app;

import android.os.Bundle;

import androidx.preference.PreferenceFragmentCompat;

/**
 * Settings fragment for the bottom nav tab.
 * Reuses the same preferences XML as the standalone SettingsActivity.
 */
public class SettingsFragment extends PreferenceFragmentCompat
{
    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey)
    {
        setPreferencesFromResource(R.xml.preferences, rootKey);
    }
}
