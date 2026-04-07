package com.familytracks.app;

import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

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

        BottomNavigationView nav = findViewById(R.id.bottomNav);
        nav.setOnItemSelectedListener(new BottomNavigationView.OnItemSelectedListener()
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
}
