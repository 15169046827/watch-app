package com.canace.x3voiceledger.phone;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class YimuImporter {
    private static final String YIMU_PACKAGE = "com.wangc.bill";

    private YimuImporter() { }

    static boolean launch(Activity activity, PhoneLedgerStore.Entry entry) {
        return launch(activity, entry, YimuCategoryMapper.map(entry.category, entry.note));
    }

    static boolean launch(Activity activity, PhoneLedgerStore.Entry entry,
                          YimuCategoryMapper.Mapping category) {
        return launch(activity, entry, category, null);
    }

    static boolean launch(Activity activity, PhoneLedgerStore.Entry entry,
                          YimuCategoryMapper.Mapping category, LedgerProfileStore profile) {
        String remark = entry.note;
        if (!entry.locationLabel.isEmpty()) remark += " · 地点：" + entry.locationLabel;
        Uri.Builder uri = new Uri.Builder()
                .scheme("yimu")
                .authority("api")
                .appendPath("addbill")
                .appendQueryParameter("parentCategory", category.parent)
                .appendQueryParameter("money", String.format(Locale.US, "%.2f", entry.amountCents / 100d))
                .appendQueryParameter("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                        .format(new Date(entry.transactionTime)))
                .appendQueryParameter("remark", remark);
        if (!category.child.isEmpty()) uri.appendQueryParameter("childCategory", category.child);
        String account = entry.account.isEmpty() && profile != null ? profile.defaultAccount() : entry.account;
        String bookName = entry.bookName.isEmpty() && profile != null ? profile.bookName() : entry.bookName;
        String tags = entry.tags.isEmpty() && profile != null ? profile.tags() : entry.tags;
        if (!account.isEmpty()) uri.appendQueryParameter("asset", account);
        if (!bookName.isEmpty()) uri.appendQueryParameter("bookName", bookName);
        if (!tags.isEmpty()) uri.appendQueryParameter("tags", tags);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri.build()).setPackage(YIMU_PACKAGE);
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "未找到可接收账单的一木记账，请确认已安装最新版",
                    Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
