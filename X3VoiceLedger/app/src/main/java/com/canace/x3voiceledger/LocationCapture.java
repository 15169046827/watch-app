package com.canace.x3voiceledger;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.List;

final class LocationCapture {
    interface Callback {
        void onLocation(LocationSnapshot snapshot);

        void onUnavailable(String reason);
    }

    private final Context context;
    private final LocationManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LocationListener listener;
    private Location best;
    private boolean receivedFreshLocation;

    LocationCapture(Context context) {
        this.context = context;
        manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    boolean hasPermission() {
        return context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    boolean start(Callback callback) {
        stop();
        if (!hasPermission() || manager == null) {
            return false;
        }
        receivedFreshLocation = false;

        try {
            List<String> providers = manager.getProviders(true);
            for (String provider : providers) {
                Location known = manager.getLastKnownLocation(provider);
                if (isBetter(known, best)) {
                    best = known;
                }
            }
            if (best != null) {
                callback.onLocation(new LocationSnapshot(best));
            }

            listener =
                    new LocationListener() {
                        @Override
                        public void onLocationChanged(Location location) {
                            receivedFreshLocation = true;
                            if (isBetter(location, best)) {
                                best = location;
                                callback.onLocation(new LocationSnapshot(location));
                            }
                            if (location.hasAccuracy() && location.getAccuracy() <= 80f) {
                                stop();
                            }
                        }

                        @Override
                        public void onStatusChanged(String provider, int status, Bundle extras) { }

                        @Override
                        public void onProviderEnabled(String provider) { }

                        @Override
                        public void onProviderDisabled(String provider) { }
                    };
            boolean requested = false;
            for (String provider : providers) {
                if (LocationManager.GPS_PROVIDER.equals(provider)
                        || LocationManager.NETWORK_PROVIDER.equals(provider)) {
                    manager.requestLocationUpdates(
                            provider, 1000L, 0f, listener, Looper.getMainLooper());
                    requested = true;
                }
            }
            if (!requested) {
                callback.onUnavailable("系统没有可用的定位 Provider，可手动填写地点");
                stop();
                return false;
            }
            handler.postDelayed(
                    () -> {
                        if (!receivedFreshLocation && best == null) {
                            callback.onUnavailable("未取得 GPS 定位，请到室外重试或手动填写");
                        } else if (!receivedFreshLocation) {
                            callback.onUnavailable("仅有上次定位，可更新或手动修改地点");
                        }
                        stop();
                    },
                    30_000L);
            return true;
        } catch (SecurityException | IllegalArgumentException ignored) {
            stop();
            return false;
        }
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        if (manager != null && listener != null) {
            try {
                manager.removeUpdates(listener);
            } catch (SecurityException ignored) {
                // Permission may have been revoked while locating.
            }
        }
        listener = null;
    }

    private static boolean isBetter(Location candidate, Location current) {
        if (candidate == null) {
            return false;
        }
        if (current == null) {
            return true;
        }
        long timeDelta = candidate.getTime() - current.getTime();
        if (timeDelta > 120_000L) {
            return true;
        }
        if (timeDelta < -120_000L) {
            return false;
        }
        if (!candidate.hasAccuracy()) {
            return false;
        }
        return !current.hasAccuracy() || candidate.getAccuracy() < current.getAccuracy();
    }
}
