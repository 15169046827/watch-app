package com.canace.x3wolainotes;

import android.app.Activity;
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
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderActivity extends Activity {
    private static final int BG = Color.rgb(9, 12, 18);
    private static final int CARD = Color.rgb(25, 31, 41);
    private static final int CARD_ALT = Color.rgb(38, 47, 60);
    private static final int PRIMARY = Color.rgb(104, 232, 181);
    private static final int PRIMARY_DARK = Color.rgb(8, 54, 38);
    private static final int MUTED = Color.rgb(157, 170, 188);
    private static final int WARN = Color.rgb(255, 194, 102);

    private static final class PageRef {
        final String id;
        final String title;
        final boolean directory;

        PageRef(String id, String title, boolean directory) {
            this.id = id;
            this.title = title;
            this.directory = directory;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Deque<PageRef> history = new ArrayDeque<>();

    private TextView titleView;
    private TextView statusView;
    private LinearLayout blockList;
    private Button refreshButton;
    private Button filterButton;
    private EditText filterInput;
    private PageRef loadedPage;
    private List<WolaiClient.RemoteBlock> loadedBlocks = Collections.emptyList();
    private Map<String, WolaiClient.DatabaseTable> loadedTables = Collections.emptyMap();
    private String activeQuery = "";
    private String token;
    private NoteStore store;
    private PageConfigStore pageConfig;
    private boolean destroyed;
    private int loadGeneration;
    private float downX;
    private float downY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new NoteStore(this);
        pageConfig = new PageConfigStore(this);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        // Favorites can include sensitive notes; keep them out of screenshots and recents previews.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        token = new SecretStore(this).loadToken();
        buildScreen();
        String favoritesPageId = pageConfig.favoritesPageId();
        if (!AppConfig.isConfiguredPage(favoritesPageId)) {
            showError("未配置收藏页面");
            return;
        }
        history.push(new PageRef(favoritesPageId, AppConfig.FAVORITES_PAGE_LABEL, true));
        if (token == null || token.trim().isEmpty()) {
            showError("未配置个人 Token");
        } else {
            loadCurrent();
        }
    }

    private void buildScreen() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setBackgroundColor(BG);
        int side = isRoundScreen() ? dp(32) : dp(24);
        content.setPadding(side, dp(8), side, dp(18));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button backButton = button("‹", CARD_ALT, Color.WHITE);
        backButton.setTextSize(22);
        backButton.setOnClickListener(v -> navigateBack());
        header.addView(backButton, params(dp(42), dp(38), 0, 0));

        titleView = text(AppConfig.FAVORITES_PAGE_LABEL, 18, Color.WHITE, true);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, -2, 1f);
        titleParams.setMargins(dp(6), 0, 0, 0);
        header.addView(titleView, titleParams);
        content.addView(header, params(-1, -2, 0, dp(5)));

        statusView = text("", 11, MUTED, false);
        statusView.setPadding(dp(8), dp(4), dp(8), dp(4));
        content.addView(statusView, params(-1, -2, 0, dp(5)));

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setGravity(Gravity.CENTER_VERTICAL);
        filterInput = new EditText(this);
        filterInput.setHint("筛选当前页…");
        filterInput.setHintTextColor(MUTED);
        filterInput.setTextColor(Color.WHITE);
        filterInput.setTextSize(13);
        filterInput.setSingleLine(true);
        filterInput.setInputType(InputType.TYPE_CLASS_TEXT);
        filterInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        filterInput.setFilters(new InputFilter[]{
            new InputFilter.LengthFilter(ReaderFilter.MAX_QUERY_LENGTH)
        });
        filterInput.setBackground(rounded(CARD, 16));
        filterInput.setPadding(dp(10), 0, dp(10), 0);
        filterInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE) {
                applyFilter(false);
                return true;
            }
            return false;
        });
        filterRow.addView(filterInput, new LinearLayout.LayoutParams(0, dp(40), 1f));
        filterButton = button("筛选", CARD_ALT, Color.WHITE);
        filterButton.setOnClickListener(v -> {
            String input = ReaderFilter.normalize(filterInput.getText().toString());
            boolean clear = !activeQuery.isEmpty() && activeQuery.equals(input);
            applyFilter(clear);
        });
        LinearLayout.LayoutParams filterButtonParams =
                new LinearLayout.LayoutParams(dp(70), dp(40));
        filterButtonParams.setMargins(dp(5), 0, 0, 0);
        filterRow.addView(filterButton, filterButtonParams);
        content.addView(filterRow, params(-1, dp(40), 0, dp(5)));

        refreshButton = button("在线刷新", CARD_ALT, Color.WHITE);
        refreshButton.setOnClickListener(v -> loadCurrent());
        content.addView(refreshButton, params(-1, dp(40), 0, dp(6)));

        blockList = new LinearLayout(this);
        blockList.setOrientation(LinearLayout.VERTICAL);
        content.addView(blockList, params(-1, -2, 0, dp(4)));

        TextView hint = text("仅在线读取 · 右滑返回", 10, MUTED, false);
        hint.setPadding(0, dp(5), 0, 0);
        content.addView(hint);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        setFilterEnabled(false);
    }

    private void loadCurrent() {
        if (token == null || token.trim().isEmpty() || history.isEmpty()) {
            return;
        }
        PageRef page = history.peek();
        int generation = ++loadGeneration;
        loadedPage = null;
        loadedBlocks = Collections.emptyList();
        loadedTables = Collections.emptyMap();
        titleView.setText(page.title);
        statusView.setText("在线读取中…");
        statusView.setTextColor(PRIMARY);
        refreshButton.setEnabled(false);
        refreshButton.setAlpha(0.55f);
        setFilterEnabled(false);
        blockList.removeAllViews();
        blockList.addView(messageCard("正在加载"));

        executor.execute(() -> loadPage(page, generation));
    }

    private void loadPage(PageRef page, int generation) {
        try {
            WolaiClient client = new WolaiClient(token);
            List<WolaiClient.RemoteBlock> blocks = page.directory
                    ? client.fetchChildren(page.id) : client.fetchPage(page.id);
            Map<String, WolaiClient.DatabaseTable> tables = fetchDatabases(client, page, blocks);
            runOnUiThread(() -> applyLoadedPage(page, blocks, tables, generation));
        } catch (Exception error) {
            String message = UserErrorMessages.readerStatus(WolaiClient.statusOf(error));
            runOnUiThread(() -> applyLoadError(message, generation));
        }
    }

    private Map<String, WolaiClient.DatabaseTable> fetchDatabases(
            WolaiClient client, PageRef page, List<WolaiClient.RemoteBlock> blocks) {
        Map<String, WolaiClient.DatabaseTable> tables = new LinkedHashMap<>();
        if (page.directory) {
            return tables;
        }
        for (WolaiClient.RemoteBlock block : blocks) {
            if (!"database".equals(block.type) || tables.containsKey(block.id)) {
                continue;
            }
            tables.put(block.id, fetchDatabaseOrFailure(client, block.id));
        }
        return tables;
    }

    private WolaiClient.DatabaseTable fetchDatabaseOrFailure(WolaiClient client, String id) {
        try {
            return client.fetchDatabase(id);
        } catch (WolaiClient.ApiException error) {
            return WolaiClient.DatabaseTable.failed(
                    UserErrorMessages.readerStatus(WolaiClient.statusOf(error)));
        }
    }

    private void applyLoadedPage(PageRef page, List<WolaiClient.RemoteBlock> blocks,
                                 Map<String, WolaiClient.DatabaseTable> tables, int generation) {
        if (destroyed || generation != loadGeneration) {
            return;
        }
        refreshButton.setEnabled(true);
        refreshButton.setAlpha(1f);
        loadedPage = page;
        loadedBlocks = blocks;
        loadedTables = tables;
        setFilterEnabled(true);
        renderLoadedContent();
    }

    private void applyLoadError(String message, int generation) {
        if (destroyed || generation != loadGeneration) {
            return;
        }
        refreshButton.setEnabled(true);
        refreshButton.setAlpha(1f);
        setFilterEnabled(false);
        showError(message);
    }

    private void renderBlocks(PageRef page, List<WolaiClient.RemoteBlock> blocks,
                              Map<String, WolaiClient.DatabaseTable> tables, String query) {
        blockList.removeAllViews();
        int rendered = page.directory ? renderDirectoryHeader(query) : 0;
        for (WolaiClient.RemoteBlock block : blocks) {
            if (renderBlock(page, block, tables, query)) {
                rendered++;
            }
        }
        if (rendered == 0) {
            blockList.addView(messageCard(emptyPageMessage(page, query)));
        }
    }

    private int renderDirectoryHeader(String query) {
        int rendered = addRecentPages(query);
        if (rendered > 0) {
            addSectionHeading("收藏页面");
        }
        return rendered;
    }

    private boolean renderBlock(PageRef page, WolaiClient.RemoteBlock block,
                                Map<String, WolaiClient.DatabaseTable> tables, String query) {
        if (page.directory || block.isPageLink()) {
            return addPageLinkIfMatched(block, query);
        }
        if ("database".equals(block.type)) {
            return addDatabase(block, tables.get(block.id), query);
        }
        return addContentBlock(block, query);
    }

    private boolean addPageLinkIfMatched(WolaiClient.RemoteBlock block, String query) {
        if (!block.isPageLink()) {
            return false;
        }
        String label = block.text.isEmpty() ? "未命名页面" : block.text;
        if (!ReaderFilter.matches(query, label)) {
            return false;
        }
        addPageLink(block);
        return true;
    }

    private static String emptyPageMessage(PageRef page, String query) {
        if (!query.isEmpty()) {
            return "当前页面没有匹配结果";
        }
        return page.directory ? "请在 Wolai 的“手表收藏”页面添加页面引用"
                : "这个页面暂时没有可显示的文字内容";
    }

    private int addRecentPages(String query) {
        List<RecentPage> matches = new ArrayList<>();
        for (RecentPage page : store.recentPages(NoteStore.MAX_RECENT_PAGES)) {
            if (ReaderFilter.matches(query, page.title)) {
                matches.add(page);
            }
        }
        if (matches.isEmpty()) {
            return 0;
        }

        addSectionHeading("最近访问");
        for (RecentPage page : matches) {
            addRecentPageLink(page);
        }
        Button clear = button("清除最近", CARD_ALT, WARN);
        clear.setOnClickListener(v -> {
            store.clearRecentPages();
            renderLoadedContent();
        });
        blockList.addView(clear, params(-1, dp(36), 0, dp(7)));
        return matches.size();
    }

    private void addSectionHeading(String value) {
        TextView heading = text(value, 13, MUTED, true);
        heading.setGravity(Gravity.START);
        heading.setPadding(dp(8), dp(5), dp(8), dp(5));
        blockList.addView(heading, params(-1, -2, 0, dp(3)));
    }

    private void addRecentPageLink(RecentPage page) {
        TextView view = text(page.title + "  ›", 14, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(9), dp(10), dp(9));
        view.setBackground(rounded(CARD, 14));
        view.setOnClickListener(v -> openPage(page.id, page.title));
        blockList.addView(view, params(-1, -2, 0, dp(4)));
    }

    private boolean addDatabase(WolaiClient.RemoteBlock block,
                                WolaiClient.DatabaseTable table, String query) {
        String label = block.text.isEmpty() ? "数据表格" : block.text;
        boolean headingMatches = ReaderFilter.matches(query, label);
        List<WolaiClient.DatabaseRow> rows = matchingRows(table, query, headingMatches);
        if (!shouldDisplayDatabase(query, headingMatches, rows)) {
            return false;
        }
        addDatabaseHeading(label);
        addDatabaseBody(table, rows, query);
        return true;
    }

    private void addDatabaseBody(WolaiClient.DatabaseTable table,
                                 List<WolaiClient.DatabaseRow> rows, String query) {
        if (table == null) {
            addDatabaseMessage("表格暂时无法读取");
            return;
        }
        if (!table.error.isEmpty()) {
            addDatabaseMessage(table.error);
            return;
        }
        if (table.rows.isEmpty()) {
            addDatabaseMessage("表格暂无数据");
            return;
        }
        for (WolaiClient.DatabaseRow row : rows) {
            addDatabaseRow(row, table.rows.indexOf(row) + 1);
        }
        if (table.hasMore) {
            addMoreRowsNotice(query);
        }
    }

    private void addDatabaseMessage(String message) {
        blockList.addView(messageCard(message), params(-1, -2, 0, dp(5)));
    }

    private static boolean shouldDisplayDatabase(
            String query, boolean headingMatches, List<WolaiClient.DatabaseRow> rows) {
        if (query.isEmpty() || headingMatches) {
            return true;
        }
        return !rows.isEmpty();
    }

    private static List<WolaiClient.DatabaseRow> matchingRows(
            WolaiClient.DatabaseTable table, String query, boolean headingMatches) {
        List<WolaiClient.DatabaseRow> rows = new ArrayList<>();
        if (table == null || !table.error.isEmpty()) {
            return rows;
        }
        for (WolaiClient.DatabaseRow row : table.rows) {
            if (headingMatches || databaseRowMatches(row, query)) {
                rows.add(row);
            }
        }
        return rows;
    }

    private void addDatabaseHeading(String label) {
        TextView heading = text(label, 15, Color.WHITE, true);
        heading.setPadding(dp(9), dp(9), dp(9), dp(7));
        heading.setBackground(rounded(CARD_ALT, 14));
        blockList.addView(heading, params(-1, -2, 0, dp(4)));
    }

    private void addMoreRowsNotice(String query) {
        TextView more = text(query.isEmpty()
                ? "接口还有更多数据，在线刷新后查看"
                : "筛选仅覆盖本次接口返回的数据", 10, WARN, false);
        more.setPadding(dp(8), dp(5), dp(8), dp(7));
        blockList.addView(more);
    }

    private void addDatabaseRow(WolaiClient.DatabaseRow row, int rowNumber) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(9), dp(7), dp(9), dp(7));
        card.setBackground(rounded(CARD, 13));

        WolaiClient.DatabaseCell primary = null;
        for (WolaiClient.DatabaseCell cell : row.cells) {
            if ("primary".equals(cell.type) && !cell.value.isEmpty()) {
                primary = cell;
                break;
            }
        }
        String rowTitle = primary == null ? "第 " + rowNumber + " 行" : primary.value;
        TextView title = text(limit(rowTitle, 240), 14, Color.WHITE, true);
        title.setGravity(Gravity.START);
        title.setMaxLines(4);
        card.addView(title, params(-1, -2, 0, dp(3)));

        int detailCount = 0;
        for (WolaiClient.DatabaseCell cell : row.cells) {
            if (cell == primary || cell.value.isEmpty()) {
                continue;
            }
            TextView detail = text(limit(cell.column + " · " + cell.value, 360),
                    11, MUTED, false);
            detail.setGravity(Gravity.START);
            detail.setMaxLines(5);
            detail.setPadding(0, dp(1), 0, dp(1));
            card.addView(detail, params(-1, -2, 0, 0));
            detailCount++;
        }
        if (detailCount == 0 && primary == null) {
            TextView empty = text("空白行", 11, MUTED, false);
            empty.setGravity(Gravity.START);
            card.addView(empty);
        }

        if (!row.pageId.isEmpty()) {
            String pageTitle = rowTitle;
            card.setOnClickListener(v -> openPage(row.pageId, pageTitle));
        }
        blockList.addView(card, params(-1, -2, 0, dp(4)));
    }

    private static String limit(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }

    private void addPageLink(WolaiClient.RemoteBlock block) {
        String label = block.text.isEmpty() ? "未命名页面" : block.text;
        TextView view = text(label + "  ›", 15, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(10), dp(11), dp(10), dp(11));
        view.setBackground(rounded(CARD_ALT, 16));
        view.setOnClickListener(v -> openPage(block.targetPageId, label));
        blockList.addView(view, params(-1, -2, 0, dp(5)));
    }

    private void openPage(String pageId, String title) {
        store.recordRecentPage(pageId, title, System.currentTimeMillis());
        clearFilterForNavigation();
        history.push(new PageRef(pageId, title, false));
        loadCurrent();
    }

    private boolean addContentBlock(WolaiClient.RemoteBlock block, String query) {
        if ("divider".equals(block.type)) {
            return addDivider(query);
        }
        String value = displayText(block);
        if (value.isEmpty()) {
            return false;
        }
        if (!ReaderFilter.matches(query, value)) {
            return false;
        }
        value = decorateText(block, value);
        boolean heading = "heading".equals(block.type);
        TextView view = text(value, heading ? 16 : 14, Color.WHITE, heading);
        view.setGravity(heading ? Gravity.CENTER : Gravity.START);
        view.setPadding(dp(9 + Math.min(block.depth, 3) * 7), dp(7), dp(9), dp(7));
        view.setBackground(rounded(CARD, 13));
        if ("code".equals(block.type)) {
            view.setTypeface(Typeface.MONOSPACE);
        }
        blockList.addView(view, params(-1, -2, 0, dp(4)));
        return true;
    }

    private boolean addDivider(String query) {
        if (!query.isEmpty()) {
            return false;
        }
        View divider = new View(this);
        divider.setBackgroundColor(CARD_ALT);
        blockList.addView(divider, params(-1, dp(1), 4, 4));
        return true;
    }

    private static String displayText(WolaiClient.RemoteBlock block) {
        return block.text.isEmpty() ? mediaPlaceholder(block.type) : block.text;
    }

    private static String decorateText(WolaiClient.RemoteBlock block, String value) {
        String decorated = value;
        if ("todo_list".equals(block.type) || "todo_list_pro".equals(block.type)) {
            decorated = (block.checked ? "☑ " : "☐ ") + value;
        } else if ("bull_list".equals(block.type)) {
            decorated = "• " + value;
        } else if ("enum_list".equals(block.type)) {
            decorated = "– " + value;
        } else if ("quote".equals(block.type)) {
            decorated = "“" + value + "”";
        }
        return decorated.length() > 12000
                ? decorated.substring(0, 12000) + "…" : decorated;
    }

    private static String mediaPlaceholder(String type) {
        if ("image".equals(type)) {
            return "［图片］";
        }
        if ("video".equals(type)) {
            return "［视频］";
        }
        if ("audio".equals(type)) {
            return "［音频］";
        }
        if ("file".equals(type)) {
            return "［附件］";
        }
        if ("table".equals(type)) {
            return "［表格］";
        }
        if ("embed".equals(type) || "bookmark".equals(type)) {
            return "［网页链接］";
        }
        return "";
    }

    private void showError(String message) {
        loadedPage = null;
        loadedBlocks = Collections.emptyList();
        loadedTables = Collections.emptyMap();
        statusView.setText(message);
        statusView.setTextColor(WARN);
        blockList.removeAllViews();
        blockList.addView(messageCard("需要联网后重试"));
    }

    private void navigateBack() {
        if (history.size() <= 1) {
            finish();
            return;
        }
        history.pop();
        clearFilterForNavigation();
        loadCurrent();
    }

    private void applyFilter(boolean clear) {
        if (loadedPage == null) {
            return;
        }
        if (clear) {
            activeQuery = "";
            filterInput.setText("");
        } else {
            activeQuery = ReaderFilter.normalize(filterInput.getText().toString());
            filterInput.setText(activeQuery);
            filterInput.setSelection(activeQuery.length());
        }
        renderLoadedContent();
    }

    private void renderLoadedContent() {
        if (loadedPage == null) {
            return;
        }
        filterButton.setText(activeQuery.isEmpty() ? "筛选" : "清除");
        statusView.setText(activeQuery.isEmpty()
                ? "在线内容 · 不保存到手表"
                : "筛选当前页 · 不发起新请求");
        statusView.setTextColor(MUTED);
        renderBlocks(loadedPage, loadedBlocks, loadedTables, activeQuery);
    }

    private void clearFilterForNavigation() {
        activeQuery = "";
        if (filterInput != null) {
            filterInput.setText("");
        }
        if (filterButton != null) {
            filterButton.setText("筛选");
        }
    }

    private void setFilterEnabled(boolean enabled) {
        filterInput.setEnabled(enabled);
        filterInput.setAlpha(enabled ? 1f : 0.55f);
        filterButton.setEnabled(enabled);
        filterButton.setAlpha(enabled ? 1f : 0.55f);
    }

    private static boolean databaseRowMatches(WolaiClient.DatabaseRow row, String query) {
        List<String> values = new ArrayList<>();
        for (WolaiClient.DatabaseCell cell : row.cells) {
            values.add(cell.column);
            values.add(cell.value);
        }
        return ReaderFilter.matches(query, values.toArray(new String[0]));
    }

    private TextView messageCard(String value) {
        TextView view = text(value, 12, MUTED, false);
        view.setPadding(dp(9), dp(10), dp(9), dp(10));
        view.setBackground(rounded(CARD, 13));
        return view;
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private boolean isRoundScreen() {
        return (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_ROUND_MASK)
                == Configuration.SCREENLAYOUT_ROUND_YES;
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
                navigateBack();
                return true;
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public void onBackPressed() {
        navigateBack();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        loadGeneration++;
        loadedPage = null;
        loadedBlocks = Collections.emptyList();
        loadedTables = Collections.emptyMap();
        // Let the bounded HTTP request finish, then close SQLite on the same worker queue.
        executor.execute(() -> {
            store.close();
        });
        executor.shutdown();
        super.onDestroy();
    }
}
