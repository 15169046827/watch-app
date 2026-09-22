package com.canace.x3voiceledger.phone;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PhoneMainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 71;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 72;
    private static final int IMAGE_PERMISSION_REQUEST = 73;
    private static final int PROFILE_FILE_REQUEST = 74;
    private static final int BACKUP_EXPORT_REQUEST = 75;
    private static final int BACKUP_IMPORT_REQUEST = 76;
    private static final int BG = Color.rgb(246, 248, 247);
    private static final int INK = Color.rgb(19, 37, 30);
    private static final int MUTED = Color.rgb(102, 119, 112);
    private static final int PRIMARY = Color.rgb(116, 174, 154);
    private static final int PRIMARY_DARK = Color.rgb(57, 92, 80);
    private static final int PALE_MINT = Color.rgb(235, 244, 240);
    private static final int LINE = Color.rgb(225, 232, 228);
    private static final int DANGER = Color.rgb(196, 74, 83);
    private PhoneLedgerStore store;
    private CategoryRuleStore ruleStore;
    private LedgerProfileStore profileStore;
    private LinearLayout content;
    private final Set<String> selectedIds = new LinkedHashSet<>();
    private final ArrayDeque<ImportItem> batchQueue = new ArrayDeque<>();
    private final ArrayDeque<PhoneLedgerStore.Entry> resolutionQueue = new ArrayDeque<>();
    private final List<ImportItem> resolvedBatch = new ArrayList<>();
    private boolean batchAwaitingReturn;
    private boolean batchPaused;
    private TextView selectionStatus;
    private final ExecutorService profileImportExecutor =
            Executors.newSingleThreadExecutor(
                    runnable -> new Thread(runnable, "profile-import"));
    private final ProfileImportCompletionGate profileImportGate =
            new ProfileImportCompletionGate();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new PhoneLedgerStore(this);
        ruleStore = new CategoryRuleStore(this);
        profileStore = new LedgerProfileStore(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        render();
        ensurePermissionsAndSync();
        handleProfileIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleProfileIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store == null) {
            return;
        }
        boolean continueBatch = batchAwaitingReturn && batchPaused;
        batchAwaitingReturn = false;
        batchPaused = false;
        render();
        if (continueBatch) {
            getWindow().getDecorView().postDelayed(this::launchNextBatch, 350L);
        }
    }

    @Override
    protected void onPause() {
        if (batchAwaitingReturn) {
            batchPaused = true;
        }
        super.onPause();
    }

    private void render() {
        List<PhoneLedgerStore.Entry> pending =
                store.recentByState(PhoneLedgerStore.STATE_PENDING, 100);
        List<PhoneLedgerStore.Entry> archived =
                store.recentByState(PhoneLedgerStore.STATE_ARCHIVED, 100);
        Set<String> pendingIds = new LinkedHashSet<>();
        for (PhoneLedgerStore.Entry entry : pending) {
            pendingIds.add(entry.id);
        }
        selectedIds.retainAll(pendingIds);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(20), dp(18), dp(32));
        content.setBackgroundColor(BG);

        LinearLayout hero = panel(22, PRIMARY_DARK);
        hero.setBackground(gradient(Color.rgb(50, 74, 66), Color.rgb(103, 147, 131), 22));
        TextView eyebrow = styledText("X3 · POCKET LEDGER", 12, true, Color.rgb(216, 235, 227));
        eyebrow.setLetterSpacing(0.08f);
        hero.addView(eyebrow);
        TextView title = styledText("轻松记，随时同步", 28, true, Color.WHITE);
        title.setPadding(0, dp(5), 0, dp(4));
        hero.addView(title);
        hero.addView(styledText("手表负责捕捉每一笔，手机负责把账单整理好。", 14, false, Color.rgb(231, 240, 236)));
        TextView stats =
                styledText(
                        "待处理  " + pending.size() + "    ·    已归档  " + archived.size(),
                        15,
                        true,
                        Color.WHITE);
        stats.setGravity(Gravity.CENTER);
        stats.setBackground(rounded(Color.argb(38, 255, 255, 255), 14, 0, 0));
        stats.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams statsParams = new LinearLayout.LayoutParams(-1, -2);
        statsParams.setMargins(0, dp(16), 0, 0);
        hero.addView(stats, statsParams);
        content.addView(hero, blockParams(0, 18));

        content.addView(sectionHeading("同步与配置", "把账单和一木配置保持在一起"));
        LinearLayout tools = panel(20, Color.WHITE);
        Button sync = actionButton("立即同步手表账单", true, false);
        sync.setOnClickListener(v -> ensurePermissionsAndSync());
        tools.addView(sync, buttonParams(52, 0, 8));

        Button classificationMode =
                actionButton("分类确认方式  ·  " + modeLabel(ruleStore.mode()), false, false);
        classificationMode.setOnClickListener(v -> showModeDialog());
        tools.addView(classificationMode, buttonParams(48, 0, 8));

        Button categoryRules =
                actionButton("已学习分类规则  ·  " + ruleStore.ruleCount() + " 条", false, false);
        categoryRules.setOnClickListener(v -> showCategoryRulesDialog());
        tools.addView(categoryRules, buttonParams(48, 0, 8));

        Button profile = actionButton("账务配置  ·  " + profileStore.summary(), false, false);
        profile.setOnClickListener(v -> showProfileDialog());
        tools.addView(profile, buttonParams(54, 0, 8));

        Button importProfile = actionButton("从一木导出文件更新配置", false, false);
        importProfile.setOnClickListener(v -> chooseProfileFile());
        tools.addView(importProfile, buttonParams(48, 0, 8));

        Button screenshot = actionButton("", false, false);
        screenshot.setText(
                WatchScreenshotMonitor.hasImagePermission(this)
                        ? "识别已传到手机的手表付款截图"
                        : "开启已连接场景的截图记账");
        screenshot.setOnClickListener(v -> enableScreenshotImport());
        tools.addView(screenshot, buttonParams(48, 0, 8));

        Button data = actionButton("数据迁移、导出与清除", false, false);
        data.setOnClickListener(v -> showDataManagementDialog());
        tools.addView(data, buttonParams(48, 0, 0));
        content.addView(tools, blockParams(0, 18));

        if (!pending.isEmpty()) {
            addBatchControls(pending);
        }
        addSection("待导入", pending, false);
        addSection("已归档", archived, true);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
    }

    private void addBatchControls(List<PhoneLedgerStore.Entry> pending) {
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(12), dp(12), dp(12), dp(10));
        actions.setBackground(rounded(PALE_MINT, 18, 0, 0));
        Button selectAll = actionButton("全选", false, false);
        selectAll.setOnClickListener(
                v -> {
                    selectedIds.clear();
                    for (PhoneLedgerStore.Entry entry : pending) {
                        selectedIds.add(entry.id);
                    }
                    render();
                });
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, dp(44), 1);
        half.setMargins(0, 0, dp(5), 0);
        actions.addView(selectAll, half);
        Button batch = actionButton("批量导入", true, false);
        batch.setOnClickListener(v -> confirmBatchImport());
        LinearLayout.LayoutParams otherHalf = new LinearLayout.LayoutParams(0, dp(44), 1);
        otherHalf.setMargins(dp(5), 0, 0, 0);
        actions.addView(batch, otherHalf);
        content.addView(actions);
        selectionStatus = styledText("已选择 " + selectedIds.size() + " 笔", 13, false, MUTED);
        selectionStatus.setPadding(dp(12), dp(5), 0, 0);
        content.addView(selectionStatus);
    }

    private void addSection(String title, List<PhoneLedgerStore.Entry> entries, boolean archived) {
        content.addView(sectionHeading(title, entries.size() + " 笔"));
        if (entries.isEmpty()) {
            TextView empty = styledText(archived ? "还没有归档账单" : "待处理区空空的，真不错", 14, false, MUTED);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(16), dp(22), dp(16), dp(22));
            empty.setBackground(rounded(Color.WHITE, 18, LINE, 1));
            content.addView(empty, blockParams(0, 4));
            return;
        }
        for (PhoneLedgerStore.Entry entry : entries) {
            addCard(entry, archived);
        }
    }

    private void addCard(PhoneLedgerStore.Entry entry, boolean archived) {
        LinearLayout card = createLedgerCard();
        addHeadline(card, entry);
        addMetadata(card, entry);
        addSourceBadge(card, entry.source);
        addArchivedMapping(card, entry.archivedMapping());
        if (!archived) {
            addSelectionControl(card, entry.id);
        }
        addCardActions(card, entry, archived);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.setMargins(0, dp(6), 0, dp(6));
        content.addView(card, cardParams);
    }

    private LinearLayout createLedgerCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(14));
        card.setBackground(rounded(Color.WHITE, 20, LINE, 1));
        card.setElevation(dp(1));
        return card;
    }

    private void addHeadline(LinearLayout card, PhoneLedgerStore.Entry entry) {
        LinearLayout headline = new LinearLayout(this);
        headline.setOrientation(LinearLayout.HORIZONTAL);
        headline.setGravity(Gravity.CENTER_VERTICAL);
        TextView note = styledText(entry.note, 17, true, INK);
        note.setMaxLines(2);
        headline.addView(note, new LinearLayout.LayoutParams(0, -2, 1));
        TextView amount =
                styledText(
                        "¥" + String.format(Locale.CHINA, "%.2f", entry.amountCents / 100d),
                        20,
                        true,
                        PRIMARY_DARK);
        amount.setGravity(Gravity.END);
        headline.addView(amount, new LinearLayout.LayoutParams(-2, -2));
        card.addView(headline);
    }

    private void addMetadata(LinearLayout card, PhoneLedgerStore.Entry entry) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        String metadata =
                format.format(new Date(entry.transactionTime))
                        + "  ·  "
                        + entry.category
                        + (entry.account.isEmpty() ? "  ·  账户待确认" : "  ·  " + entry.account)
                        + (entry.locationLabel.isEmpty() ? "" : "\n" + entry.locationLabel);
        TextView meta = styledText(metadata, 13, false, MUTED);
        meta.setPadding(0, dp(8), 0, dp(10));
        card.addView(meta);
    }

    private void addSourceBadge(LinearLayout card, String source) {
        boolean automatic = "alipay_nfc_accessibility".equals(source);
        TextView badge =
                styledText(sourceLabel(source), 12, true, automatic ? Color.rgb(139, 103, 78) : PRIMARY_DARK);
        badge.setBackground(rounded(automatic ? Color.rgb(247, 239, 232) : PALE_MINT, 11, 0, 0));
        badge.setPadding(dp(9), dp(5), dp(9), dp(5));
        card.addView(badge, new LinearLayout.LayoutParams(-2, -2));
    }

    private static String sourceLabel(String source) {
        if ("watch_screenshot".equals(source)) {
            return "手表截图识别";
        }
        return "alipay_nfc_accessibility".equals(source) ? "支付宝自动识别 · 待核对" : "手表语音记账";
    }

    private void addArchivedMapping(
            LinearLayout card, YimuCategoryMapper.Mapping archivedMapping) {
        if (archivedMapping == null) {
            return;
        }
        TextView yimu = styledText("已按「" + typeAndPath(archivedMapping) + "」归档", 12, false, MUTED);
        yimu.setPadding(0, dp(8), 0, 0);
        card.addView(yimu);
    }

    private void addSelectionControl(LinearLayout card, String entryId) {
        CheckBox check = new CheckBox(this);
        check.setText("加入批量导入");
        check.setTextColor(MUTED);
        check.setChecked(selectedIds.contains(entryId));
        check.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (checked) {
                        selectedIds.add(entryId);
                    } else {
                        selectedIds.remove(entryId);
                    }
                    if (selectionStatus != null) {
                        selectionStatus.setText("已选择 " + selectedIds.size() + " 笔");
                    }
                });
        card.addView(check);
    }

    private void addCardActions(
            LinearLayout card, PhoneLedgerStore.Entry entry, boolean archived) {
        Button detailsButton = actionButton("调整账单信息", false, false);
        detailsButton.setOnClickListener(v -> showDetailsDialog(entry));
        card.addView(detailsButton, buttonParams(44, 4, 8));
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button importButton = actionButton(archived ? "重新导入" : "导入一木", true, false);
        importButton.setOnClickListener(v -> importEntry(entry, archived));
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        importParams.setMargins(0, 0, dp(5), 0);
        actions.addView(importButton, importParams);
        Button deleteButton = actionButton("删除", false, true);
        deleteButton.setOnClickListener(v -> confirmDelete(entry));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(46), 1);
        deleteParams.setMargins(dp(5), 0, 0, 0);
        actions.addView(deleteButton, deleteParams);
        card.addView(actions);
    }

    private void importEntry(PhoneLedgerStore.Entry entry, boolean archived) {
        if (archived) {
            confirmReimport(entry);
        } else {
            confirmImport(entry);
        }
    }
    private void confirmImport(PhoneLedgerStore.Entry entry) {
        resolveMapping(entry, mapping -> confirmLaunch(entry, mapping, false));
    }

    private void confirmReimport(PhoneLedgerStore.Entry entry) {
        YimuCategoryMapper.Mapping saved = entry.archivedMapping();
        if (saved == null) {
            saved = YimuCategoryMapper.map(entry.category, entry.note);
        }
        YimuCategoryMapper.Mapping mapping = saved;
        new AlertDialog.Builder(this)
                .setTitle("重新导入这笔账单？")
                .setMessage(
                        summary(entry)
                                + "\n分类："
                                + typeAndPath(mapping)
                                + "\n如果一木中已有该账单，重新导入会产生重复记录。")
                .setNegativeButton("取消", null)
                .setNeutralButton(
                        "更改分类",
                        (dialog, which) ->
                                showMappingDialog(
                                        entry, selected -> confirmLaunch(entry, selected, true)))
                .setPositiveButton(
                        "仍然导入",
                        (dialog, which) -> {
                            if (YimuImporter.launch(this, entry, mapping, profileStore)) {
                                store.markArchived(entry.id, mapping);
                            }
                        })
                .show();
    }

    private void confirmBatchImport() {
        List<PhoneLedgerStore.Entry> entries = selectedPendingEntries();
        if (entries.isEmpty()) {
            Toast.makeText(this, "请先选择待导入账单", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("批量导入 " + entries.size() + " 笔？")
                .setMessage("将逐笔打开一木。每次返回伴侣后，会继续下一笔并自动归档。")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "开始",
                        (dialog, which) -> {
                            resolutionQueue.clear();
                            resolutionQueue.addAll(entries);
                            resolvedBatch.clear();
                            resolveNextBatchEntry();
                        })
                .show();
    }

    private List<PhoneLedgerStore.Entry> selectedPendingEntries() {
        List<PhoneLedgerStore.Entry> result = new ArrayList<>();
        for (String id : selectedIds) {
            PhoneLedgerStore.Entry entry = store.find(id);
            if (entry != null && entry.yimuState == PhoneLedgerStore.STATE_PENDING) {
                result.add(entry);
            }
        }
        return result;
    }

    private void launchNextBatch() {
        ImportItem item = batchQueue.pollFirst();
        if (item == null) {
            render();
            Toast.makeText(this, "批量导入已全部发起并归档", Toast.LENGTH_LONG).show();
            return;
        }
        batchAwaitingReturn = true;
        batchPaused = false;
        if (YimuImporter.launch(this, item.entry, item.mapping, profileStore)) {
            store.markArchived(item.entry.id, item.mapping);
        } else {
            batchAwaitingReturn = false;
            batchQueue.clear();
            render();
        }
    }

    private void confirmLaunch(
            PhoneLedgerStore.Entry entry, YimuCategoryMapper.Mapping mapping, boolean reimport) {
        String warning = reimport ? "\n这可能在一木中产生重复账单。" : "\n成功打开一木后将移入已归档。";
        new AlertDialog.Builder(this)
                .setTitle(reimport ? "按新分类重新导入？" : "确认导入一木")
                .setMessage(summary(entry) + "\n分类：" + typeAndPath(mapping) + warning)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (dialog, which) -> launchAndArchive(entry, mapping))
                .show();
    }

    private void launchAndArchive(
            PhoneLedgerStore.Entry entry, YimuCategoryMapper.Mapping mapping) {
        if (YimuImporter.launch(this, entry, mapping, profileStore)) {
            store.markArchived(entry.id, mapping);
        }
    }

    private void resolveMapping(PhoneLedgerStore.Entry entry, MappingCallback callback) {
        YimuCategoryMapper.Mapping learned = ruleStore.find(entry);
        YimuCategoryMapper.Mapping watchSuggested = entry.watchSuggestedMapping();
        int mode = ruleStore.mode();
        if (mode == CategoryRuleStore.MODE_AUTO) {
            callback.onSelected(
                    learned == null
                            ? (watchSuggested == null
                                    ? YimuCategoryMapper.map(entry.category, entry.note)
                                    : watchSuggested)
                            : learned);
        } else if (mode == CategoryRuleStore.MODE_SMART && learned != null) {
            callback.onSelected(learned);
        } else {
            showMappingDialog(entry, callback);
        }
    }

    private void showMappingDialog(PhoneLedgerStore.Entry entry, MappingCallback callback) {
        List<YimuCategoryMapper.Mapping> choices = mappingChoices(entry);
        String[] labels = mappingLabels(choices);
        int[] selected = new int[] {0};
        new AlertDialog.Builder(this)
                .setTitle("这笔账单匹配哪些分类？")
                .setMessage(summary(entry))
                .setSingleChoiceItems(labels, 0, (dialog, which) -> selected[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "下一步",
                        (dialog, which) ->
                                handleMappingChoice(entry, callback, choices, selected[0]))
                .show();
    }

    private List<YimuCategoryMapper.Mapping> mappingChoices(PhoneLedgerStore.Entry entry) {
        List<YimuCategoryMapper.Mapping> choices = new ArrayList<>();
        YimuCategoryMapper.Mapping learned = ruleStore.find(entry);
        addMappingChoice(choices, learned);
        if (learned == null) {
            addMappingChoice(choices, entry.watchSuggestedMapping());
        }
        for (YimuCategoryMapper.Mapping suggestion
                : YimuCategoryMapper.suggestions(entry.category, entry.note)) {
            addMappingChoice(choices, suggestion);
        }
        return choices;
    }

    private static void addMappingChoice(
            List<YimuCategoryMapper.Mapping> choices,
            YimuCategoryMapper.Mapping suggestion) {
        if (suggestion == null || choices.size() >= 3 || containsMapping(choices, suggestion)) {
            return;
        }
        choices.add(suggestion);
    }

    private static boolean containsMapping(
            List<YimuCategoryMapper.Mapping> choices,
            YimuCategoryMapper.Mapping suggestion) {
        for (YimuCategoryMapper.Mapping value : choices) {
            if (value.parent.equals(suggestion.parent) && value.child.equals(suggestion.child)) {
                return true;
            }
        }
        return false;
    }

    private String[] mappingLabels(List<YimuCategoryMapper.Mapping> choices) {
        String[] labels = new String[choices.size() + 2];
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = typeAndPath(choices.get(i));
        }
        labels[choices.size()] = "浏览全部现有分类…";
        labels[choices.size() + 1] = "新增分类…";
        return labels;
    }

    private void handleMappingChoice(
            PhoneLedgerStore.Entry entry,
            MappingCallback callback,
            List<YimuCategoryMapper.Mapping> choices,
            int selected) {
        if (selected < choices.size()) {
            YimuCategoryMapper.Mapping mapping = choices.get(selected);
            ruleStore.remember(entry, mapping);
            callback.onSelected(mapping);
        } else if (selected == choices.size()) {
            showParentChooser(entry, callback);
        } else {
            showNewCategoryDialog(entry, callback);
        }
    }
    private void showParentChooser(PhoneLedgerStore.Entry entry, MappingCallback callback) {
        List<YimuCategoryMapper.Parent> parents = profileStore.categories();
        String[] labels = new String[parents.size()];
        for (int i = 0; i < parents.size(); i++) {
            labels[i] =
                    ("收入".equals(parents.get(i).name) ? "收入 · " : "支出 · ") + parents.get(i).name;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择父分类")
                .setItems(
                        labels,
                        (dialog, which) -> showChildChooser(entry, parents.get(which), callback))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showChildChooser(
            PhoneLedgerStore.Entry entry,
            YimuCategoryMapper.Parent parent,
            MappingCallback callback) {
        String[] labels = new String[parent.children.size() + 1];
        labels[0] = "仅使用父分类“" + parent.name + "”";
        for (int i = 0; i < parent.children.size(); i++) {
            labels[i + 1] = parent.children.get(i);
        }
        new AlertDialog.Builder(this)
                .setTitle(parent.name + "的子分类")
                .setItems(
                        labels,
                        (dialog, which) -> {
                            String child = which == 0 ? "" : parent.children.get(which - 1);
                            YimuCategoryMapper.Mapping mapping =
                                    new YimuCategoryMapper.Mapping(parent.name, child);
                            ruleStore.remember(entry, mapping);
                            callback.onSelected(mapping);
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showNewCategoryDialog(PhoneLedgerStore.Entry entry, MappingCallback callback) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(24), 0, dp(24), 0);
        EditText parent = new EditText(this);
        parent.setHint("父分类，例如 食品餐饮");
        parent.setSingleLine(true);
        parent.setInputType(InputType.TYPE_CLASS_TEXT);
        EditText child = new EditText(this);
        child.setHint("子分类（可留空）");
        child.setSingleLine(true);
        child.setInputType(InputType.TYPE_CLASS_TEXT);
        fields.addView(parent);
        fields.addView(child);
        new AlertDialog.Builder(this)
                .setTitle("新增一木分类")
                .setMessage("只有现有分类确实无法匹配时才建议新增。")
                .setView(fields)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "下一步",
                        (dialog, which) -> {
                            String parentName = parent.getText().toString().trim();
                            String childName = child.getText().toString().trim();
                            if (parentName.isEmpty()) {
                                Toast.makeText(this, "父分类不能为空", Toast.LENGTH_LONG).show();
                                return;
                            }
                            YimuCategoryMapper.Mapping mapping =
                                    new YimuCategoryMapper.Mapping(parentName, childName);
                            confirmNewCategory(entry, mapping, callback);
                        })
                .show();
    }

    private void confirmNewCategory(
            PhoneLedgerStore.Entry entry,
            YimuCategoryMapper.Mapping mapping,
            MappingCallback callback) {
        new AlertDialog.Builder(this)
                .setTitle("确认新增分类？")
                .setMessage("导入账单时，一木可能永久创建：\n" + typeAndPath(mapping))
                .setNegativeButton("返回", null)
                .setPositiveButton(
                        "确认新增",
                        (dialog, which) -> {
                            ruleStore.remember(entry, mapping);
                            callback.onSelected(mapping);
                        })
                .show();
    }

    private void resolveNextBatchEntry() {
        PhoneLedgerStore.Entry entry = resolutionQueue.pollFirst();
        if (entry == null) {
            batchQueue.clear();
            batchQueue.addAll(resolvedBatch);
            selectedIds.clear();
            new AlertDialog.Builder(this)
                    .setTitle("分类确认完成")
                    .setMessage("共 " + resolvedBatch.size() + " 笔，将逐笔打开一木并归档。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("开始导入", (dialog, which) -> launchNextBatch())
                    .show();
            return;
        }
        resolveMapping(
                entry,
                mapping -> {
                    resolvedBatch.add(new ImportItem(entry, mapping));
                    resolveNextBatchEntry();
                });
    }

    private void showModeDialog() {
        String[] modes = new String[] {"智能确认（首次或不确定时询问）", "每笔确认", "自动分类"};
        int[] selected = new int[] {ruleStore.mode()};
        new AlertDialog.Builder(this)
                .setTitle("分类确认方式")
                .setSingleChoiceItems(modes, selected[0], (dialog, which) -> selected[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "保存",
                        (dialog, which) -> {
                            ruleStore.setMode(selected[0]);
                            render();
                        })
                .show();
    }

    private static String modeLabel(int mode) {
        if (mode == CategoryRuleStore.MODE_ALWAYS) {
            return "每笔确认";
        }
        if (mode == CategoryRuleStore.MODE_AUTO) {
            return "自动分类";
        }
        return "智能确认";
    }

    private void showCategoryRulesDialog() {
        List<CategoryRuleStore.Rule> rules = ruleStore.rules();
        if (rules.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("已学习分类规则")
                    .setMessage("还没有学习规则。你在分类确认时作出的选择会显示在这里。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        String[] labels = new String[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            labels[i] = categoryRuleLabel(rules.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("已学习分类规则（" + rules.size() + " 条）")
                .setItems(labels, (dialog, which) -> confirmDeleteCategoryRule(rules.get(which)))
                .setNeutralButton("清空全部", (dialog, which) -> confirmClearCategoryRules())
                .setPositiveButton("关闭", null)
                .show();
    }

    private String categoryRuleLabel(CategoryRuleStore.Rule rule) {
        String category = rule.sourceCategory.isEmpty() ? "未分类" : rule.sourceCategory;
        String source = rule.sourceText.isEmpty() ? "空白说明" : rule.sourceText;
        if (source.length() > 22) {
            source = source.substring(0, 22) + "…";
        }
        return category + " · " + source + "\n→ " + typeAndPath(rule.mapping);
    }

    private void confirmDeleteCategoryRule(CategoryRuleStore.Rule rule) {
        new AlertDialog.Builder(this)
                .setTitle("删除这条学习规则？")
                .setMessage(categoryRuleLabel(rule))
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "删除",
                        (dialog, which) -> {
                            if (ruleStore.delete(rule.storageKey)) {
                                Toast.makeText(this, "已删除学习规则", Toast.LENGTH_SHORT).show();
                                render();
                            }
                        })
                .show();
    }

    private void confirmClearCategoryRules() {
        new AlertDialog.Builder(this)
                .setTitle("清空全部学习规则？")
                .setMessage("分类确认方式和一木分类目录不会改变；之后会重新学习你的选择。")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "清空",
                        (dialog, which) -> {
                            int count = ruleStore.clearRules();
                            Toast.makeText(this, "已清空 " + count + " 条规则", Toast.LENGTH_SHORT)
                                    .show();
                            render();
                        })
                .show();
    }

    private static String typeAndPath(YimuCategoryMapper.Mapping mapping) {
        return (mapping.isIncome() ? "收入 · " : "支出 · ") + mapping.path();
    }

    private void confirmDelete(PhoneLedgerStore.Entry entry) {
        new AlertDialog.Builder(this)
                .setTitle("删除伴侣中的记录？")
                .setMessage("不会删除手表或一木中的副本。")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "删除",
                        (dialog, which) -> {
                            selectedIds.remove(entry.id);
                            if (store.delete(entry.id)) {
                                Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                            }
                            render();
                        })
                .show();
    }

    private String summary(PhoneLedgerStore.Entry entry) {
        String account = entry.account.isEmpty() ? profileStore.defaultAccount() : entry.account;
        String book = entry.bookName.isEmpty() ? profileStore.bookName() : entry.bookName;
        return entry.note
                + "  ¥"
                + String.format(Locale.CHINA, "%.2f", entry.amountCents / 100d)
                + (account.isEmpty() ? "\n账户：待确认" : "\n账户：" + account)
                + (book.isEmpty() ? "" : " · 账本：" + book);
    }

    private void showProfileDialog() {
        LinearLayout fields = dialogFields();
        EditText accounts = dialogEditor("账户名称，每行一个", profileStore.accountsText());
        accounts.setSingleLine(false);
        accounts.setMinLines(3);
        addLabeledEditor(fields, "可用账户", "每行一个；名称必须与一木一致", accounts);
        Button defaultAccount = valueButton(profileStore.defaultAccount());
        defaultAccount.setOnClickListener(
                v ->
                        showStringChooser(
                                "选择默认账户",
                                splitValues(accounts.getText().toString()),
                                buttonValue(defaultAccount),
                                false,
                                value -> setButtonValue(defaultAccount, value)));
        addLabeledButton(fields, "默认账户", "账单没有指定账户时使用", defaultAccount);

        EditText books = dialogEditor("账本名称，每行一个", profileStore.booksText());
        books.setSingleLine(false);
        books.setMinLines(2);
        addLabeledEditor(fields, "可用账本", "每行一个；这里是账本列表", books);
        Button bookName = valueButton(profileStore.bookName());
        bookName.setOnClickListener(
                v ->
                        showStringChooser(
                                "选择默认账本",
                                splitValues(books.getText().toString()),
                                buttonValue(bookName),
                                false,
                                value -> setButtonValue(bookName, value)));
        addLabeledButton(fields, "默认账本", "账单没有指定账本时使用；不是第二个账本", bookName);

        EditText availableTags = dialogEditor("标签库，每行一个", profileStore.availableTagsText());
        availableTags.setSingleLine(false);
        availableTags.setMinLines(2);
        addLabeledEditor(fields, "可用标签", "每行一个", availableTags);
        Button tags = valueButton(profileStore.tags());
        tags.setOnClickListener(
                v ->
                        showTagChooser(
                                splitValues(availableTags.getText().toString()),
                                buttonValue(tags),
                                value -> setButtonValue(tags, value)));
        addLabeledButton(fields, "默认标签", "可多选；账单没有标签时使用", tags);

        Button categories = actionButton("", false, false);
        categories.setText("查看已解析分类（" + profileStore.categories().size() + " 组）");
        categories.setOnClickListener(
                v -> showTextDialog("已解析的一级 / 二级分类", categoryDetails(profileStore.categories())));
        addLabeledButton(fields, "分类目录", "由一木导出文件解析并同步到手表", categories);
        new AlertDialog.Builder(this)
                .setTitle("账务配置")
                .setMessage("列表与默认项含义不同；保存后会在下一次 BLE 连接时同步到手表。")
                .setView(dialogScroll(fields))
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "保存并同步",
                        (dialog, which) -> {
                            profileStore.save(
                                    accounts.getText().toString(),
                                    buttonValue(defaultAccount),
                                    books.getText().toString(),
                                    buttonValue(bookName),
                                    availableTags.getText().toString(),
                                    buttonValue(tags));
                            PhoneSyncService.start(this);
                            render();
                            Toast.makeText(this, "已保存；请在手表点击“接收手机账户 / 分类”", Toast.LENGTH_LONG)
                                    .show();
                        })
                .show();
    }

    private void showDetailsDialog(PhoneLedgerStore.Entry entry) {
        LinearLayout fields = dialogFields();
        EditText note = dialogEditor("消费内容", entry.note);
        addLabeledEditor(fields, "消费内容", "可人工修正语音或截图结果", note);

        YimuCategoryMapper.Mapping initial = entry.watchSuggestedMapping();
        if (initial == null) {
            initial = YimuCategoryMapper.map(entry.category, entry.note);
        }
        YimuCategoryMapper.Mapping[] category = new YimuCategoryMapper.Mapping[] {initial};
        Button categoryButton = valueButton(typeAndPath(initial));
        categoryButton.setOnClickListener(
                v ->
                        showCategoryChooser(
                                category[0],
                                selected -> {
                                    category[0] = selected;
                                    setButtonValue(categoryButton, typeAndPath(selected));
                                }));
        addLabeledButton(fields, "一木分类", "从已解析的一级/二级分类中选择", categoryButton);

        Button account =
                valueButton(
                        entry.account.isEmpty() ? profileStore.defaultAccount() : entry.account);
        account.setOnClickListener(
                v ->
                        showStringChooser(
                                "选择账户",
                                profileStore.accounts(),
                                buttonValue(account),
                                true,
                                value -> setButtonValue(account, value)));
        addLabeledButton(fields, "支出 / 收入账户", "来自已解析账户，可自定义", account);

        Button bookName =
                valueButton(entry.bookName.isEmpty() ? profileStore.bookName() : entry.bookName);
        bookName.setOnClickListener(
                v ->
                        showStringChooser(
                                "选择账本",
                                profileStore.books(),
                                buttonValue(bookName),
                                true,
                                value -> setButtonValue(bookName, value)));
        addLabeledButton(fields, "账本", "来自已解析账本，可自定义", bookName);

        Button tags = valueButton(entry.tags.isEmpty() ? profileStore.tags() : entry.tags);
        tags.setOnClickListener(
                v ->
                        showTagChooser(
                                profileStore.availableTags(),
                                buttonValue(tags),
                                value -> setButtonValue(tags, value)));
        addLabeledButton(fields, "标签", "来自已解析标签，可多选或自定义", tags);
        new AlertDialog.Builder(this)
                .setTitle("修改账单内容")
                .setView(dialogScroll(fields))
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "保存",
                        (dialog, which) -> {
                            String noteValue = note.getText().toString().trim();
                            if (noteValue.isEmpty()) {
                                noteValue = entry.note;
                            }
                            store.updateDetails(
                                    entry.id,
                                    noteValue,
                                    category[0].parent,
                                    category[0].child,
                                    buttonValue(account),
                                    buttonValue(bookName),
                                    buttonValue(tags));
                            render();
                        })
                .show();
    }

    private LinearLayout dialogFields() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(22), dp(4), dp(22), dp(16));
        return fields;
    }

    private EditText dialogEditor(String hint, String value) {
        EditText editor = new EditText(this);
        editor.setHint(hint);
        editor.setText(value);
        editor.setSingleLine(true);
        editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setTextColor(INK);
        editor.setHintTextColor(MUTED);
        editor.setTextSize(15);
        editor.setMinHeight(dp(50));
        editor.setPadding(dp(14), dp(8), dp(14), dp(8));
        editor.setBackground(rounded(Color.rgb(244, 247, 245), 14, LINE, 1));
        return editor;
    }

    private View dialogScroll(LinearLayout fields) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        scroll.addView(fields, new ScrollView.LayoutParams(-1, -2));
        LinearLayout viewport = new LinearLayout(this);
        viewport.setOrientation(LinearLayout.VERTICAL);
        int contentHeight = Math.min(dp(480), Math.round(screenHeight * 0.50f));
        viewport.addView(scroll, new LinearLayout.LayoutParams(-1, contentHeight));
        return viewport;
    }

    private void addLabeledEditor(LinearLayout fields, String title, String help, EditText editor) {
        fields.addView(fieldLabel(title, help));
        fields.addView(editor, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addLabeledButton(LinearLayout fields, String title, String help, Button button) {
        fields.addView(fieldLabel(title, help));
        fields.addView(button, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private TextView fieldLabel(String title, String help) {
        String value = title + (help.isEmpty() ? "" : "\n" + help);
        SpannableString content = new SpannableString(value);
        content.setSpan(
                new StyleSpan(Typeface.BOLD), 0, title.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!help.isEmpty()) {
            content.setSpan(
                    new ForegroundColorSpan(MUTED),
                    title.length() + 1,
                    value.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView label = styledText("", 13, false, INK);
        label.setText(content);
        label.setLineSpacing(0, 1.12f);
        label.setPadding(dp(2), dp(14), 0, dp(6));
        return label;
    }

    private Button valueButton(String value) {
        Button button = actionButton("", false, false);
        setButtonValue(button, value);
        return button;
    }

    private void setButtonValue(Button button, String value) {
        String normalized = value == null ? "" : value.trim();
        button.setTag(normalized);
        String display = normalized.isEmpty() ? "未选择" : normalized;
        if (display.length() > 36) {
            display = display.substring(0, 36) + "…";
        }
        button.setText(display + "  ▾");
    }

    private static String buttonValue(Button button) {
        Object value = button.getTag();
        return value == null ? "" : value.toString();
    }

    private void showStringChooser(
            String title,
            List<String> source,
            String current,
            boolean custom,
            StringCallback callback) {
        List<String> values = new ArrayList<>();
        values.add("");
        for (String value : source) {
            if (!value.trim().isEmpty() && !values.contains(value.trim())) {
                values.add(value.trim());
            }
        }
        String[] labels = new String[values.size() + (custom ? 1 : 0)];
        labels[0] = "不指定";
        for (int i = 1; i < values.size(); i++) {
            labels[i] = values.get(i);
        }
        if (custom) {
            labels[labels.length - 1] = "自定义输入…";
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(
                        labels,
                        (dialog, which) -> {
                            if (custom && which == labels.length - 1) {
                                showCustomValueDialog(title, current, callback);
                            } else {
                                callback.onSelected(values.get(which));
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCustomValueDialog(String title, String current, StringCallback callback) {
        EditText input = dialogEditor("输入与一木完全一致的名称", current);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "确定",
                        (dialog, which) -> callback.onSelected(input.getText().toString().trim()))
                .show();
    }

    private void showTagChooser(List<String> source, String current, StringCallback callback) {
        List<String> values = new ArrayList<>();
        for (String value : source) {
            if (!value.trim().isEmpty() && !values.contains(value.trim())) {
                values.add(value.trim());
            }
        }
        Set<String> selected = new LinkedHashSet<>(splitTagValues(current));
        boolean[] checked = new boolean[values.size()];
        for (int i = 0; i < values.size(); i++) {
            checked[i] = selected.contains(values.get(i));
        }
        new AlertDialog.Builder(this)
                .setTitle("选择标签")
                .setMultiChoiceItems(
                        values.toArray(new String[0]),
                        checked,
                        (dialog, which, isChecked) -> {
                            if (isChecked) {
                                selected.add(values.get(which));
                            } else {
                                selected.remove(values.get(which));
                            }
                        })
                .setNegativeButton("取消", null)
                .setNeutralButton(
                        "自定义输入",
                        (dialog, which) -> showCustomValueDialog("自定义标签", current, callback))
                .setPositiveButton(
                        "确定", (dialog, which) -> callback.onSelected(String.join(",", selected)))
                .show();
    }

    private void showCategoryChooser(YimuCategoryMapper.Mapping current, MappingCallback callback) {
        List<YimuCategoryMapper.Parent> parents = profileStore.categories();
        String[] labels = new String[parents.size() + 1];
        for (int i = 0; i < parents.size(); i++) {
            labels[i] =
                    ("收入".equals(parents.get(i).name) ? "收入 · " : "支出 · ") + parents.get(i).name;
        }
        labels[labels.length - 1] = "自定义分类…";
        new AlertDialog.Builder(this)
                .setTitle("选择一级分类")
                .setItems(
                        labels,
                        (dialog, which) -> {
                            if (which == parents.size()) {
                                showCustomCategoryDialog(current, callback);
                            } else {
                                showCategoryChildChooser(parents.get(which), callback);
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCategoryChildChooser(
            YimuCategoryMapper.Parent parent, MappingCallback callback) {
        String[] labels = new String[parent.children.size() + 2];
        labels[0] = "仅使用一级分类“" + parent.name + "”";
        for (int i = 0; i < parent.children.size(); i++) {
            labels[i + 1] = parent.children.get(i);
        }
        labels[labels.length - 1] = "自定义二级分类…";
        new AlertDialog.Builder(this)
                .setTitle("选择“" + parent.name + "”的二级分类")
                .setItems(
                        labels,
                        (dialog, which) -> {
                            if (which == labels.length - 1) {
                                showCustomValueDialog(
                                        "自定义二级分类",
                                        "",
                                        child ->
                                                callback.onSelected(
                                                        new YimuCategoryMapper.Mapping(
                                                                parent.name, child)));
                            } else {
                                callback.onSelected(
                                        new YimuCategoryMapper.Mapping(
                                                parent.name,
                                                which == 0 ? "" : parent.children.get(which - 1)));
                            }
                        })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCustomCategoryDialog(
            YimuCategoryMapper.Mapping current, MappingCallback callback) {
        LinearLayout fields = dialogFields();
        EditText parent = dialogEditor("一级分类", current == null ? "" : current.parent);
        EditText child = dialogEditor("二级分类（可留空）", current == null ? "" : current.child);
        addLabeledEditor(fields, "一级分类", "父分类", parent);
        addLabeledEditor(fields, "二级分类", "可留空", child);
        new AlertDialog.Builder(this)
                .setTitle("自定义分类")
                .setView(fields)
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "确定",
                        (dialog, which) -> {
                            String parentValue = parent.getText().toString().trim();
                            if (!parentValue.isEmpty()) {
                                callback.onSelected(
                                        new YimuCategoryMapper.Mapping(
                                                parentValue, child.getText().toString().trim()));
                            }
                        })
                .show();
    }

    private void showTextDialog(String title, String value) {
        TextView content = text(value, 15, false);
        content.setPadding(dp(24), dp(8), dp(24), dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scroll)
                .setPositiveButton("关闭", null)
                .show();
    }

    private static String categoryDetails(List<YimuCategoryMapper.Parent> categories) {
        StringBuilder result = new StringBuilder();
        for (YimuCategoryMapper.Parent parent : categories) {
            if (result.length() > 0) {
                result.append("\n\n");
            }
            result.append("【").append(parent.name).append("】");
            if (parent.children.isEmpty()) {
                result.append("\n  （没有二级分类）");
            } else {
                for (String child : parent.children) {
                    result.append("\n  · ").append(child);
                }
            }
        }
        return result.length() == 0 ? "尚未解析到分类" : result.toString();
    }

    private static List<String> splitValues(String raw) {
        List<String> result = new ArrayList<>();
        if (raw != null) {
            for (String value : raw.split("[\\r\\n,，]+")) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private static List<String> splitTagValues(String raw) {
        List<String> result = new ArrayList<>();
        if (raw != null) {
            for (String value : raw.split("[\\s,，;；]+")) {
                String trimmed = value.trim();
                if (!trimmed.isEmpty() && !result.contains(trimmed)) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    private void showDataManagementDialog() {
        String[] actions = {
            "导出完整 JSON 备份",
            "从 JSON 备份恢复",
            "清除手机本地账单",
            "清除账务配置与分类规则",
            "清除截图扫描缓存",
            "清除全部手机本地数据"
        };
        new AlertDialog.Builder(this)
                .setTitle("数据迁移、导出与清除")
                .setMessage(
                        "备份包含账单、账户/分类/账本/标签和已学习规则，属于敏感财务数据。"
                                + "不包含系统权限、蓝牙配对、截图游标、支付页面内容或凭据。")
                .setItems(
                        actions,
                        (dialog, which) -> {
                            if (which == 0) {
                                chooseBackupDestination();
                            } else if (which == 1) {
                                chooseBackupFile();
                            } else {
                                confirmPhoneDataClear(which);
                            }
                        })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void chooseBackupDestination() {
        Intent intent =
                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json")
                        .putExtra(
                                Intent.EXTRA_TITLE,
                                "X3VoiceLedger-backup-"
                                        + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                                                .format(new Date())
                                        + ".json");
        startActivityForResult(intent, BACKUP_EXPORT_REQUEST);
    }

    private void chooseBackupFile() {
        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("application/json");
        startActivityForResult(intent, BACKUP_IMPORT_REQUEST);
    }

    private void exportBackupUri(Uri uri) {
        Toast.makeText(this, "正在生成未加密的敏感 JSON 备份…", Toast.LENGTH_SHORT).show();
        profileImportExecutor.execute(
                () -> {
                    try {
                        byte[] data = LedgerBackupManager.create(store, profileStore, ruleStore);
                        LedgerBackupManager.write(getContentResolver(), uri, data);
                        runOnUiThread(
                                () ->
                                        Toast.makeText(
                                                        this,
                                                        "备份已导出，请妥善保管",
                                                        Toast.LENGTH_LONG)
                                                .show());
                    } catch (Exception error) {
                        showBackgroundError(error, "导出备份失败");
                    }
                });
    }

    private void importBackupUri(Uri uri) {
        Toast.makeText(this, "正在校验备份…", Toast.LENGTH_SHORT).show();
        profileImportExecutor.execute(
                () -> {
                    try {
                        LedgerBackupManager.ParsedBackup backup =
                                LedgerBackupManager.read(getContentResolver(), uri);
                        runOnUiThread(() -> confirmBackupRestore(backup));
                    } catch (Exception error) {
                        showBackgroundError(error, "读取备份失败");
                    }
                });
    }

    private void confirmBackupRestore(LedgerBackupManager.ParsedBackup backup) {
        if (!canDeliverProfileImport()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("恢复这份记账备份？")
                .setMessage(
                        "备份包含 "
                                + backup.entryCount
                                + " 笔账单、"
                                + backup.ruleCount
                                + " 条分类规则。账单按 UUID 和更新时间合并，较旧记录不会覆盖较新编辑；"
                                + "账务配置和分类规则将替换为备份内容。系统权限与蓝牙配对需要重新确认。")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "确认恢复",
                        (dialog, which) ->
                                profileImportExecutor.execute(() -> restoreBackup(backup)))
                .show();
    }

    private void restoreBackup(LedgerBackupManager.ParsedBackup backup) {
        try {
            int restored =
                    LedgerBackupManager.restore(backup, store, profileStore, ruleStore);
            runOnUiThread(
                    () -> {
                        if (!canDeliverProfileImport()) {
                            return;
                        }
                        render();
                        Toast.makeText(
                                        this,
                                        "恢复完成，写入或更新 " + restored + " 笔账单",
                                        Toast.LENGTH_LONG)
                                .show();
                    });
        } catch (Exception error) {
            showBackgroundError(error, "恢复备份失败，可修正后安全重试");
        }
    }

    private void showBackgroundError(Exception error, String fallback) {
        String message = error.getMessage() == null ? fallback : error.getMessage();
        runOnUiThread(
                () -> {
                    if (canDeliverProfileImport()) {
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void confirmPhoneDataClear(int action) {
        String title;
        String message;
        if (action == 2) {
            title = "清除手机本地全部账单？";
            message = "将永久删除待导入和已归档账单，不影响手表或一木中的副本。";
        } else if (action == 3) {
            title = "清除配置与分类规则？";
            message = "将清除账户、分类、账本、标签和已学习规则，不删除账单。";
        } else if (action == 4) {
            title = "清除截图扫描缓存？";
            message = "将重置截图游标与失败重试状态，不删除账单或原始照片。";
        } else {
            title = "清除全部手机本地数据？";
            message =
                    "将永久删除账单、配置、分类规则和截图扫描缓存。"
                            + "不会撤销系统权限、删除照片、改变蓝牙配对或删除手表/一木副本。";
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认清除", (dialog, which) -> clearPhoneData(action))
                .show();
    }

    private void clearPhoneData(int action) {
        if (action == 2 || action > 4) {
            stopService(new Intent(this, PhoneSyncService.class));
            store.deleteAll();
            selectedIds.clear();
        }
        if (action == 3 || action > 4) {
            profileStore.clear();
            ruleStore.clearAll();
        }
        if (action == 4 || action > 4) {
            WatchScreenshotMonitor.clearState(this);
        }
        render();
        Toast.makeText(this, "所选手机本地数据已清除", Toast.LENGTH_LONG).show();
    }

    private void chooseProfileFile() {
        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(
                                Intent.EXTRA_MIME_TYPES,
                                new String[] {
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "text/csv",
                                    "text/tab-separated-values",
                                    "application/json",
                                    "text/plain"
                                });
        startActivityForResult(intent, PROFILE_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == PROFILE_FILE_REQUEST) {
            importProfileUri(uri);
        } else if (requestCode == BACKUP_EXPORT_REQUEST) {
            exportBackupUri(uri);
        } else if (requestCode == BACKUP_IMPORT_REQUEST) {
            importBackupUri(uri);
        }
    }

    private void handleProfileIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return;
        }
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        intent.setAction(null);
        intent.removeExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            Toast.makeText(this, "分享内容中没有可读取的导出文件", Toast.LENGTH_LONG).show();
            return;
        }
        importProfileUri(uri);
    }

    private void importProfileUri(Uri uri) {
        Toast.makeText(this, "正在读取账务配置…", Toast.LENGTH_SHORT).show();
        profileImportExecutor.execute(() -> readProfile(uri));
    }

    private void readProfile(Uri uri) {
        try {
            LedgerProfileImporter.Result result =
                    LedgerProfileImporter.read(getContentResolver(), uri);
            runOnUiThread(() -> deliverProfileResult(result));
        } catch (Exception error) {
            String message =
                    error.getMessage() == null ? "无法读取所选文件" : error.getMessage();
            runOnUiThread(() -> deliverProfileError(message));
        }
    }

    private void deliverProfileResult(LedgerProfileImporter.Result result) {
        if (canDeliverProfileImport()) {
            confirmProfileImport(result);
        }
    }

    private void deliverProfileError(String message) {
        if (canDeliverProfileImport()) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private boolean canDeliverProfileImport() {
        return profileImportGate.isOpen()
                && !isFinishing()
                && (Build.VERSION.SDK_INT < 17 || !isDestroyed());
    }
    private void confirmProfileImport(LedgerProfileImporter.Result result) {
        LinearLayout preview = dialogFields();
        preview.addView(text(result.preview(), 15, false));
        Button categories = actionButton("", false, false);
        categories.setText("查看解析出的一级 / 二级分类（" + result.categories.size() + " 组）");
        categories.setOnClickListener(v -> showTextDialog("本次解析出的分类", result.categoryDetails()));
        preview.addView(categories, new LinearLayout.LayoutParams(-1, dp(50)));
        new AlertDialog.Builder(this)
                .setTitle("发现账务配置")
                .setView(dialogScroll(preview))
                .setNegativeButton("取消", null)
                .setPositiveButton(
                        "合并并同步",
                        (dialog, which) -> {
                            profileStore.merge(result);
                            PhoneSyncService.start(this);
                            render();
                            Toast.makeText(this, "配置已合并；下次 BLE 连接将同步到手表", Toast.LENGTH_LONG).show();
                        })
                .show();
    }

    private void enableScreenshotImport() {
        if (!WatchScreenshotMonitor.hasImagePermission(this)) {
            requestPermissions(
                    new String[] {WatchScreenshotMonitor.requiredPermission()},
                    IMAGE_PERMISSION_REQUEST);
            return;
        }
        WatchScreenshotMonitor.rescanLatest(this);
        Toast.makeText(this, "正在离线识别最近一张手表截图", Toast.LENGTH_SHORT).show();
    }

    private void ensurePermissionsAndSync() {
        List<String> missing = new ArrayList<>();
        if (!hasLocationPermission()) {
            missing.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            addIfMissing(missing, "android.permission.BLUETOOTH_SCAN");
            addIfMissing(missing, "android.permission.BLUETOOTH_CONNECT");
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        } else {
            PhoneSyncService.start(this);
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[] {"android.permission.POST_NOTIFICATIONS"},
                        NOTIFICATION_PERMISSION_REQUEST);
            }
        }
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasSyncPermissions() {
        if (!hasLocationPermission()) {
            return false;
        }
        return Build.VERSION.SDK_INT < 31
                || (checkSelfPermission("android.permission.BLUETOOTH_SCAN")
                                == PackageManager.PERMISSION_GRANTED
                        && checkSelfPermission("android.permission.BLUETOOTH_CONNECT")
                                == PackageManager.PERMISSION_GRANTED);
    }

    private void addIfMissing(List<String> missing, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            missing.add(permission);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == IMAGE_PERMISSION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                WatchScreenshotMonitor.rescanLatest(this);
                render();
            } else {
                Toast.makeText(this, "未获得照片权限，截图记账保持关闭", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        if (hasSyncPermissions()) {
            ensurePermissionsAndSync();
        }
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView view = styledText(value, sp, bold, INK);
        view.setPadding(0, 0, 0, dp(12));
        return view;
    }

    private TextView styledText(String value, int sp, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.START);
        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }
        return view;
    }

    private LinearLayout panel(int radius, int color) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        panel.setBackground(rounded(color, radius, 0, 0));
        return panel;
    }

    private View sectionHeading(String title, String subtitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.BOTTOM);
        row.setPadding(dp(2), dp(7), dp(2), dp(9));
        TextView heading = styledText(title, 20, true, INK);
        row.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView detail = styledText(subtitle, 12, false, MUTED);
        detail.setGravity(Gravity.END);
        row.addView(detail);
        return row;
    }

    private Button actionButton(String label, boolean primary, boolean danger) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int background =
                primary ? PRIMARY : danger ? Color.rgb(255, 239, 240) : Color.rgb(241, 245, 243);
        int foreground = primary ? Color.rgb(5, 57, 40) : danger ? DANGER : INK;
        button.setTextColor(foreground);
        button.setBackground(rounded(background, 15, primary ? 0 : LINE, primary ? 0 : 1));
        button.setPadding(dp(12), 0, dp(12), 0);
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private GradientDrawable gradient(int startColor, int endColor, int radiusDp) {
        GradientDrawable drawable =
                new GradientDrawable(
                        GradientDrawable.Orientation.TL_BR, new int[] {startColor, endColor});
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams blockParams(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams buttonParams(int height, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface MappingCallback {
        void onSelected(YimuCategoryMapper.Mapping mapping);
    }

    private interface StringCallback {
        void onSelected(String value);
    }

    private static final class ImportItem {
        final PhoneLedgerStore.Entry entry;
        final YimuCategoryMapper.Mapping mapping;

        ImportItem(PhoneLedgerStore.Entry entry, YimuCategoryMapper.Mapping mapping) {
            this.entry = entry;
            this.mapping = mapping;
        }
    }

    @Override
    protected void onDestroy() {
        profileImportGate.close();
        profileImportExecutor.shutdownNow();
        store.close();
        super.onDestroy();
    }
}
