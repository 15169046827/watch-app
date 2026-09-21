package com.canace.x3offlinereader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.test.InstrumentationTestCase;

/** Opens the OPPO document provider long enough for a shell-side initial-location inspection. */
public final class WatchStoragePickerProbeTest extends InstrumentationTestCase {
    public void testNovelPickerInitialLocation() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Activity library =
                getInstrumentation()
                        .startActivitySync(
                                new Intent(context, BookLibraryActivity.class)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            getInstrumentation()
                    .runOnMainSync(() -> library.findViewById(R.id.choose_button).performClick());
            Thread.sleep(30000);
        } finally {
            try (ParcelFileDescriptor ignored =
                    getInstrumentation()
                            .getUiAutomation()
                            .executeShellCommand("am force-stop com.android.documentsui")) {
                Thread.sleep(500);
            }
            getInstrumentation().runOnMainSync(library::finish);
            getInstrumentation().waitForIdleSync();
        }
    }
}
