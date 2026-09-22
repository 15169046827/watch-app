package com.canace.x3wolainotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DataManagementActivity extends Activity {
    private static final int CREATE_BACKUP = 1101;
    private static final int OPEN_BACKUP = 1102;
    private static final int BG = Color.rgb(9, 12, 18);
    private static final int CARD = Color.rgb(25, 31, 41);
    private static final int CARD_ALT = Color.rgb(38, 47, 60);
    private static final int PRIMARY = Color.rgb(104, 232, 181);
    private static final int PRIMARY_DARK = Color.rgb(8, 54, 38);
    private static final int MUTED = Color.rgb(157, 170, 188);
    private static final int WARN = Color.rgb(255, 194, 102);

    private NoteStore store;
    private PageConfigStore pageConfig;
    private SecretStore secretStore;
    private TextView pageStatus;
    private Button configurePagesButton;
    private Button deletePagesButton;
    private AlertDialog pageConfigDialog;
    private TextView tokenStatus;
    private Button importTokenButton;
    private Button deleteTokenButton;
    private AlertDialog tokenImportDialog;
    private byte[] pendingImport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        store = new NoteStore(this);
        pageConfig = new PageConfigStore(this);
        secretStore = new SecretStore(this);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setBackgroundColor(BG);
        int side = isRoundScreen() ? dp(32) : dp(24);
        content.setPadding(side, dp(10), side, dp(18));
        content.addView(text("数据与授权", 22, Color.WHITE, true));
        content.addView(text("导出文件未加密，含本地笔记正文；不含授权、页面配置或在线内容。",
                11, WARN, false), params(-1, -2, 0, 7));

        pageStatus = text("", 11, MUTED, false);
        content.addView(pageStatus, params(-1, -2, 0, 4));
        configurePagesButton = button("配置 Wolai 页面", PRIMARY, PRIMARY_DARK);
        configurePagesButton.setOnClickListener(v -> showPageConfig());
        content.addView(configurePagesButton, params(-1, dp(42), 0, 5));
        deletePagesButton = button("删除页面配置", CARD_ALT, WARN);
        deletePagesButton.setOnClickListener(v -> confirm("删除页面配置？",
                "删除后不显示也不保留两个页面 ID；如需更换，必须重新配置。",
                () -> {
                    pageConfig.clearPages();
                    refreshPageStatus();
                    toast("页面配置已删除");
                }));
        content.addView(deletePagesButton, params(-1, dp(40), 0, 7));

        content.addView(text(
                "个人 Token 不是 OAuth。它可访问你授权的 Wolai 内容，请只填写自己创建或持有的 Token。"
                        + "Token 仅加密保存在本机，不进入 APK、日志或备份；清除本机授权不等于远端撤销。",
                11, WARN, false), params(-1, -2, 0, 6));
        tokenStatus = text("", 11, MUTED, false);
        content.addView(tokenStatus, params(-1, -2, 0, 4));
        importTokenButton = button("导入个人 Token", PRIMARY, PRIMARY_DARK);
        importTokenButton.setOnClickListener(v -> showTokenImport());
        content.addView(importTokenButton, params(-1, dp(42), 0, 5));
        deleteTokenButton = button("删除个人 Token", CARD_ALT, WARN);
        deleteTokenButton.setOnClickListener(v -> confirm("删除个人 Token？",
                "删除后无法恢复；如需更换，必须先删除再重新导入。"
                        + "因 Wolai 尚无已核实的撤销接口，远端授权需在 Wolai 账户中另行撤销。",
                () -> {
                    secretStore.clearLocalAuthorization();
                    refreshTokenStatus();
                    toast("个人 Token 已从本机删除");
                }));
        content.addView(deleteTokenButton, params(-1, dp(40), 0, 7));

        Button export = button("导出本地数据", CARD_ALT, Color.WHITE);
        export.setOnClickListener(v -> chooseExport());
        content.addView(export, params(-1, dp(40), 0, 4));
        Button restore = button("导入并合并备份", CARD_ALT, Color.WHITE);
        restore.setOnClickListener(v -> chooseImport());
        content.addView(restore, params(-1, dp(40), 0, 7));

        Button clearNotes = button("清除本地笔记", CARD_ALT, WARN);
        clearNotes.setOnClickListener(v -> confirm("清除全部本地笔记？",
                "包括已同步、待同步、失败和待确认记录；Wolai 中已有内容不会删除。",
                () -> {
                    store.clearLocalNotes();
                    toast("本地笔记已清除");
                }));
        content.addView(clearNotes, params(-1, dp(40), 0, 4));
        Button clearRecent = button("清除最近访问", CARD_ALT, WARN);
        clearRecent.setOnClickListener(v -> confirm("清除最近访问？",
                "只清除本机页面标题和访问时间，不影响 Wolai 内容。",
                () -> {
                    store.clearRecentPages();
                    toast("最近访问已清除");
                }));
        content.addView(clearRecent, params(-1, dp(40), 0, 4));
        Button clearAll = button("清除全部本机数据", CARD_ALT, WARN);
        clearAll.setOnClickListener(v -> confirm("清除全部本机数据？",
                "将清除本地笔记、最近访问、页面配置和本机授权；系统权限不变。",
                () -> {
                    store.clearAll();
                    pageConfig.clearPages();
                    secretStore.clearLocalAuthorization();
                    refreshPageStatus();
                    refreshTokenStatus();
                    toast("全部本机数据已清除");
                }));
        content.addView(clearAll, params(-1, dp(42), 0, 7));

        content.addView(text("返回后重新进入相关页面，配置才会生效。右滑或返回键退出。",
                10, MUTED, false));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        refreshPageStatus();
        refreshTokenStatus();
    }

    private void showTokenImport() {
        if (secretStore.hasStoredToken()) {
            toast("请先删除现有 Token，再重新导入");
            refreshTokenStatus();
            return;
        }
        EditText input = secureSensitiveInput("个人 Token（不会回显）");
        input.setFilters(new InputFilter[] {
            new InputFilter.LengthFilter(PersonalTokenPolicy.MAX_LENGTH)
        });
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(4), dp(20), 0);
        body.addView(text("导入后不会回显或允许修改；如需更换，必须先删除旧 Token。",
                11, WARN, false), params(-1, -2, 0, 8));
        body.addView(input, params(-1, dp(46), 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("导入个人 Token")
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认导入", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> importPersonalToken(input, dialog)));
        dialog.setOnDismissListener(ignored -> {
            input.setText("");
            if (tokenImportDialog == dialog) {
                tokenImportDialog = null;
            }
        });
        tokenImportDialog = dialog;
        dialog.show();
    }

    private void importPersonalToken(EditText input, AlertDialog dialog) {
        String value = input.getText().toString();
        if (!PersonalTokenPolicy.isValid(value)) {
            input.setError("Token 格式无效");
            return;
        }
        try {
            secretStore.savePersonalToken(value);
            input.setText("");
            dialog.dismiss();
            refreshTokenStatus();
            toast("个人 Token 已加密导入");
        } catch (Exception error) {
            input.setText("");
            toast("个人 Token 导入失败");
        }
    }

    private void refreshTokenStatus() {
        if (tokenStatus == null || importTokenButton == null || deleteTokenButton == null) {
            return;
        }
        boolean configured = secretStore.hasStoredToken();
        if (configured) {
            long savedAt = secretStore.tokenSavedAt();
            String imported = savedAt > 0L
                    ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                            .format(new Date(savedAt))
                    : "旧版本导入（时间未知）";
            tokenStatus.setText("状态：已配置个人 Token\n导入时间：" + imported
                    + "\nToken 不显示、不可修改");
            importTokenButton.setVisibility(View.GONE);
            deleteTokenButton.setVisibility(View.VISIBLE);
        } else {
            tokenStatus.setText("状态：未配置个人 Token（本地功能可用）");
            importTokenButton.setVisibility(View.VISIBLE);
            deleteTokenButton.setVisibility(View.GONE);
        }
    }

    private void showPageConfig() {
        if (pageConfig.hasConfiguredPages()) {
            toast("请先删除现有页面配置，再重新配置");
            refreshPageStatus();
            return;
        }
        EditText target = secureSensitiveInput("同步页面 ID");
        EditText favorites = secureSensitiveInput("收藏页面 ID");
        InputFilter[] filters = new InputFilter[] {
            new InputFilter.LengthFilter(ApiInputPolicy.MAX_RESOURCE_ID_LENGTH)
        };
        target.setFilters(filters);
        favorites.setFilters(filters);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(4), dp(20), 0);
        body.addView(text("页面 ID 只在本次配置时输入；保存后不显示、不可修改。",
                11, WARN, false), params(-1, -2, 0, 8));
        body.addView(target, params(-1, dp(46), 0, 5));
        body.addView(favorites, params(-1, dp(46), 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("配置 Wolai 页面")
                .setView(body)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认配置", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> savePageConfig(target, favorites, dialog)));
        dialog.setOnDismissListener(ignored -> {
            target.setText("");
            favorites.setText("");
            if (pageConfigDialog == dialog) {
                pageConfigDialog = null;
            }
        });
        pageConfigDialog = dialog;
        dialog.show();
    }

    private void savePageConfig(EditText target, EditText favorites, AlertDialog dialog) {
        if (!pageConfig.save(target.getText().toString(), favorites.getText().toString())) {
            toast("页面 ID 格式无效");
            return;
        }
        target.setText("");
        favorites.setText("");
        dialog.dismiss();
        refreshPageStatus();
        toast("页面配置已保存");
    }

    private void refreshPageStatus() {
        if (pageStatus == null || configurePagesButton == null || deletePagesButton == null) {
            return;
        }
        String target = pageConfig.targetPageId();
        String favorites = pageConfig.favoritesPageId();
        boolean configured = !target.isEmpty() || !favorites.isEmpty();
        if (configured) {
            long savedAt = pageConfig.configSavedAt();
            String configuredAt = savedAt > 0L
                    ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
                            .format(new Date(savedAt))
                    : "旧版本配置（时间未知）";
            pageStatus.setText("同步页面：" + (target.isEmpty() ? "未配置" : "已配置")
                    + "\n收藏页面：" + (favorites.isEmpty() ? "未配置" : "已配置")
                    + "\n配置时间：" + configuredAt
                    + "\n页面 ID 不显示、不可修改");
            configurePagesButton.setVisibility(View.GONE);
            deletePagesButton.setVisibility(View.VISIBLE);
        } else {
            pageStatus.setText("同步页面：未配置\n收藏页面：未配置");
            configurePagesButton.setVisibility(View.VISIBLE);
            deletePagesButton.setVisibility(View.GONE);
        }
    }

    private void chooseExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        String date = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "x3-wolai-notes-" + date + ".json");
        startActivityForResult(intent, CREATE_BACKUP);
    }

    private void chooseImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, OPEN_BACKUP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == CREATE_BACKUP) {
            exportTo(uri);
        } else if (requestCode == OPEN_BACKUP) {
            prepareImport(uri);
        }
    }

    private void exportTo(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IllegalStateException("无法打开导出位置");
            }
            output.write(new NotesBackupManager(store).exportJson());
            output.flush();
            toast("导出完成，请妥善保管");
        } catch (Exception error) {
            toast("导出失败");
        }
    }

    private void prepareImport(Uri uri) {
        try {
            pendingImport = readLimited(uri);
            confirm("导入并合并此备份？",
                    "先完整校验，再按记录 ID 合并；不会导入授权、页面配置或在线内容。",
                    this::finishImport);
        } catch (Exception error) {
            pendingImport = null;
            toast("备份文件无效或过大");
        }
    }

    private void finishImport() {
        byte[] bytes = pendingImport;
        pendingImport = null;
        try {
            int count = new NotesBackupManager(store).importJson(bytes);
            toast("已合并 " + count + " 条本地记录");
        } catch (Exception error) {
            toast("导入失败，现有数据未清除");
        }
    }

    private byte[] readLimited(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("无法读取备份");
            }
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (output.size() + count > BackupPolicy.MAX_BACKUP_BYTES) {
                    throw new IllegalArgumentException("备份文件过大");
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认", (dialog, which) -> action.run()).show();
    }

    private EditText secureSensitiveInput(String hint) {
        EditText input = new EditText(this) {
            @Override
            public int getAutofillType() {
                return View.AUTOFILL_TYPE_NONE;
            }

            @Override
            public void autofill(AutofillValue value) {
                // Token entry is always explicit; never accept framework or password-manager fill.
            }
        };
        styleInput(input, hint, "");
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        input.setImeOptions(EditorInfo.IME_ACTION_DONE
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setPrivateImeOptions("disableLearning=true");
        input.setAutofillHints((String[]) null);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            input.setImportantForContentCapture(
                    View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        }
        input.setSaveEnabled(false);
        input.setFreezesText(false);
        return input;
    }

    private void styleInput(EditText input, String hint, String value) {
        input.setHint(hint);
        input.setHintTextColor(MUTED);
        input.setTextColor(Color.WHITE);
        input.setTextSize(13);
        input.setSingleLine(true);
        input.setText(value);
        input.setBackground(rounded(CARD, 16));
        input.setPadding(dp(10), 0, dp(10), 0);
    }

    @Override
    protected void onStop() {
        if (pageConfigDialog != null) {
            pageConfigDialog.dismiss();
            pageConfigDialog = null;
        }
        if (tokenImportDialog != null) {
            tokenImportDialog.dismiss();
            tokenImportDialog = null;
        }
        super.onStop();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setBackground(rounded(background, 20));
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

    private boolean isRoundScreen() {
        return (getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_ROUND_MASK) == Configuration.SCREENLAYOUT_ROUND_YES;
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        store.close();
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPageStatus();
        refreshTokenStatus();
    }
}
