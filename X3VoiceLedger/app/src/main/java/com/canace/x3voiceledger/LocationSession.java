package com.canace.x3voiceledger;

final class LocationSession {
    private long generation;
    private LocationSnapshot latest;

    long begin() {
        generation++;
        latest = null;
        return generation;
    }

    long generation() {
        return generation;
    }

    boolean accept(long requestGeneration, LocationSnapshot snapshot) {
        if (requestGeneration != generation || snapshot == null) {
            return false;
        }
        latest = snapshot;
        return true;
    }

    LocationSnapshot latest() {
        return latest;
    }
}
