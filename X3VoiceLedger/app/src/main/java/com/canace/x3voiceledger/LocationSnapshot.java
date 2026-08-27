package com.canace.x3voiceledger;

import android.location.Location;

import java.util.Locale;

final class LocationSnapshot {
    final double latitude;
    final double longitude;
    final float accuracy;
    final long time;

    LocationSnapshot(Location location) {
        latitude = location.getLatitude();
        longitude = location.getLongitude();
        accuracy = location.hasAccuracy() ? location.getAccuracy() : 0f;
        time = location.getTime();
    }

    String coordinateLabel() {
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
    }
}
