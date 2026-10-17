package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Public accessibility text entry for synthetic form values; never changes the global input method. */
final class UiTextChecks {
    void run(Instrumentation runner, String field, String text) {
        Bundle result = new Bundle();
        int code = Activity.RESULT_OK;
        AccessibilityNodeInfo root = null;
        List<AccessibilityNodeInfo> matches = new ArrayList<>();
        try {
            require(field != null, "Missing acceptance field");
            require(Arrays.asList("com.canace.x3schedule.phone.validation.phone", "com.canace.x3schedule.validation")
                    .contains(runner.getTargetContext().getPackageName()), "Isolated validation runner required");
            require(text.matches("QA07-[A-Za-z0-9 _.\\-]{1,80}|x3schedule-[A-Za-z0-9_.\\-]{1,100}\\.x3backup"),
                    "Only synthetic acceptance values are allowed");
            AccessibilityServiceInfo info = runner.getUiAutomation().getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            runner.getUiAutomation().setServiceInfo(info);
            root = runner.getUiAutomation().getRootInActiveWindow();
            require(root != null, "No active UI");
            String owner = String.valueOf(root.getPackageName());
            require(allowedOwner(owner, field), "Unexpected foreground application");
            collect(root, field, matches);
            require(matches.size() == 1, "Expected exactly one editable acceptance field");
            AccessibilityNodeInfo target = matches.get(0);
            Bundle value = new Bundle();
            value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            require(target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value), "Text entry refused");
            require(target.refresh() && text.contentEquals(target.getText()), "Text entry did not persist");
            result.putString("stream", "Synthetic UI text entered through public accessibility API");
        } catch (RuntimeException failure) {
            code = Activity.RESULT_CANCELED;
            result.putString("stream", "Synthetic UI text refused: " + failure.getClass().getSimpleName());
        } finally {
            for (AccessibilityNodeInfo match : matches) {
                match.recycle();
            }
            if (root != null) {
                root.recycle();
            }
        }
        runner.finish(code, result);
    }

    private boolean allowedOwner(String owner, String field) {
        if (field.equals("document-name")) {
            return owner.equals("com.google.android.documentsui") || owner.equals("com.android.documentsui");
        }
        require(Arrays.asList("日程名称", "分类", "完整完成标准（可全部留空）", "基本完成标准", "最低行动标准")
                .contains(field), "Unsupported acceptance field");
        return Arrays.asList("com.canace.x3schedule.phone", "com.canace.x3schedule",
                "com.canace.x3schedule.phone.validation.phone", "com.canace.x3schedule.validation").contains(owner);
    }

    private void collect(AccessibilityNodeInfo node, String field, List<AccessibilityNodeInfo> matches) {
        boolean selected = field.equals("document-name") ? "android:id/title".equals(node.getViewIdResourceName())
                : field.equals(String.valueOf(node.getContentDescription()));
        if (node.isEditable() && selected) {
            matches.add(AccessibilityNodeInfo.obtain(node));
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child != null) {
                collect(child, field, matches);
                child.recycle();
            }
        }
    }
}
