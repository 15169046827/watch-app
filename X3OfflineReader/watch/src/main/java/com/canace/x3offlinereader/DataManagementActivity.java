package com.canace.x3offlinereader;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.canace.x3offlinereader.ui.SwipeBackActivity;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DataManagementActivity extends SwipeBackActivity {
    private static final int CREATE_BACKUP = 1201;
    private static final int OPEN_BACKUP = 1202;
    private static final int BG = Color.rgb(10, 14, 20);
    private static final int CARD = Color.rgb(31, 39, 51);
    private static final int PRIMARY = Color.rgb(137, 216, 255);
    private static final int MUTED = Color.rgb(171, 183, 199);
    private static final int WARN = Color.rgb(255, 191, 105);
    private WatchBackupManager manager;
    private byte[] pendingImport;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        manager = new WatchBackupManager(this);
        LinearLayout root = new LinearLayout(this);
        root.setId(View.generateViewId());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(34), dp(18), dp(34), dp(24));
        root.setBackgroundColor(BG);
        root.addView(text("数据与迁移", 22, Color.WHITE, true), params(-1, -2, 0, 5));
        root.addView(text("备份未加密，含书籍标识、音乐路径、进度和收藏；不含源书、音频、歌词或缓存。",
                11, WARN, false), params(-1, -2, 0, 8));
        addButton(root, "导出进度与设置", () -> chooseExport(), Color.WHITE);
        addButton(root, "导入并无损合并", () -> chooseImport(), Color.WHITE);
        addButton(root, "清除进度、收藏与设置", () -> confirm(
                "清除播放状态？", "小说和音乐目录及用户源文件都会保留。",
                manager::clearPlaybackData), WARN);
        addButton(root, "清除小说索引与缓存", () -> confirm(
                "清除小说本机数据？", "会清除目录、进度和播放缓存，但不会删除原始 .opbook 文件。",
                manager::clearNovelLibrary), WARN);
        addButton(root, "清除音乐目录", () -> confirm(
                "清除音乐本机数据？", "会清除扫描目录、隐藏记录和进度，但不会删除音乐或歌词源文件。",
                manager::clearMusicLibrary), WARN);
        addButton(root, "清除全部本机数据", () -> confirm(
                "清除全部听读本机数据？", "源 .opbook、音乐和歌词文件仍会保留；之后需重新导入或扫描。",
                manager::clearAllLocalData), WARN);
        root.addView(text("恢复前先重新导入同一 .opbook、重新扫描音乐；仅匹配项目会恢复。右滑返回。",
                10, MUTED, false), params(-1, -2, 2, 0));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        enableSwipeBack(root.getId());
    }

    private void addButton(LinearLayout root, String label, Runnable action, int color) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(color);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setBackground(rounded(CARD, 18));
        button.setOnClickListener(view -> action.run());
        root.addView(button, params(-1, dp(42), 0, 5));
    }

    private void chooseExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        String date = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
        intent.putExtra(Intent.EXTRA_TITLE, "tingdu-progress-" + date + ".json");
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
        if (requestCode == CREATE_BACKUP) {
            exportTo(data.getData());
        } else if (requestCode == OPEN_BACKUP) {
            prepareImport(data.getData());
        }
    }

    private void exportTo(Uri uri) {
        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) {
                throw new IllegalStateException("无法打开导出位置");
            }
            output.write(manager.exportJson());
            output.flush();
            toast("导出完成，请妥善保管");
        } catch (Exception error) {
            toast("导出失败");
        }
    }

    private void prepareImport(Uri uri) {
        try {
            pendingImport = readLimited(uri);
            new AlertDialog.Builder(this).setTitle("导入并合并此备份？")
                    .setMessage("文件会先完整校验；只恢复已重新导入书籍和已扫描音乐的匹配项，较旧进度不会覆盖较新进度。")
                    .setNegativeButton("取消", (dialog, which) -> pendingImport = null)
                    .setPositiveButton("确认", (dialog, which) -> finishImport()).show();
        } catch (Exception error) {
            pendingImport = null;
            toast("备份文件无效或过大");
        }
    }

    private void finishImport() {
        byte[] bytes = pendingImport;
        pendingImport = null;
        try {
            int count = manager.importJson(bytes);
            toast("已处理 " + count + " 项备份数据");
        } catch (Exception error) {
            toast("导入失败；现有数据未清除，可安全重试");
        }
    }

    private byte[] readLimited(Uri uri) throws Exception {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) {
                throw new IllegalStateException("无法读取备份");
            }
            byte[] buffer = new byte[4096];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (output.size() + count > WatchDataPolicy.MAX_BACKUP_BYTES) {
                    throw new IllegalArgumentException("备份文件过大");
                }
                if (count > 0) {
                    output.write(buffer, 0, count);
                }
            }
            return output.toByteArray();
        }
    }

    private void confirm(String title, String message, Runnable action) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认", (dialog, which) -> {
                    try {
                        action.run();
                        toast("本机数据已清除；源文件保持不变");
                    } catch (RuntimeException error) {
                        toast("清除失败");
                    }
                }).show();
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

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        manager.close();
        super.onDestroy();
    }
}
