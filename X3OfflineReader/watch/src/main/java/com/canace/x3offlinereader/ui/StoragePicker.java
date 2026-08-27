package com.canace.x3offlinereader.ui;

import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

public final class StoragePicker {
    private StoragePicker(){}
    public static Intent startInMusic(Intent intent){intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic"));return intent;}
}
