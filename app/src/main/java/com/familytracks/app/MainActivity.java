package com.familytracks.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.Menu;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * Main activity with bottom tab navigation: Map, Events, Status, Settings, Debug.
 */
public class MainActivity extends AppCompatActivity
{
    private MapViewFragment theMapFragment;
    private EventsFragment theEventsFragment;
    private StatusFragment theStatusFragment;
    private SettingsFragment theSettingsFragment;
    private DebugFragment theDebugFragment;
    private Fragment theActiveFragment;
    private BottomNavigationView theNav;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        theMapFragment = new MapViewFragment();
        theEventsFragment = new EventsFragment();
        theStatusFragment = new StatusFragment();
        theSettingsFragment = new SettingsFragment();
        theDebugFragment = new DebugFragment();

        getSupportFragmentManager().beginTransaction()
                .add(R.id.fragmentContainer, theDebugFragment, "debug").hide(theDebugFragment)
                .add(R.id.fragmentContainer, theSettingsFragment, "settings").hide(theSettingsFragment)
                .add(R.id.fragmentContainer, theStatusFragment, "status").hide(theStatusFragment)
                .add(R.id.fragmentContainer, theEventsFragment, "events").hide(theEventsFragment)
                .add(R.id.fragmentContainer, theMapFragment, "map")
                .commit();

        theActiveFragment = theMapFragment;

        theNav = findViewById(R.id.bottomNav);
        updateDebugTabVisibility();

        theNav.setOnItemSelectedListener(new BottomNavigationView.OnItemSelectedListener()
        {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item)
            {
                Fragment selected = null;
                int id = item.getItemId();

                if (id == R.id.nav_map)
                {
                    selected = theMapFragment;
                }
                else if (id == R.id.nav_events)
                {
                    selected = theEventsFragment;
                }
                else if (id == R.id.nav_status)
                {
                    selected = theStatusFragment;
                }
                else if (id == R.id.nav_settings)
                {
                    selected = theSettingsFragment;
                }
                else if (id == R.id.nav_debug)
                {
                    selected = theDebugFragment;
                }

                if (selected != null && selected != theActiveFragment)
                {
                    getSupportFragmentManager().beginTransaction()
                            .hide(theActiveFragment)
                            .show(selected)
                            .commit();
                    theActiveFragment = selected;
                }

                return true;
            }
        });
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        updateDebugTabVisibility();
    }

    private void updateDebugTabVisibility()
    {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean showDebug = prefs.getBoolean("show_debug_tab", false);
        Menu menu = theNav.getMenu();
        MenuItem debugItem = menu.findItem(R.id.nav_debug);

        if (debugItem != null)
        {
            debugItem.setVisible(showDebug);
        }

        // If debug tab is hidden and it was the active fragment, switch to map
        if (!showDebug && theActiveFragment == theDebugFragment)
        {
            getSupportFragmentManager().beginTransaction()
                    .hide(theActiveFragment)
                    .show(theMapFragment)
                    .commit();
            theActiveFragment = theMapFragment;
            theNav.setSelectedItemId(R.id.nav_map);
        }
    }
}
