package com.canace.x3voiceledger;

import android.content.Context;
import android.content.SharedPreferences;

final class PrivacyPreferences {
    private static final String PREFS = "privacy_choices";
    private static final String KEY_FIRST_RUN_COMPLETE = "first_run_complete_v1";
    private static final String KEY_PAYMENT_CAPTURE_CONSENT = "payment_capture_consent_v1";
    private static final String PAYMENT_RUNTIME_PREFS = "automatic_payment_dedup";

    private PrivacyPreferences() { }

    static boolean isFirstRunComplete(Context context) {
        return preferences(context).getBoolean(KEY_FIRST_RUN_COMPLETE, false);
    }

    static void markFirstRunComplete(Context context) {
        preferences(context).edit().putBoolean(KEY_FIRST_RUN_COMPLETE, true).apply();
    }

    static boolean isPaymentCaptureConsented(Context context) {
        return preferences(context).getBoolean(KEY_PAYMENT_CAPTURE_CONSENT, false);
    }

    static void setPaymentCaptureConsented(Context context, boolean consented) {
        preferences(context).edit().putBoolean(KEY_PAYMENT_CAPTURE_CONSENT, consented).apply();
        if (!consented) {
            context.getSharedPreferences(PAYMENT_RUNTIME_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .apply();
        }
    }

    static void clearPaymentCaptureState(Context context) {
        preferences(context).edit().remove(KEY_PAYMENT_CAPTURE_CONSENT).apply();
        context.getSharedPreferences(PAYMENT_RUNTIME_PREFS, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}