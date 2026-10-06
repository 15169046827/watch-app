package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;

/** Real Android widgets, synthetic text only. No Activity launch, Store or device operation. */
final class UiStateChecks {
    private int checks;

    void run(Instrumentation runner) {
        Bundle result = new Bundle();
        Throwable[] failure = { null };
        try {
            runner.runOnMainSync(() -> {
                try {
                    forms(runner.getTargetContext());
                } catch (RuntimeException | AssertionError error) {
                    failure[0] = error;
                }
            });
            if (failure[0] != null) {
                throw new IllegalStateException("Synthetic widget checks failed", failure[0]);
            }
            result.putString("stream", "Synthetic UI state passed: " + checks);
            runner.finish(Activity.RESULT_OK, result);
        } catch (RuntimeException | AssertionError error) {
            result.putString("stream", "Synthetic UI state failed: " + error.getClass().getSimpleName());
            runner.finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void check(boolean value) {
        if (!value) {
            throw new AssertionError("UI state check " + (checks + 1));
        }
        checks++;
    }

    private void forms(Context target) {
        Context context = new ContextThemeWrapper(target, android.R.style.Theme_Material_Light);
        LinearLayout first = new LinearLayout(context);
        EditText title = field(first, "名称", "  合成未保存草稿  ");
        EditText date = field(first, "日期", "2026-");
        Spinner choice = choice(first);
        choice.setSelection(2);
        ScreenSession saved = new ScreenSession();
        FormState.capture(first, saved);
        // Pass through the same serialized boundary as an Activity recreated in a new process.
        ScreenSession restored = ScreenSession.decode(saved.encode());
        LinearLayout second = new LinearLayout(context);
        EditText nextDate = field(second, "日期", "2030-01-01");
        Spinner nextChoice = choice(second);
        LinearLayout nested = new LinearLayout(context);
        second.addView(nested);
        EditText nextTitle = field(nested, "名称", "默认标题");
        EditText newField = field(second, "新增字段", "默认值");
        Button submit = new Button(context);
        int[] commits = { 0 };
        submit.setOnClickListener(view -> commits[0]++);
        second.addView(submit);
        FormState.restore(second, restored);
        check(nextTitle.getText().toString().equals(title.getText().toString()));
        check(nextDate.getText().toString().equals(date.getText().toString()));
        check(nextChoice.getSelectedItemPosition() == 2);
        check(newField.getText().toString().equals("默认值"));
        check(commits[0] == 0);
        restored.choices.put("用途", 99);
        FormState.restore(second, restored);
        check(nextChoice.getSelectedItemPosition() == 2);
        restored.choices.put("用途", -1);
        FormState.restore(second, restored);
        check(nextChoice.getSelectedItemPosition() == 2);
        restored.fields.put("名称", "");
        FormState.restore(second, restored);
        check(nextTitle.getText().length() == 0);
        check(commits[0] == 0);
    }

    private EditText field(LinearLayout parent, String label, String value) {
        EditText field = new EditText(parent.getContext());
        field.setContentDescription(label);
        field.setText(value);
        parent.addView(field);
        return field;
    }

    private Spinner choice(LinearLayout parent) {
        Spinner spinner = new Spinner(parent.getContext());
        spinner.setContentDescription("用途");
        spinner.setAdapter(new ArrayAdapter<>(parent.getContext(), android.R.layout.simple_spinner_dropdown_item,
                new String[] { "执行", "提醒", "待办" }));
        parent.addView(spinner);
        return spinner;
    }
}
