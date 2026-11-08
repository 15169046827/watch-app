package com.canace.x3offlinereader;

import android.content.Context;
import android.test.InstrumentationTestCase;
import com.canace.x3offlinereader.data.LibraryDb;

/** Removes only the publicly distributed golden sample left by older device tests. */
public final class WatchGoldenFixtureCleanupTest extends InstrumentationTestCase {
    public void testRemoveLegacyGoldenBookFixture() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        try (LibraryDb db = new LibraryDb(context)) {
            GoldenBookFixture.clean(getInstrumentation(), context, db);
        }
    }
}
