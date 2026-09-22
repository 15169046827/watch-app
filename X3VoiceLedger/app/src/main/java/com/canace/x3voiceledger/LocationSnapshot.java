package com.canace.x3voiceledger;

import android.location.Location;
import java.util.Locale;

final class LocationSnapshot {
    final double latitude;
    final double longitude;
    final float accuracy;
    final long time;

    LocationSnapshot(Location location) {
        this(
                location.getLatitude(),
                location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : 0f,
                location.getTime());
    }

    LocationSnapshot(double latitude, double longitude, float accuracy, long time) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.accuracy = accuracy;
        this.time = time;
    }

    String coordinateLabel() {
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
    }
}
