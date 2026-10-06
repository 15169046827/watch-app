package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import com.canace.x3schedule.domain.Models.Instance;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

/** ListView recycles rows instead of allocating a button or label for every saved event. */
final class CalendarItemDialog {
    private CalendarItemDialog() {
    }

    static void show(Activity activity, String title, List<Instance> items, ZoneId zone,
            Consumer<Instance> select) {
        ArrayAdapter<Instance> adapter = new ArrayAdapter<Instance>(activity,
                android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                TextView row = recycled instanceof TextView ? (TextView) recycled : new TextView(activity);
                Instance item = getItem(position);
                row.setText(activity.getString(R.string.schedule_item_summary, item.title,
                        CalendarScreen.label(item, zone)));
                row.setTextSize(16);
                row.setMinHeight(ScheduleUi.dp(activity, 64));
                int padding = ScheduleUi.dp(activity, 16);
                row.setPadding(padding, padding, padding, padding);
                return row;
            }
        };
        new AlertDialog.Builder(activity).setTitle(title)
                .setAdapter(adapter, (dialog, position) -> select.accept(items.get(position)))
                .setNegativeButton("关闭", null).show();
    }
}
