package com.canace.x3wolainotes;

final class PersonalTokenPolicy {
    static final int MAX_LENGTH = 1024;
    private static final int MIN_LENGTH = 20;

    private PersonalTokenPolicy() {
    }

    static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    static boolean isValid(String value) {
        String token = normalize(value);
        return token.length() >= MIN_LENGTH && token.length() <= MAX_LENGTH
                && token.indexOf('\r') < 0 && token.indexOf('\n') < 0;
    }
}
