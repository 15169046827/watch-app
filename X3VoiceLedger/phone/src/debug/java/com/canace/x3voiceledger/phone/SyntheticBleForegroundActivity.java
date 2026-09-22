package com.canace.x3voiceledger.phone;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Debug-only foreground host used by isolated BLE device acceptance. */
public final class SyntheticBleForegroundActivity extends Activity {
    static final String ACTION_FINISH =
            "com.canace.x3voiceledger.phone.action.FINISH_SYNTHETIC_BLE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finishIfRequested(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        finishIfRequested(intent);
    }

    private void finishIfRequested(Intent intent) {
        if (intent != null && ACTION_FINISH.equals(intent.getAction())) {
            finish();
        }
    }
}
