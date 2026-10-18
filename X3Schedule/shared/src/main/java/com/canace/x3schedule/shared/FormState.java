package com.canace.x3schedule.shared;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;

/** Form labels are stable keys within a screen; buttons are deliberately never replayed. */
final class FormState {
    private FormState() {
    }

    static void capture(View view, ScreenSession state) {
        visit(view, state, false);
    }

    static void restore(View view, ScreenSession state) {
        visit(view, state, true);
    }

    private static void visit(View view, ScreenSession state, boolean restore) {
        CharSequence label = view.getContentDescription();
        if (label != null) {
            value(view, label.toString(), state, restore);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                visit(group.getChildAt(index), state, restore);
            }
        }
    }

    private static void value(View view, String key, ScreenSession state, boolean restore) {
        if (view instanceof EditText) {
            EditText field = (EditText) view;
            if (!restore) {
                state.fields.put(key, field.getText().toString());
            } else if (state.fields.get(key) != null) {
                field.setText(state.fields.get(key));
            }
        } else if (view instanceof Spinner) {
            Spinner spinner = (Spinner) view;
            if (!restore) {
                state.choices.put(key, spinner.getSelectedItemPosition());
            } else {
                Integer position = state.choices.get(key);
                if (position != null && position >= 0 && position < spinner.getCount()) {
                    spinner.setSelection(position);
                }
            }
        }
    }
}
