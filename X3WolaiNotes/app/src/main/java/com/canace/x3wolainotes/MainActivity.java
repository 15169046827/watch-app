package com.canace.x3wolainotes;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int BG = Color.rgb(9, 12, 18);
    private static final int CARD = Color.rgb(25, 31, 41);
    private static final int CARD_ALT = Color.rgb(38, 47, 60);
    private static final int PRIMARY = Color.rgb(104, 232, 181);
    private static final int PRIMARY_DARK = Color.rgb(8, 54, 38);
    private static final int MUTED = Color.rgb(157, 170, 188);
    private static final int WARN = Color.rgb(255, 194, 102);

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    private NoteStore store;
    private SecretStore secretStore;
    private EditText noteInput;
    private TextView statusView;
    private LinearLayout localList;
    private Button noteModeButton;
    private Button todoModeButton;
    private Button syncButton;
    private int selectedKind = NoteItem.KIND_NOTE;
    private boolean authorized;
    private boolean destroyed;
    private String statusMessage = "准备就绪";
    private float downX;
    private float downY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        store = new NoteStore(this);
        secretStore = new SecretStore(this);
        importProvisionedToken();
        buildScreen();
        renderState();
        if (authorized) syncAll(false);
    }

    private void importProvisionedToken() {
        try {
            boolean imported = secretStore.importProvisionedToken();
            authorized = imported || secretStore.loadToken() != null;
            if (imported) statusMessage = "授权已安全保存";
        } catch (Exception error) {
            authorized = secretStore.loadToken() != null;
            statusMessage = "授权导入失败";
        }
    }

    private void buildScreen() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setBackgroundColor(BG);
        // Keep a conservative fallback inset because this customized watch build may not expose
        // Configuration.SCREENLAYOUT_ROUND even though the physical display is round.
        int side = isRoundScreen() ? dp(32) : dp(24);
        content.setPadding(side, dp(10), side, dp(18));

        content.addView(text("Wolai 速记", 24, Color.WHITE, true));
        TextView destination = text("同步到 · " + AppConfig.TARGET_PAGE_LABEL, 12, MUTED, false);
        destination.setPadding(0, 0, 0, dp(5));
        content.addView(destination);

        statusView = text("", 12, MUTED, true);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(dp(10), dp(5), dp(10), dp(5));
        content.addView(statusView, params(-1, dp(34), 0, dp(7)));

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        noteModeButton = button("笔记", PRIMARY, PRIMARY_DARK);
        todoModeButton = button("待办", CARD_ALT, Color.WHITE);
        noteModeButton.setOnClickListener(v -> selectKind(NoteItem.KIND_NOTE));
        todoModeButton.setOnClickListener(v -> selectKind(NoteItem.KIND_TODO));
        modes.addView(noteModeButton, weightedParams(dp(38), 0, dp(3)));
        modes.addView(todoModeButton, weightedParams(dp(38), dp(3), 0));
        content.addView(modes, params(-1, dp(38), 0, dp(5)));

        noteInput = new EditText(this);
        noteInput.setHint("记下一句话…");
        noteInput.setHintTextColor(MUTED);
        noteInput.setTextColor(Color.WHITE);
        noteInput.setTextSize(16);
        noteInput.setGravity(Gravity.CENTER);
        noteInput.setSingleLine(true);
        noteInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        noteInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        noteInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(AppConfig.MAX_NOTE_LENGTH)});
        noteInput.setBackground(rounded(CARD, 18));
        noteInput.setPadding(dp(12), 0, dp(12), 0);
        noteInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                saveNote();
                return true;
            }
            return false;
        });
        content.addView(noteInput, params(-1, dp(56), 0, dp(5)));

        Button saveButton = button("保存并同步", PRIMARY, PRIMARY_DARK);
        saveButton.setOnClickListener(v -> saveNote());
        content.addView(saveButton, params(-1, dp(48), 0, dp(4)));

        syncButton = button("刷新 · 重试", CARD_ALT, Color.WHITE);
        syncButton.setOnClickListener(v -> syncAll(true));
        content.addView(syncButton, params(-1, dp(40), 0, dp(7)));

        Button favoritesButton = button("查看收藏 · 在线", CARD_ALT, Color.WHITE);
        favoritesButton.setOnClickListener(v -> startActivity(new Intent(this, ReaderActivity.class)));
        content.addView(favoritesButton, params(-1, dp(44), 0, dp(7)));

        content.addView(sectionTitle("本地记录"));
        localList = new LinearLayout(this);
        localList.setOrientation(LinearLayout.VERTICAL);
        content.addView(localList, params(-1, -2, 0, dp(5)));

        TextView hint = text("右滑退出 · 点输入框可用输入法语音", 11, MUTED, false);
        hint.setPadding(0, dp(5), 0, 0);
        content.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void selectKind(int kind) {
        selectedKind = kind;
        noteModeButton.setBackground(rounded(kind == NoteItem.KIND_NOTE ? PRIMARY : CARD_ALT, 18));
        noteModeButton.setTextColor(kind == NoteItem.KIND_NOTE ? PRIMARY_DARK : Color.WHITE);
        todoModeButton.setBackground(rounded(kind == NoteItem.KIND_TODO ? PRIMARY : CARD_ALT, 18));
        todoModeButton.setTextColor(kind == NoteItem.KIND_TODO ? PRIMARY_DARK : Color.WHITE);
        noteInput.setHint(kind == NoteItem.KIND_TODO ? "添加一项待办…" : "记下一句话…");
    }

    private void saveNote() {
        String value = NoteFormatter.normalize(noteInput.getText().toString());
        if (value.isEmpty()) {
            noteInput.setError("请输入内容");
            return;
        }
        hideKeyboard();
        NoteItem item = new NoteItem(UUID.randomUUID().toString(), value, selectedKind,
                System.currentTimeMillis(), NoteItem.STATE_PENDING, "");
        try {
            store.insertPending(item);
            noteInput.setText("");
            statusMessage = "已保存，等待同步";
            renderState();
            syncAll(true);
        } catch (RuntimeException error) {
            Toast.makeText(this, "本地保存失败", Toast.LENGTH_LONG).show();
        }
    }

    private void syncAll(boolean notify) {
        if (!syncing.compareAndSet(false, true)) return;
        renderState();
        executor.execute(() -> {
            String finalMessage;
            try {
                if (!AppConfig.hasTargetPage()) {
                    finalMessage = "未配置同步页面 · 本地可用";
                } else {
                    String token = secretStore.loadToken();
                    if (token == null || token.trim().isEmpty()) {
                    authorized = false;
                    finalMessage = "等待安装时配置授权";
                    } else {
                        authorized = true;
                        WolaiClient client = new WolaiClient(token);
                        List<NoteItem> pending = store.pending(20);
                        List<SyncReconciler.Candidate> remoteCandidates = null;
                        Set<String> consumedRemoteIds = new HashSet<>();
                        String syncMessage = "";
                        for (NoteItem item : pending) {
                            String expected = NoteFormatter.forWolai(item.kind, item.text);
                            long now = System.currentTimeMillis();
                            if (item.state == NoteItem.STATE_UNCERTAIN) {
                                try {
                                    if (remoteCandidates == null) {
                                        remoteCandidates = toCandidates(
                                                client.fetchChildren(AppConfig.TARGET_PAGE_ID));
                                    }
                                    String match = SyncReconciler.findMatch(item, expected,
                                            remoteCandidates, consumedRemoteIds, now);
                                    if (!match.isEmpty()) {
                                        consumedRemoteIds.add(match);
                                        store.markSynced(item.id);
                                        continue;
                                    }
                                } catch (WolaiClient.ApiException error) {
                                    syncMessage = UserErrorMessages.syncStatus(
                                            WolaiClient.statusOf(error));
                                    break;
                                }
                                if (SyncReconciler.shouldWaitBeforeRetry(item, now)) {
                                    syncMessage = "写入结果待确认 · 稍后自动核对";
                                    break;
                                }
                            }
                            try {
                                store.markAttempting(item.id, now);
                                client.createTextBlock(AppConfig.TARGET_PAGE_ID, expected);
                                store.markSynced(item.id);
                            } catch (WolaiClient.ApiException error) {
                                if (error.isOutcomeUnknown()) {
                                    store.markUncertain(item.id, error.getMessage());
                                    syncMessage = "写入结果待确认 · 稍后自动核对";
                                } else {
                                    store.markFailed(item.id, error.getMessage());
                                    syncMessage = UserErrorMessages.syncStatus(
                                            WolaiClient.statusOf(error));
                                }
                                break;
                            }
                        }
                        int uncertain = store.uncertainCount();
                        int remaining = store.pendingCount();
                        if (!syncMessage.isEmpty()) {
                            finalMessage = syncMessage;
                        } else if (uncertain > 0) {
                            finalMessage = uncertain + " 条写入结果待确认";
                        } else {
                            finalMessage = remaining == 0 ? "同步完成" : remaining + " 条等待重试";
                        }
                    }
                }
            } catch (Exception error) {
                finalMessage = UserErrorMessages.syncStatus(WolaiClient.statusOf(error));
            }
            syncing.set(false);
            final String result = finalMessage;
            runOnUiThread(() -> {
                if (destroyed) return;
                statusMessage = result;
                renderState();
                if (notify) Toast.makeText(this, result, Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void renderState() {
        if (statusView == null) return;
        int pending = store.pendingCount();
        String label;
        int color;
        if (syncing.get()) {
            label = "正在同步…";
            color = PRIMARY;
        } else if (!AppConfig.hasTargetPage()) {
            label = "未配置同步页面 · 本地可用";
            color = WARN;
        } else if (!authorized) {
            label = "未配置授权 · 本地可用";
            color = WARN;
        } else if (pending > 0) {
            label = pending + " 条待同步 · " + statusMessage;
            color = WARN;
        } else {
            label = statusMessage;
            color = PRIMARY;
        }
        statusView.setText(label);
        statusView.setTextColor(color);
        statusView.setBackground(rounded(CARD, 16));
        syncButton.setEnabled(!syncing.get());
        syncButton.setAlpha(syncing.get() ? 0.55f : 1f);
        renderLocal();
    }

    private void renderLocal() {
        localList.removeAllViews();
        List<NoteItem> items = store.recentLocal(3);
        if (items.isEmpty()) {
            localList.addView(emptyRow("还没有本地记录"));
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        for (NoteItem item : items) {
            String state = item.state == NoteItem.STATE_SYNCED ? "已同步" :
                    item.state == NoteItem.STATE_FAILED ? "待重试" :
                            item.state == NoteItem.STATE_UNCERTAIN ? "待确认" : "待同步";
            String prefix = item.kind == NoteItem.KIND_TODO ? "☐ " : "";
            addCard(localList, prefix + item.text,
                    format.format(new Date(item.createdAt)) + " · " + state,
                    item.state == NoteItem.STATE_SYNCED ? MUTED : WARN);
        }
    }

    private void addCard(LinearLayout parent, String title, String meta, int metaColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(9), dp(5), dp(9), dp(5));
        card.setBackground(rounded(CARD, 13));

        TextView titleView = text(title, 13, Color.WHITE, false);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(titleView, params(-1, -2, 0, 0));
        TextView metaView = text(meta, 10, metaColor, false);
        card.addView(metaView, params(-1, -2, 0, 0));
        parent.addView(card, params(-1, -2, 0, dp(3)));
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 12, MUTED, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, dp(2), 0, dp(3));
        return view;
    }

    private TextView emptyRow(String value) {
        TextView view = text(value, 12, MUTED, false);
        view.setBackground(rounded(CARD, 13));
        view.setPadding(dp(8), dp(7), dp(8), dp(7));
        return view;
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

    private Button button(String label, int background, int foreground) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(foreground);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), 0, dp(8), 0);
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

    private LinearLayout.LayoutParams weightedParams(int height, int left, int right) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(0, height, 1f);
        result.setMargins(dp(left), 0, dp(right), 0);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isRoundScreen() {
        return (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_ROUND_MASK)
                == Configuration.SCREENLAYOUT_ROUND_YES;
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) return;
        InputMethodManager manager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (manager != null) manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        focus.clearFocus();
    }

    private static List<SyncReconciler.Candidate> toCandidates(
            List<WolaiClient.RemoteBlock> blocks) {
        List<SyncReconciler.Candidate> result = new ArrayList<>();
        for (WolaiClient.RemoteBlock block : blocks) {
            if (!"text".equals(block.type)) continue;
            result.add(new SyncReconciler.Candidate(block.id, block.text, block.createdAt));
        }
        return result;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float dx = event.getX() - downX;
            float dy = Math.abs(event.getY() - downY);
            if (dx > getResources().getDisplayMetrics().widthPixels * 0.26f && dy < dx * 0.65f) {
                hideKeyboard();
                finish();
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        store.close();
        super.onDestroy();
    }
}
