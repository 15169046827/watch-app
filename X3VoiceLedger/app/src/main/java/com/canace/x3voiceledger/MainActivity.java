package com.canace.x3voiceledger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.text.ParseException;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final int LOCATION_PERMISSION_REQUEST = 41;
    private static final String TIME_FORMAT = "yyyy-MM-dd HH:mm";
    private static final int BG = Color.rgb(8, 11, 16);
    private static final int CARD = Color.rgb(25, 31, 39);
    private static final int PRIMARY = Color.rgb(85, 230, 165);
    private static final int MUTED = Color.rgb(159, 171, 185);

    private LedgerStore store;
    private WatchProfileStore profileStore;
    private EditText spokenInput;
    private LedgerParser.Result pending;
    private LocationCapture locationCapture;
    private LocationSnapshot latestLocation;
    private String automaticLocationLabel = "";
    private EditText amountEditor;
    private EditText noteEditor;
    private EditText categoryEditor;
    private EditText categoryParentEditor;
    private EditText categoryChildEditor;
    private EditText accountEditor;
    private EditText bookNameEditor;
    private EditText tagsEditor;
    private EditText timeEditor;
    private EditText locationEditor;
    private TextView locationStatus;
    private TextView automaticRecognitionStatus;
    private Button automaticRecognitionButton;
    private LedgerEntry editingEntry;
    private boolean confirmationVisible;
    private boolean managementVisible;
    private float downX;
    private float downY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        store = new LedgerStore(this);
        profileStore = new WatchProfileStore(this);
        locationCapture = new LocationCapture(this);
        showEntryScreen("");
        WatchRetryJobService.schedule(this);
        if (store.pendingCount() > 0) BleSyncService.start(this);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAutomaticRecognitionStatus();
    }

    private void showEntryScreen(String initialText) {
        confirmationVisible = false;
        managementVisible = false;
        pending = null;
        editingEntry = null;

        LinearLayout content = baseContent();
        content.addView(text("记账", 24, Color.WHITE, true));
        TextView counter = text("已保存 " + store.count() + " 笔 · 待同步 "
                + store.pendingCount() + " 笔", 13, MUTED, false);
        counter.setPadding(0, dp(2), 0, dp(8));
        content.addView(counter);

        automaticRecognitionStatus = text("", 11, MUTED, false);
        content.addView(automaticRecognitionStatus);
        automaticRecognitionButton = button("", Color.rgb(65, 76, 90), Color.WHITE);
        automaticRecognitionButton.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "请开启“支付自动记账”", Toast.LENGTH_LONG).show();
        });
        content.addView(automaticRecognitionButton, params(-1, dp(40), 0, dp(4)));
        refreshAutomaticRecognitionStatus();

        spokenInput = new EditText(this);
        spokenInput.setText(initialText);
        spokenInput.setHint("例如：午饭25元");
        spokenInput.setHintTextColor(MUTED);
        spokenInput.setTextColor(Color.WHITE);
        spokenInput.setTextSize(17);
        spokenInput.setSingleLine(true);
        spokenInput.setGravity(Gravity.CENTER);
        spokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        spokenInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        spokenInput.setBackground(rounded(CARD, 18));
        spokenInput.setPadding(dp(12), 0, dp(12), 0);
        content.addView(spokenInput, params(-1, dp(54), 0, dp(4)));
        spokenInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                parseAndConfirm();
                return true;
            }
            return false;
        });

        Button voiceButton = button("打开输入法 · 点击语音", PRIMARY, Color.rgb(5, 42, 29));
        voiceButton.setOnClickListener(v -> openKeyboard());
        content.addView(voiceButton, params(-1, dp(46), 0, dp(5)));

        Button parseButton = button("解析账单", Color.rgb(65, 76, 90), Color.WHITE);
        parseButton.setOnClickListener(v -> parseAndConfirm());
        content.addView(parseButton, params(-1, dp(42), 0, dp(4)));

        TextView profileStatus = text(profileStore.summary(), 11, MUTED, false);
        profileStatus.setPadding(0, dp(2), 0, dp(2));
        content.addView(profileStatus);
        Button profileButton = button("接收手机账户 / 分类", Color.rgb(65, 76, 90), Color.WHITE);
        profileButton.setOnClickListener(v -> {
            BleSyncService.startConfigWindow(this);
            Toast.makeText(this, "正在等待手机伴侣同步配置", Toast.LENGTH_SHORT).show();
        });
        content.addView(profileButton, params(-1, dp(40), 0, dp(4)));

        if (store.count() > 0) {
            Button manageButton = button("管理 / 删除账单", Color.rgb(65, 76, 90), Color.WHITE);
            manageButton.setOnClickListener(v -> showLedgerManager());
            content.addView(manageButton, params(-1, dp(40), 0, dp(4)));
        }

        addRecent(content);
        setContentView(wrap(content));
    }

    private void openKeyboard() {
        spokenInput.requestFocus();
        spokenInput.setSelection(spokenInput.length());
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            spokenInput.postDelayed(() -> imm.showSoftInput(spokenInput, InputMethodManager.SHOW_IMPLICIT), 120);
        }
    }

    private void parseAndConfirm() {
        hideKeyboard();
        LedgerParser.Result result = LedgerParser.parse(spokenInput.getText().toString());
        if (!result.valid) {
            spokenInput.setError(result.error);
            Toast.makeText(this, result.error, Toast.LENGTH_SHORT).show();
            return;
        }
        showConfirmation(result);
        requestCurrentLocation();
    }

    private void showConfirmation(LedgerParser.Result result) {
        confirmationVisible = true;
        managementVisible = false;
        pending = result;

        LinearLayout content = baseContent();
        TextView label = text("确认账单", 17, MUTED, false);
        label.setPadding(0, 0, 0, dp(2));
        content.addView(label);

        amountEditor = editableField(content, "金额", String.format(Locale.CHINA, "%.2f", result.amountCents / 100d),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        noteEditor = editableField(content, "内容", result.note, InputType.TYPE_CLASS_TEXT);
        categoryEditor = editableField(content, "分类", result.category, InputType.TYPE_CLASS_TEXT);
        WatchProfileStore.CategorySelection category = profileStore.categoryFor(result.category, result.note);
        categoryParentEditor = editableField(content, "一木父分类", category.parent, InputType.TYPE_CLASS_TEXT);
        categoryChildEditor = editableField(content, "一木子分类", category.child, InputType.TYPE_CLASS_TEXT);
        accountEditor = editableField(content, "支出账户", profileStore.inferAccount(result.rawText),
                InputType.TYPE_CLASS_TEXT);
        accountEditor.setHint("例如：支付宝；可留空后在手机确认");
        bookNameEditor = editableField(content, "账本", profileStore.bookName(), InputType.TYPE_CLASS_TEXT);
        tagsEditor = editableField(content, "标签", profileStore.tags(), InputType.TYPE_CLASS_TEXT);
        timeEditor = editableField(content, "消费时间", formatTime(result.transactionTime), InputType.TYPE_CLASS_TEXT);

        boolean hasSpokenLocation = result.locationLabel != null && !result.locationLabel.isEmpty();
        String locationText = hasSpokenLocation ? result.locationLabel
                : latestLocation == null ? "" : latestLocation.coordinateLabel();
        automaticLocationLabel = hasSpokenLocation ? "" : locationText;
        locationEditor = editableField(content, "地点", locationText, InputType.TYPE_CLASS_TEXT);
        locationEditor.setHint("可说地点，也可手动填写");
        String initialLocationStatus = hasSpokenLocation ? "已采用语音地点，仍会尝试保留原始坐标"
                : latestLocation == null ? "正在获取定位…" : locationDetail(latestLocation);
        locationStatus = text(initialLocationStatus, 11, MUTED, false);
        content.addView(locationStatus);

        Button locateButton = button("更新定位", Color.rgb(65, 76, 90), Color.WHITE);
        locateButton.setOnClickListener(v -> requestCurrentLocation());
        content.addView(locateButton, params(-1, dp(38), 0, dp(4)));

        Button saveButton = button("保存", PRIMARY, Color.rgb(5, 42, 29));
        saveButton.setOnClickListener(v -> savePending());
        content.addView(saveButton, params(-1, dp(50), 0, dp(5)));

        boolean editing = editingEntry != null;
        Button editButton = button(editing ? "取消修改" : "重新输入",
                Color.rgb(65, 76, 90), Color.WHITE);
        editButton.setOnClickListener(v -> {
            if (editing) showLedgerManager();
            else showEntryScreen(result.rawText);
        });
        content.addView(editButton, params(-1, dp(42), 0, dp(4)));

        TextView hint = text("向右滑动返回", 12, MUTED, false);
        hint.setPadding(0, dp(5), 0, 0);
        content.addView(hint);
        setContentView(wrap(content));
    }

    private void showLedgerManager() {
        confirmationVisible = false;
        managementVisible = true;
        LinearLayout content = baseContent();
        content.addView(text("账单管理", 21, Color.WHITE, true));
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (LedgerEntry entry : store.recent(30)) {
            String line = entry.note + "  " + LedgerParser.formatAmount(entry.amountCents)
                    + "\n" + format.format(new Date(entry.transactionTime))
                    + (entry.account.isEmpty() ? "" : " · " + entry.account);
            TextView row = text(line, 13, Color.WHITE, false);
            row.setBackground(rounded(CARD, 12));
            row.setPadding(dp(8), dp(5), dp(8), dp(5));
            content.addView(row, params(-1, dp(44), 2, 1));
            Button edit = button("修改这笔", Color.rgb(65, 76, 90), Color.WHITE);
            edit.setOnClickListener(v -> showEntryEditor(entry));
            content.addView(edit, params(-1, dp(34), 0, dp(2)));
            Button delete = button("删除这笔", Color.rgb(92, 48, 54), Color.WHITE);
            delete.setOnClickListener(v -> confirmDelete(entry));
            content.addView(delete, params(-1, dp(34), 0, dp(4)));
        }
        TextView hint = text("删除仅影响手表本机 · 向右滑动返回", 11, MUTED, false);
        content.addView(hint);
        setContentView(wrap(content));
    }

    private void confirmDelete(LedgerEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("删除这笔账单？")
                .setMessage(entry.note + "  " + LedgerParser.formatAmount(entry.amountCents)
                        + "\n不会删除手机或一木中的副本。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    stopService(new Intent(this, BleSyncService.class));
                    if (store.delete(entry.id)) {
                        Toast.makeText(this, "已从手表删除", Toast.LENGTH_SHORT).show();
                    }
                    if (store.pendingCount() > 0) BleSyncService.start(this);
                    if (store.count() == 0) showEntryScreen("");
                    else showLedgerManager();
                })
                .show();
    }

    private void savePending() {
        if (pending == null) return;
        long amountCents = LedgerParser.parseAmountText(amountEditor.getText().toString());
        String note = noteEditor.getText().toString().trim();
        String category = categoryEditor.getText().toString().trim();
        String categoryParent = categoryParentEditor.getText().toString().trim();
        String categoryChild = categoryChildEditor.getText().toString().trim();
        String account = accountEditor.getText().toString().trim();
        String bookName = bookNameEditor.getText().toString().trim();
        String tags = tagsEditor.getText().toString().trim();
        long transactionTime = parseTime(timeEditor.getText().toString());
        if (amountCents <= 0) {
            amountEditor.setError("请输入有效金额");
            return;
        }
        if (note.isEmpty()) {
            noteEditor.setError("请输入消费内容");
            return;
        }
        if (category.isEmpty()) {
            categoryEditor.setError("请输入分类");
            return;
        }
        if (transactionTime <= 0) {
            timeEditor.setError("格式应为 2026-08-17 17:30");
            return;
        }
        long now = System.currentTimeMillis();
        LedgerEntry original = editingEntry;
        Double latitude = latestLocation != null ? latestLocation.latitude
                : original == null ? null : original.latitude;
        Double longitude = latestLocation != null ? latestLocation.longitude
                : original == null ? null : original.longitude;
        Float accuracy = latestLocation != null ? latestLocation.accuracy
                : original == null ? null : original.locationAccuracy;
        Long locationTime = latestLocation != null ? latestLocation.time
                : original == null ? null : original.locationTime;
        LedgerEntry entry = new LedgerEntry(
                original == null ? UUID.randomUUID().toString() : original.id,
                original == null ? now : original.createdAt, now, transactionTime, amountCents,
                note, category, categoryParent, categoryChild, account, bookName, tags, pending.rawText,
                original == null ? "watch_voice" : original.source,
                latitude, longitude, accuracy, locationTime,
                locationEditor.getText().toString().trim());
        try {
            if (original == null) store.insert(entry);
            else if (!store.update(entry)) throw new IllegalStateException("entry missing");
            BleSyncService.start(this);
            Toast.makeText(this, original == null ? "已保存，正在自动同步" : "修改已保存，将同步到手机",
                    Toast.LENGTH_SHORT).show();
            showEntryScreen("");
        } catch (RuntimeException error) {
            Toast.makeText(this, "保存失败，请重试", Toast.LENGTH_LONG).show();
        }
    }

    private void addRecent(LinearLayout content) {
        List<LedgerEntry> recent = store.recent(3);
        if (recent.isEmpty()) return;
        TextView title = text("最近", 12, MUTED, false);
        title.setPadding(0, dp(5), 0, dp(1));
        content.addView(title);
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (LedgerEntry entry : recent) {
            String line = entry.note + "  " + LedgerParser.formatAmount(entry.amountCents) +
                    "\n" + format.format(new Date(entry.transactionTime)) +
                    (entry.account.isEmpty() ? "" : " · " + entry.account) +
                    (entry.locationLabel.isEmpty() ? "" : " · " + entry.locationLabel) +
                    ("alipay_nfc_accessibility".equals(entry.source) ? "\n支付宝自动识别 · 待核对" : "");
            TextView row = text(line, 13, Color.WHITE, false);
            row.setGravity(Gravity.CENTER);
            row.setBackground(rounded(CARD, 12));
            row.setPadding(dp(8), dp(4), dp(8), dp(4));
            content.addView(row, params(-1, dp(42), 0, dp(2)));
        }
    }

    private LinearLayout baseContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setBackgroundColor(BG);
        int inset = isRoundScreen() ? dp(30) : dp(18);
        content.setPadding(inset, dp(10), inset, dp(12));
        return content;
    }

    private View wrap(LinearLayout content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private boolean isRoundScreen() {
        return (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_ROUND_MASK)
                == Configuration.SCREENLAYOUT_ROUND_YES;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private EditText editableField(LinearLayout content, String label, String value, int inputType) {
        TextView fieldLabel = text(label, 11, MUTED, false);
        fieldLabel.setGravity(Gravity.START);
        content.addView(fieldLabel, params(-1, dp(18), 2, 0));
        EditText editor = new EditText(this);
        editor.setText(value);
        editor.setTextSize(16);
        editor.setTextColor(Color.WHITE);
        editor.setHintTextColor(MUTED);
        editor.setSingleLine(true);
        editor.setGravity(Gravity.CENTER);
        editor.setInputType(inputType);
        editor.setSelectAllOnFocus(true);
        editor.setBackground(rounded(CARD, 14));
        editor.setPadding(dp(9), 0, dp(9), 0);
        content.addView(editor, params(-1, dp(44), 0, dp(2)));
        return editor;
    }

    private void requestCurrentLocation() {
        if (!locationCapture.hasPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
            return;
        }
        if (locationStatus != null) locationStatus.setText("正在获取定位…");
        boolean started = locationCapture.start(new LocationCapture.Callback() {
            @Override
            public void onLocation(LocationSnapshot snapshot) {
                applyLocation(snapshot);
            }

            @Override
            public void onUnavailable(String reason) {
                if (locationStatus != null) locationStatus.setText(reason);
            }
        });
        if (!started && locationStatus != null) locationStatus.setText("定位不可用，可手动填写地点");
    }

    private void applyLocation(LocationSnapshot snapshot) {
        latestLocation = snapshot;
        String newLabel = snapshot.coordinateLabel();
        if (locationEditor != null) {
            String current = locationEditor.getText().toString().trim();
            if (current.isEmpty() || current.equals(automaticLocationLabel)) {
                locationEditor.setText(newLabel);
                automaticLocationLabel = newLabel;
            }
        }
        if (locationStatus != null) locationStatus.setText(locationDetail(snapshot));
    }

    private String locationDetail(LocationSnapshot snapshot) {
        String accuracy = snapshot.accuracy > 0 ? "精度约 ±" + Math.round(snapshot.accuracy) + " 米" : "精度未知";
        return accuracy + " · 原始坐标将保留";
    }

    private static String formatTime(long millis) {
        return new SimpleDateFormat(TIME_FORMAT, Locale.CHINA).format(new Date(millis));
    }

    private static long parseTime(String value) {
        SimpleDateFormat format = new SimpleDateFormat(TIME_FORMAT, Locale.CHINA);
        format.setLenient(false);
        try {
            Date parsed = format.parse(value.trim());
            return parsed == null ? -1L : parsed.getTime();
        } catch (ParseException ignored) {
            return -1L;
        }
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String id = intent.getStringExtra(AlipayPaymentAccessibilityService.EXTRA_EDIT_ENTRY_ID);
        intent.removeExtra(AlipayPaymentAccessibilityService.EXTRA_EDIT_ENTRY_ID);
        if (id == null || id.isEmpty()) return;
        LedgerEntry entry = store.find(id);
        if (entry != null) showEntryEditor(entry);
    }

    private void showEntryEditor(LedgerEntry entry) {
        editingEntry = entry;
        LedgerParser.Result result = LedgerParser.Result.success(entry.amountCents, entry.note,
                entry.category, entry.rawText, entry.transactionTime, entry.locationLabel);
        showConfirmation(result);
        editingEntry = entry;
        categoryParentEditor.setText(entry.categoryParent);
        categoryChildEditor.setText(entry.categoryChild);
        accountEditor.setText(entry.account);
        bookNameEditor.setText(entry.bookName);
        tagsEditor.setText(entry.tags);
        locationEditor.setText(entry.locationLabel);
    }

    private void refreshAutomaticRecognitionStatus() {
        if (automaticRecognitionStatus == null || automaticRecognitionButton == null) return;
        boolean enabled = isAutomaticRecognitionEnabled();
        automaticRecognitionStatus.setText(enabled
                ? "支付宝自动记账已开启 · 商户待补充"
                : "可自动读取支付宝付款金额");
        automaticRecognitionButton.setText(enabled ? "自动识别已开启 · 查看设置" : "开启支付自动记账");
        automaticRecognitionButton.setBackground(rounded(enabled ? Color.rgb(34, 100, 75)
                : Color.rgb(65, 76, 90), 22));
    }

    private boolean isAutomaticRecognitionEnabled() {
        if (Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
            return false;
        }
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String component = new ComponentName(this, AlipayPaymentAccessibilityService.class)
                .flattenToString();
        for (String value : enabled.split(":")) if (component.equalsIgnoreCase(value)) return true;
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != LOCATION_PERMISSION_REQUEST) return;
        boolean granted = false;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_GRANTED) granted = true;
        }
        if (granted) requestCurrentLocation();
        else if (locationStatus != null) locationStatus.setText("未授权定位，可手动填写地点");
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setBackground(rounded(background, 22));
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams params(int width, int height, int top, int bottom) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(width, height);
        result.setMargins(0, dp(top), 0, dp(bottom));
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        focus.clearFocus();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            float dy = Math.abs(event.getY() - downY);
            if (dx > getResources().getDisplayMetrics().widthPixels * 0.28f && dy < dx * 0.65f) {
                hideKeyboard();
                if (confirmationVisible) showEntryScreen(pending == null ? "" : pending.rawText);
                else if (managementVisible) showEntryScreen("");
                else finish();
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        if (confirmationVisible) showEntryScreen(pending == null ? "" : pending.rawText);
        else if (managementVisible) showEntryScreen("");
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        locationCapture.stop();
        store.close();
        super.onDestroy();
    }
}
