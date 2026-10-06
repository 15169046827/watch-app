package com.canace.x3schedule.shared;

import android.app.AlertDialog;
import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

final class EditorControls {
    private EditorControls() {
    }

    static Button row(LinearLayout parent, String title, String value, Runnable action) {
        Button row = ScheduleUi.button(parent, title + "\n" + value + "  ›", () -> safe(parent.getContext(), action));
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        return row;
    }

    static EditText field(LinearLayout parent, String label, String value) {
        ScheduleUi.text(parent, label, 14);
        EditText field = new EditText(parent.getContext());
        ScheduleUi.field(field);
        field.setSingleLine(true);
        field.setContentDescription(label);
        field.setText(value);
        parent.addView(field);
        return field;
    }

    static LinearLayout panel(Context context) {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        int padding = ScheduleUi.dp(context, 16);
        panel.setPadding(padding, 0, padding, padding);
        return panel;
    }

    static void confirm(Context context, String title, LinearLayout panel, Runnable save) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(panel);
        AlertDialog dialog = new AlertDialog.Builder(context).setTitle(title).setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("确定", null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> safe(context, () -> {
            save.run();
            dialog.dismiss();
        }));
    }

    static void safe(Context context, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException error) {
            Toast.makeText(context, error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
