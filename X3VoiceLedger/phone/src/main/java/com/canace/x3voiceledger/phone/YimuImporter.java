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

    static boolean launch(
            Activity activity, PhoneLedgerStore.Entry entry, YimuCategoryMapper.Mapping category) {
        return launch(activity, entry, category, null);
    }

    static boolean launch(
            Activity activity,
            PhoneLedgerStore.Entry entry,
            YimuCategoryMapper.Mapping category,
            LedgerProfileStore profile) {
        Intent intent =
                new Intent(Intent.ACTION_VIEW, buildUri(entry, category, profile))
                        .setPackage(YIMU_PACKAGE);
        return start(activity, intent);
    }

    private static Uri buildUri(
            PhoneLedgerStore.Entry entry,
            YimuCategoryMapper.Mapping category,
            LedgerProfileStore profile) {
        Uri.Builder uri =
                new Uri.Builder()
                        .scheme("yimu")
                        .authority("api")
                        .appendPath("addbill")
                        .appendQueryParameter("parentCategory", category.parent)
                        .appendQueryParameter(
                                "money", String.format(Locale.US, "%.2f", entry.amountCents / 100d))
                        .appendQueryParameter("time", formattedTime(entry.transactionTime))
                        .appendQueryParameter("remark", remark(entry));
        appendOptional(uri, "childCategory", category.child);
        appendOptional(uri, "asset", account(entry, profile));
        appendOptional(uri, "bookName", bookName(entry, profile));
        appendOptional(uri, "tags", tags(entry, profile));
        return uri.build();
    }

    private static String formattedTime(long transactionTime) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new Date(transactionTime));
    }

    private static String remark(PhoneLedgerStore.Entry entry) {
        if (entry.locationLabel.isEmpty()) {
            return entry.note;
        }
        return entry.note + " · 地点：" + entry.locationLabel;
    }

    private static String account(
            PhoneLedgerStore.Entry entry, LedgerProfileStore profile) {
        return entry.account.isEmpty() && profile != null
                ? profile.defaultAccount()
                : entry.account;
    }

    private static String bookName(
            PhoneLedgerStore.Entry entry, LedgerProfileStore profile) {
        return entry.bookName.isEmpty() && profile != null
                ? profile.bookName()
                : entry.bookName;
    }

    private static String tags(
            PhoneLedgerStore.Entry entry, LedgerProfileStore profile) {
        return entry.tags.isEmpty() && profile != null ? profile.tags() : entry.tags;
    }

    private static void appendOptional(Uri.Builder uri, String name, String value) {
        if (!value.isEmpty()) {
            uri.appendQueryParameter(name, value);
        }
    }

    private static boolean start(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "未找到可接收账单的一木记账，请确认已安装最新版", Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
