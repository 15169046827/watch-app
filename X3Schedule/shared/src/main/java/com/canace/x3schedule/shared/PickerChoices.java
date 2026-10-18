package com.canace.x3schedule.shared;

import android.view.View;
import android.widget.AdapterView;
import android.widget.Spinner;

final class PickerChoices {
    private PickerChoices() {
    }

    static void observe(Spinner spinner, Runnable update) {
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                update.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Choices always have a selection.
            }
        });
        update.run();
    }
}
