package com.canace.x3schedule.shared;

import static com.canace.x3schedule.domain.Models.require;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.MotionEvent;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import com.canace.x3schedule.domain.Agenda;
import com.canace.x3schedule.domain.Execution;
import com.canace.x3schedule.domain.Ics;
import com.canace.x3schedule.domain.Kinds;
import com.canace.x3schedule.domain.Models;
import com.canace.x3schedule.domain.Models.Action;
import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.ExceptionDay;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Rule;
import com.canace.x3schedule.domain.Models.State;
import com.canace.x3schedule.domain.NaturalLanguage;
import com.canace.x3schedule.domain.PlanEdits;
import com.canace.x3schedule.domain.Wire;
import com.canace.x3schedule.domain.WorkCalendar;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native Views on both platforms. The watch has no plan editing route.
 */
public final class ScheduleActivity extends Activity {

    private Store store;
    private CalendarSurface calendarSurface;

    private SyncRuntime runtime;

    private final Runnable syncObserver = () -> {
        if (this.sync != null) {
            this.sync.setText(syncText());
        }
    };

    private LinearLayout content;

    private ScrollView scroll;

    private TextView sync;

    private Plan calendarPlan;

    private boolean calendarCompany;

    private String page = "home";

    private int homeTab;
    private LocalDate calendarDay = LocalDate.now();
    private int calendarMode;
    private int calendarOffset;

    private ScreenSession session = new ScreenSession();

    private ReportScreen reportScreen;

    private final HomeActions homeActions = new HomeActions() {
        @Override
        public void open(Route route) {
            safe(() -> openHomeRoute(route));
        }

        @Override
        public void detail(Instance instance) {
            safe(() -> ScheduleActivity.this.detail(instance));
        }

        @Override
        public void complete(Instance instance) {
            safe(() -> record(instance, Action.COMPLETE, 0, false, System.currentTimeMillis(), false));
        }
    };

    private float downX;

    private float downY;

    private byte[] exportBytes;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {

        @Override
        public void run() {
            if (page.equals("home") && (!store.phone || homeTab == 0)) {
                home(scroll.getScrollY());
            }
            handler.postDelayed(this, 60000);
        }
    };

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        store = new Store(this);
        runtime = SyncRuntime.get(this);
        restoreSession(b);
        if (b == null && store.meta("privacy_notice", "").equals("1")) {
            openReminder();
        }
    }

    private void openReminder() {
        String id = getIntent().getStringExtra("schedule.reminderInstance");
        if (id == null || id.length() > 256) {
            return;
        }
        Instance target = store.instances().stream().filter(item -> id.equals(item.id) && !item.cancelled)
                .findFirst().orElse(null);
        if (target == null) {
            toast("这项日程已改变或取消，请在日历查看最新安排。");
        } else {
            detail(target);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        session.calendarDay = calendarDay.toString();
        session.calendarMode = calendarMode;
        session.calendarOffset = calendarOffset;
        session.scroll = scroll.getScrollY();
        session.fields.clear();
        session.choices.clear();
        FormState.capture(content, session);
        session.reportRequested = reportScreen != null && reportScreen.requested();
        out.putString("schedule.session", session.encode());
        super.onSaveInstanceState(out);
    }

    private void restoreSession(Bundle saved) {
        if (!store.meta("privacy_notice", "").equals("1")) {
            welcome();
            return;
        }
        ScreenSession state = ScreenSession.decode(saved == null ? null : saved.getString("schedule.session"));
        homeTab = store.phone ? state.tab : 0;
        try {
            if (state.calendarDay != null) {
                calendarDay = LocalDate.parse(state.calendarDay);
                require(state.calendarMode >= 0 && state.calendarMode <= 2, "日历视图无效");
                calendarMode = state.calendarMode;
                calendarOffset = state.calendarOffset;
            }
            restorePage(state);
            FormState.restore(content, state);
            ScrollView target = scroll;
            if (reportScreen != null && state.reportRequested) {
                reportScreen.reload(() -> target.post(() -> target.scrollTo(0, state.scroll)));
            }
            target.post(() -> target.scrollTo(0, state.scroll));
            if (state.interrupted) {
                toast("页面状态过大或已失效，请重新打开；已保存日程不受影响");
            }
        } catch (RuntimeException error) {
            home();
            toast("未能恢复上次页面，请重新打开；已保存日程不受影响");
        }
    }

    private void restorePage(ScreenSession state) {
        Map<String, Runnable> routes = new java.util.HashMap<>();
        routes.put("home", () -> home(state.scroll));
        routes.put("reminderSettings", this::reminderSettings);
        routes.put("new", this::newSchedule);
        routes.put("todos", this::todos);
        routes.put("list", () -> list(state.pending));
        routes.put("plans", this::plans);
        routes.put("natural", this::natural);
        routes.put("settings", this::settings);
        routes.put("suggestions", this::suggestions);
        routes.put("capacity", this::capacity);
        routes.put("files", this::files);
        routes.put("connection", this::connection);
        routes.put("privacy", this::privacy);
        // Do not repeat a device selection, file operation, permission request or confirmation.
        routes.put("peers", this::connection);
        Runnable route = routes.get(state.page);
        if (route != null) {
            route.run();
        } else {
            restoreItem(state);
        }
    }

    private void restoreItem(ScreenSession state) {
        switch (state.page) {
            case "editor":
                editor(state.plan, state.editFrom, state.editOccurrence);
                break;
            case "exception":
                exception(state.plan);
                break;
            case "roster":
                roster(state.plan);
                break;
            case "plan":
                Plan current = store.plans().stream().filter(p -> p.id.equals(state.plan.id) && !p.deleted)
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("Plan no longer exists"));
                plan(current);
                break;
            case "preview":
                require(!state.drafts.isEmpty(), "预览已失效");
                preview(state.drafts, state.importing, state.scroll);
                break;
            case "detail":
            case "correct":
                Instance item = store.instances().stream().filter(i -> i.id.equals(state.instanceId))
                        .findFirst().orElseGet(() -> restoredCalendarItem(state));
                if (state.page.equals("detail")) {
                    detail(item);
                } else {
                    correction(item);
                }
                break;
            default:
                home();
                toast("上次操作需要重新发起；不会自动保存或再次写入文件");
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (page.equals("welcome")) {
            return;
        }
        resumeFeatures();
    }

    private void resumeFeatures() {
        RefreshJob.register(this);
        scheduleReminders(true);
        startLink();
        if (page.equals("connection")) {
            safe(this::connection);
        }
        handler.postDelayed(tick, 60000);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(tick);
        runtime.unobserve(syncObserver);
        runtime.release("activity");
        super.onPause();
    }

    @Override
    public void onDestroy() {
        store.close();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // Recalculate calendar columns for the new viewport without replacing an editor or pending save.
        if (store.phone && page.equals("home") && homeTab == 0) {
            safe(() -> home(scroll.getScrollY()));
        }
    }

    private void startLink() {
        runtime.observe(syncObserver);
        runtime.acquire("activity");
        safe(() -> {
            WatchConnectionService.startIfConfigured(this);
            if (store.phone && Build.VERSION.SDK_INT >= 31) {
                PresenceBinding.observe(this, store.meta("peer", ""));
            }
        });
    }

    private int dp(int n) {
        return (int) (getResources().getDisplayMetrics().density * n);
    }

    private void screen(String title) {
        session = new ScreenSession();
        session.page = page;
        session.tab = homeTab;
        reportScreen = null;
        sync = null;
        boolean introductory = page.equals("welcome");
        ScheduleShell shell = new ScheduleShell(this, title, page.equals("home") || introductory ? null : this::home,
                homeTab, introductory ? null : selected -> safe(() -> {
                    homeTab = selected;
                    home();
                }));
        scroll = shell.scroll;
        content = shell.content;
    }

    private TextView text(String s, int size) {
        return ScheduleUi.text(content, s, size);
    }

    private void button(String label, Runnable action) {
        ScheduleUi.button(content, label, () -> safe(action));
    }

    private void primaryButton(String label, Runnable action) {
        ScheduleUi.primary(ScheduleUi.button(content, label, () -> safe(action)));
    }

    private void safe(Runnable action) {
        if (DataMaintenance.active()) {
            toast("正在处理本机数据，请稍候。");
            return;
        }
        try {
            action.run();
        } catch (IllegalArgumentException e) {
            toast(e.getMessage() == null ? "输入无效" : e.getMessage());
        } catch (RuntimeException e) {
            toast("操作未完成，原数据保留；请检查权限与空间");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void scheduleReminders(boolean deliver) {
        ReminderWork.request(this, deliver, success -> {
            if (!success && !isDestroyed() && !isFinishing()) {
                toast("已保存数据保留；提醒安排未完成，请检查权限或存储空间。");
            }
        });
    }

    private String syncText() {
        return runtime.status() + "\n本机待同步改动：" + store.pending() + (store.phone && store.pending() > 0
                ? "\n手表仍可能按旧日程提醒" : "");
    }

    private String time(long at, String zone) {
        return Instant.ofEpochMilli(at).atZone(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
    }

    private String stateLabel(State s) {
        return AgendaCards.stateLabel(s);
    }

    private void home() {
        home(0);
    }

    private void home(int position) {
        page = "home";
        String[] titles = { "日历", "日程", "报告", "设置" };
        screen(store.phone ? titles[homeTab] : "日程");
        if (store.phone) {
            reminderAccess();
            AgendaScreen.reminderAttention(this, content, this::reminderSettings);
        }
        if (store.phone && homeTab == 0) {
            calendar(position);
            return;
        }
        if (store.phone && homeTab != 0) {
            phoneSection();
            return;
        }
        LinearLayout target = content;
        AgendaScreen.load(this, target, agenda -> {
            if (content == target) {
                renderHome(agenda);
                ScrollView targetScroll = scroll;
                targetScroll.post(() -> targetScroll.scrollTo(0, position));
            }
        });
    }

    private void renderHome(Agenda agenda) {
        if (store.phone) {
            sync = ScheduleUi.status(content, syncText());
            PhoneHomeScreen.today(content, agenda, homeActions);
        } else {
            WatchHomeScreen.show(content, agenda, homeActions);
            sync = ScheduleUi.status(content, syncText());
        }
        CapacityScreen.summary(this, text("正在读取同步数据容量…", 12));
        text(store.phone ? "手机按本机日程提醒；手表不会兜底。"
                : "手表本地提醒已停用；请在手机设置提醒。断连时仍可查看日程和记录完成。", 12);
    }

    private void calendar(int position) {
        if (calendarSurface == null) {
            calendarSurface = new CalendarSurface(this);
        }
        ScrollView targetScroll = scroll;
        new CalendarScreen(this, content, calendarDay, calendarMode, calendarOffset, new CalendarScreen.Actions() {
            @Override
            public void select(LocalDate day, int mode) {
                calendarDay = day;
                calendarMode = mode;
                calendarOffset = 0;
                home();
            }

            @Override
            public void create(LocalDate day, int minute) {
                calendarDay = day;
                Plan draft = new Plan();
                draft.kind = Kind.REMINDER;
                draft.anchor = day.toString();
                draft.zone = ZoneId.systemDefault().getId();
                draft.startMinute = minute;
                draft.rule = Rule.DAYS;
                draft.count = 1;
                draft.inheritReminders = true;
                safe(() -> editor(draft));
            }

            @Override
            public void detail(Instance item) {
                safe(() -> ScheduleActivity.this.detail(item));
            }
            @Override
            public void scroll(int offset) {
                calendarOffset = offset;
            }
        }, () -> targetScroll.scrollTo(0, position), calendarSurface).show();
    }

    void invalidateCalendar() {
        if (calendarSurface != null) {
            calendarSurface.finish();
            calendarSurface.body.removeAllViews();
            calendarSurface = null;
        }
    }

    private void phoneSection() {
        if (homeTab == 1) {
            PhoneHomeScreen.plans(content, homeActions);
        } else if (homeTab == 2) {
            button("本地优化建议", this::suggestions);
            reportScreen = new ReportScreen(this, content);
            reportScreen.show();
        } else {
            PhoneHomeScreen.settings(content, homeActions);
            sync = ScheduleUi.status(content, syncText());
        }
    }

    private void openHomeRoute(HomeActions.Route route) {
        switch (route) {
            case TODAY:
                list(false);
                break;
            case PENDING:
                list(true);
                break;
            case TODOS:
                todos();
                break;
            case NEW:
                newSchedule();
                break;
            case PLANS:
                plans();
                break;
            case NATURAL:
                natural();
                break;
            case SUGGESTIONS:
                suggestions();
                break;
            case REMINDERS:
                settings();
                break;
            case FILES:
                files();
                break;
            case CAPACITY:
                capacity();
                break;
            case CONNECTION:
                connection();
                break;
            case PRIVACY:
                privacy();
                break;
            default:
                throw new IllegalArgumentException("未知页面");
        }
    }

    private void newSchedule() {
        page = "new";
        screen("选择日程用途");
        for (Kind kind : Kind.values()) {
            button(Kinds.label(kind), () -> {
                Plan plan = new Plan();
                plan.kind = kind;
                plan.inheritReminders = true;
                plan.anchor = calendarDay.toString();
                plan.zone = ZoneId.systemDefault().getId();
                plan.rule = Rule.DAYS;
                plan.count = 1;
                plan.startMinute = 540;
                if (kind == Kind.REMINDER) {
                    plan.durationMinutes = 0;
                }
                if (kind == Kind.TODO) {
                    plan.rule = Rule.DAYS;
                    plan.count = 1;
                    plan.durationMinutes = 0;
                }
                editor(plan);
            });
        }
        text("执行型：按计划完成；仅提醒型：到时提醒；待办型：做完勾选，可不设时间。", 14);
    }

    private String instanceTime(Instance instance) {
        if (instance.dateOnly) {
            return CalendarScreen.label(instance, ZoneId.of(instance.zone));
        }
        if (instance.kind == Kind.TODO) {
            return instance.timed ? "截止 " + time(instance.start, instance.zone) : "未设置截止时间";
        }
        return time(instance.start, instance.zone) + " – " + time(instance.end, instance.zone);
    }

    private void todos() {
        page = "todos";
        screen("待办清单");
        List<Event> events = store.events();
        List<Instance> instances = store.instances();
        instances.sort(Comparator.comparingLong(i -> i.timed ? i.start : Long.MAX_VALUE));
        int shown = 0;
        for (Instance instance : instances) {
            if (instance.kind == Kind.TODO && !instance.cancelled) {
                State state = Execution.state(instance, Execution.winner(events, instance.id),
                        System.currentTimeMillis());
                button(instance.title + " · " + stateLabel(state) + "\n" + instanceTime(instance),
                        () -> detail(instance));
                shown++;
            }
        }
        if (shown == 0) {
            text("暂无待办，请在手机新建并同步。", 16);
        }
    }

    private void list(boolean pending) {
        page = "list";
        screen(pending ? "待确认" : "今日与下一项");
        session.pending = pending;
        List<Event> events = store.events();
        List<Instance> is = store.instances();
        is.sort(Comparator.comparingLong(i -> i.start));
        long now = System.currentTimeMillis();
        int shown = 0;
        for (Instance i : is) {
            State s = Execution.state(i, Execution.winner(events, i.id), now);
            boolean match = pending ? (s == State.PENDING || s == State.OVERDUE) : (!i.cancelled
                    && i.kind != Kind.TODO && i.end >= now - 86400000L && i.start < now + 86400000L);
            if (match && shown++ < 100) {
                button(instanceTime(i) + "\n" + i.title + " · " + stateLabel(s), () -> detail(i));
            }
        }
        if (shown == 0) {
            text(pending ? "没有待确认项" : "暂无日程，请在手机创建并同步", 16);
        }
    }

    private void detail(Instance i) {
        page = "detail";
        screen(i.title);
        session.instanceId = i.id;
        session.calendarInstance = Wire.copy(i, Instance.class);
        text(Kinds.label(i.kind), 14);
        text(instanceTime(i), 14);
        text(stateLabel(Execution.state(i, Execution.winner(store.events(), i.id), System.currentTimeMillis())), 16);
        for (String step : i.steps) {
            text("• " + step, 14);
        }
        if (i.disputed) {
            text("排班有争议，当前按公司排班执行；请在手机确认", 13);
        }
        if (i.cancelled) {
            return;
        }
        if (store.phone) {
            Plan owner = store.plans().stream().filter(p -> p.id.equals(i.planId)
                    && !p.deleted).findFirst().orElse(null);
            if (owner != null) {
                button("编辑", () -> chooseEdit(owner, i));
                button("删除", () -> chooseDelete(owner, i));
            }
        }
        detailActions(i);
    }

    private Instance restoredCalendarItem(ScreenSession state) {
        Instance item = state.calendarInstance;
        require(item != null && item.id.equals(state.instanceId), "日程已失效");
        Kinds.validateInstance(item);
        require(store.plans().stream().anyMatch(p -> p.id.equals(item.planId) && !p.deleted
                && p.version == item.planVersion), "日程已改变，请重新打开");
        return item;
    }

    private void detailActions(Instance i) {
        if (i.kind == Kind.REMINDER) {
            text("此日程仅作提醒，无需打卡或确认完成。", 14);
            return;
        }
        if (i.kind == Kind.TODO) {
            boolean done = Execution.state(i, Execution.winner(store.events(), i.id),
                    System.currentTimeMillis()) == State.TODO_DONE;
            button(done ? "重新打开待办" : "勾选完成", () -> record(i,
                    done ? Action.REOPEN : Action.COMPLETE, 0, false, System.currentTimeMillis(), false));
            return;
        }
        executionActions(i);
    }

    private void executionActions(Instance i) {
        button("开始", () -> record(i, Action.START, 0, false, System.currentTimeMillis(), false));
        primaryButton("已完成 · 刚刚完成", () -> record(i, Action.COMPLETE, 0, false,
                System.currentTimeMillis(), false));
        button("当时已完成，忘记确认", () -> record(i, Action.COMPLETE, 0, true, null, false));
        for (int n : new int[] { 5, 10, 15, 30 }) {
            button("还在进行 · " + n + " 分钟后再问", () -> record(i, Action.CONTINUE, n, false, null, false));
        }
        button("未完成", () -> record(i, Action.INCOMPLETE, 0, false, null, false));
        button("请假（超额仍可登记）", () -> record(i, Action.LEAVE, 0, false, null, false));
        if (store.phone) {
            button("修正实际完成时间", () -> {
                correction(i);
            });
        }
    }

    private void record(Instance i, Action a, int minutes, boolean earlier, Long actual, boolean correction) {
        store.record(i, a, minutes, earlier, actual, correction);
        scheduleReminders(false);
        detail(i);
    }

    private EditText field(String label, String initial) {
        text(label, 14);
        EditText e = new EditText(this);
        ScheduleUi.field(e);
        e.setText(initial);
        e.setSingleLine(true);
        e.setContentDescription(label);
        content.addView(e);
        return e;
    }

    private EditText dateField(String label, String initial, boolean optional) {
        EditText input = field(label, initial);
        DateTimeFields.date(input, optional);
        return input;
    }

    private String value(EditText e) {
        return e.getText().toString().trim();
    }

    private int integer(EditText e) {
        return Integer.parseInt(value(e));
    }

    private Spinner choice(String label, String[] choices, int initial) {
        text(label, 14);
        Spinner s = new Spinner(this);
        ScheduleUi.choice(s);
        s.setContentDescription(label);
        s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, choices));
        s.setSelection(initial);
        content.addView(s);
        return s;
    }

    private void plans() {
        page = "plans";
        screen("日程");
        for (Plan p : store.plans()) {
            if (!p.deleted) {
                button(Kinds.label(p.kind) + " · " + p.category + " / " + p.title, () -> plan(p));
            }
        }
    }

    private void reminderSettings() {
        require(store.phone, "请在手机处理提醒设置");
        page = "reminderSettings";
        screen("提醒设置待处理");
        text("原日程和记录保留。请编辑未来日程，选择手机提醒或不提醒；手机提醒还受总开关和系统权限控制。", 14);
        button("修改默认提醒与总开关", this::settings);
        Models.Settings settings = store.settings();
        for (Plan plan : store.plans()) {
            if (com.canace.x3schedule.domain.ReminderScope.pending(plan, settings)) {
                button(plan.title + " · 提醒设置待处理", () -> plan(plan));
            }
        }
    }

    private void reminderAccess() {
        android.app.NotificationManager notifications = getSystemService(android.app.NotificationManager.class);
        android.app.NotificationChannel channel = notifications.getNotificationChannel("schedule");
        if (!notifications.areNotificationsEnabled() || channel != null
                && channel.getImportance() == android.app.NotificationManager.IMPORTANCE_NONE) {
            text("手机日程通知已被系统关闭，当前不会提醒；手表不会兜底。请在系统设置中允许通知。", 14);
        }
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(android.app.AlarmManager.class)
                .canScheduleExactAlarms()) {
            text("准时提醒权限未开启，手机提醒可能延迟。请在“连接与权限”允许准时提醒。", 14);
        }
    }

    private void plan(Plan p) {
        page = "plan";
        screen(p.title);
        session.plan = Wire.copy(p, Plan.class);
        if (com.canace.x3schedule.domain.ReminderScope.pending(p, store.settings())) {
            text("旧设置只选择了手表提醒，当前不会提醒。请编辑未来日程，选择手机提醒或不提醒。", 14);
        }
        button("编辑", () -> chooseEdit(p, null));
        if (p.kind != Kind.TODO) {
            button("仅本次调整 / 取消", () -> exception(p));
            button("确认公司排班争议日", () -> roster(p));
        }
        button(p.kind == Kind.TODO ? "移除待办" : "删除未来日程", () -> confirmDelete(p, null, 2));
    }

    private void chooseEdit(Plan plan, Instance item) {
        if (plan.kind == Kind.TODO) {
            editor(Wire.copy(plan, Plan.class));
            return;
        }
        if (com.canace.x3schedule.domain.CalendarEditing.once(plan)) {
            editor(Wire.copy(plan, Plan.class), futureDate(plan).toString(), null);
            return;
        }
        if (item == null) {
            chooseSeriesEdit(plan);
            return;
        }
        new android.app.AlertDialog.Builder(this).setTitle("编辑重复日程 · 选择影响范围")
                .setItems(new String[] { "仅这一次", "这一次及以后", "整个系列（仅未来，历史保留）" },
                        (dialog, which) -> safe(() -> openEdit(plan, item, which)))
                .setNegativeButton("取消", null).show();
    }

    private void chooseSeriesEdit(Plan plan) {
        new android.app.AlertDialog.Builder(this).setTitle("编辑重复日程 · 选择影响范围")
                .setItems(new String[] { "整个系列（仅未来，历史保留）", "从指定日期起" }, (dialog, which) -> {
                    if (which == 0) {
                        editor(Wire.copy(plan, Plan.class), futureDate(plan).toString(), null);
                    } else {
                        CalendarDateDialog.show(this, "从哪天起修改", futureDate(plan).atStartOfDay(), true,
                                date -> safe(() -> {
                                    require(!date.toLocalDate().isBefore(futureDate(plan)), "请选择未来有效日期");
                                    editor(Wire.copy(plan, Plan.class), date.toLocalDate().toString(), null);
                                }));
                    }
                }).setNegativeButton("取消", null).show();
    }

    private LocalDate futureDate(Plan plan) {
        LocalDate today = LocalDate.now(ZoneId.of(plan.zone));
        LocalDate anchor = LocalDate.parse(plan.anchor);
        return today.isBefore(anchor) ? anchor : today;
    }

    private void openEdit(Plan plan, Instance item, int scope) {
        if (scope == 2) {
            editor(Wire.copy(plan, Plan.class), futureDate(plan).toString(), null);
            return;
        }
        require(item.start > System.currentTimeMillis(), "这次日程已开始，历史保持不变；可选择整个系列调整未来日程");
        if (scope == 1) {
            editor(PlanEdits.followingDraft(plan, LocalDate.parse(item.date)), item.date, null);
            return;
        }
        Plan draft = PlanEdits.at(plan, LocalDate.parse(item.date));
        require(store.events().stream().noneMatch(event -> event.instanceId.equals(item.id)),
                "这次日程已有记录，历史保持不变；可调整之后的安排");
        draft.previous.clear();
        draft.effectiveFrom = "";
        draft.exceptions.clear();
        draft.anchor = Instant.ofEpochMilli(item.start).atZone(ZoneId.of(item.zone)).toLocalDate().toString();
        draft.title = item.title;
        draft.category = item.category;
        draft.startMinute = Instant.ofEpochMilli(item.start)
                .atZone(ZoneId.of(item.zone)).toLocalTime().toSecondOfDay() / 60;
        draft.durationMinutes = item.dateOnly ? 0 : (int) ((item.end - item.start) / 60000);
        draft.daySpan = item.daySpan;
        draft.until = "";
        draft.rule = Rule.DAYS;
        draft.count = 1;
        draft.interval = 1;
        editor(draft, null, item.date);
    }

    private void chooseDelete(Plan plan, Instance item) {
        String[] labels = { "仅这一次", "这一次及以后", "整个系列（未来日程）" };
        if (plan.kind == Kind.TODO || com.canace.x3schedule.domain.CalendarEditing.once(plan)) {
            confirmDelete(plan, item, 2);
            return;
        }
        new android.app.AlertDialog.Builder(this).setTitle("删除重复日程 · 选择影响范围")
                .setItems(labels, (dialog, which) -> safe(() -> confirmDelete(plan, item, which)))
                .setNegativeButton("取消", null).show();
    }

    private void confirmDelete(Plan plan, Instance item, int scope) {
        String label = scope == 0 ? "仅删除这一次" : scope == 1 ? "删除这一次及以后" : "删除此日程的未来安排";
        new android.app.AlertDialog.Builder(this).setTitle(label).setMessage("已开始日程和完成记录将保留。确认删除？")
                .setNegativeButton("取消", null).setPositiveButton("确认删除", (dialog, which) -> safe(() -> {
                    Plan changed = Wire.copy(plan, Plan.class);
                    if (scope == 0) {
                        require(item.start > System.currentTimeMillis(), "已开始日程保持历史，不再删除");
                        ExceptionDay skip = new ExceptionDay();
                        skip.skip = true;
                        changed = PlanEdits.exception(plan, LocalDate.parse(item.date), skip,
                                System.currentTimeMillis());
                    } else if (scope == 1) {
                        changed = PlanEdits.removeFrom(plan, LocalDate.parse(item.date), System.currentTimeMillis());
                    } else {
                        changed.deleted = true;
                    }
                    store.save(changed);
                    scheduleReminders(false);
                    home();
                })).show();
    }

    private void correction(Instance instance) {
        page = "correct";
        screen("补录实际时间");
        session.instanceId = instance.id;
        EditText input = field("格式：2026-09-16T08:30", LocalDateTime.now().withSecond(0).withNano(0).toString());
        DateTimeFields.dateTime(input, instance.zone, false);
        button("确认人工修正", () -> record(instance, Action.COMPLETE, 0, false,
                LocalDateTime.parse(DateTimeFields.isoDateTime(input))
                .atZone(ZoneId.of(instance.zone)).toInstant().toEpochMilli(), true));
    }

    private void editor(Plan p) {
        editor(p, null, null);
    }

    private void editor(Plan p, String from, String occurrence) {
        require(store.phone, "仅手机编辑");
        page = "editor";
        screen("编辑日程");
        session.plan = Wire.copy(p, Plan.class);
        session.editFrom = from;
        session.editOccurrence = occurrence;
        if (p.kind == Kind.TODO) {
            new TodoEditor(this, content, session.plan, draft -> preview(Collections.singletonList(draft),
                    false)).show();
            return;
        }
        if (occurrence != null) {
            text("仅修改 " + occurrence + " 这一次", 14);
        } else if (from != null) {
            text("从 " + from + " 起生效，已开始和已有记录的日程保留。", 14);
        }
        new PlanEditor(this, content, session.plan, occurrence != null,
                draft -> safe(() -> previewEdit(draft, from, occurrence))).show();
    }

    private void previewEdit(Plan draft, String from, String occurrence) {
        Plan original = store.plans().stream().filter(p -> p.id.equals(draft.id)).findFirst().orElse(null);
        Plan changed = draft;
        if (original != null && occurrence != null) {
            require(com.canace.x3schedule.domain.CalendarEditing.start(draft).atZone(ZoneId.of(draft.zone))
                    .toInstant().toEpochMilli() > System.currentTimeMillis(), "新时间须晚于当前时间");
            ExceptionDay exception = new ExceptionDay();
            exception.title = draft.title;
            exception.category = draft.category;
            exception.scheduledDate = draft.anchor;
            exception.minute = draft.startMinute;
            exception.duration = draft.durationMinutes;
            exception.daySpan = draft.daySpan;
            changed = PlanEdits.exception(original, LocalDate.parse(occurrence), exception, System.currentTimeMillis());
        } else if (original != null) {
            LocalDate effective = from == null ? LocalDate.now(ZoneId.of(original.zone)) : LocalDate.parse(from);
            if (effective.isBefore(LocalDate.parse(original.anchor))) {
                effective = LocalDate.parse(original.anchor);
            }
            changed = PlanEdits.from(original, draft, effective, System.currentTimeMillis());
        }
        preview(Collections.singletonList(changed), false);
    }

    private String weekdayText(int mask) {
        StringBuilder b = new StringBuilder();
        for (int n = 1; n <= 7; n++) {
            if ((mask & (1 << (n - 1))) != 0) {
                b.append(n);
            }
        }
        return b.toString();
    }

    private void preview(List<Plan> drafts, boolean importing) {
        preview(drafts, importing, 0);
    }

    private void preview(List<Plan> drafts, boolean importing, int position) {
        List<Plan> existing = store.plans();
        Models.Settings reminderSettings = store.settings();
        for (Plan draft : drafts) {
            Plan prior = existing.stream().filter(plan -> plan.id.equals(draft.id)).findFirst().orElse(null);
            com.canace.x3schedule.domain.ReminderScope.requireSave(draft, prior, reminderSettings);
        }
        page = "preview";
        screen("预览后保存");
        session.drafts = drafts;
        session.importing = importing;
        if (importing) {
            text("重复 UID 合并到同一计划；请核对后确认。", 14);
        }
        ScrollView target = scroll;
        new PlanPreviewScreen(this, content, drafts, this::home, () -> target.scrollTo(0, position)).show();
    }

    private void natural() {
        page = "natural";
        screen("自然语言预览");
        EditText input = field("本地解析，不上传", "每周一三五早上 6:30 到 7:30 晨跑");
        button("生成预览", () -> preview(Collections.singletonList(NaturalLanguage.preview(value(input), LocalDate
                .now())), false));
    }

    private void exception(Plan p) {
        page = "exception";
        screen("仅本次调整");
        session.plan = Wire.copy(p, Plan.class);
        EditText date = dateField("日期", LocalDate.now().toString(), false);
        if (p.dateOnly) {
            text("全天提醒可取消所选日期的一次提醒；时间精度请在修改日程中设置。", 14);
            button("预览取消本次提醒", () -> {
                ExceptionDay cancelled = new ExceptionDay();
                cancelled.skip = true;
                Plan changed = PlanEdits.exception(p, LocalDate.parse(value(date)), cancelled,
                        System.currentTimeMillis());
                preview(Collections.singletonList(changed), false);
            });
            return;
        }
        EditText minute = field("新的开始时间", "06:30");
        DateTimeFields.time(minute);
        EditText duration = field("分钟数", "" + p.durationMinutes);
        Spinner action = choice("处理", new String[] { "调整时间", "因计划调整取消" }, 0);
        button("预览本次变更", () -> {
            String d = value(date);
            ExceptionDay x = new ExceptionDay();
            LocalTime t = LocalTime.parse(value(minute));
            x.minute = t.getHour() * 60 + t.getMinute();
            x.duration = integer(duration);
            x.skip = action.getSelectedItemPosition() == 1;
            Plan changed = PlanEdits.exception(p, LocalDate.parse(d), x, System.currentTimeMillis());
            preview(Collections.singletonList(changed), false);
        });
    }

    private void roster(Plan p) {
        page = "roster";
        screen("排班确认");
        session.plan = Wire.copy(p, Plan.class);
        for (String date : WorkCalendar.disputedDates(p, LocalDate.now(ZoneId.of(p.zone)))) {
            text("● " + date + " 有争议", 16).setTextColor(getColor(R.color.schedule_error));
        }
        EditText date = dateField("日期", LocalDate.now().toString(), false);
        Spinner type = choice("设置来源", new String[] { "用户确认", "公司排班", "法定日历手工更新" }, 0);
        Spinner day = choice("当天", new String[] { "工作日", "休息日" }, 0);
        button("预览排班变更", () -> {
            String d = value(date);
            Plan changed = PlanEdits.roster(p, LocalDate.parse(d),
                    PlanEdits.RosterSource.values()[type.getSelectedItemPosition()],
                    day.getSelectedItemPosition() == 0, System.currentTimeMillis());
            preview(Collections.singletonList(changed), false);
        });
        button("导入公司排班文件", () -> {
            calendarPlan = p;
            calendarCompany = true;
            pick(23);
        });
        button("导入法定日历更新", () -> {
            calendarPlan = p;
            calendarCompany = false;
            pick(23);
        });
        text("内置已核验的 2025、2026 年全国日历。其他年份须本地更新；缺失时暂用周一至周五。文本每行：YYYY-MM-DD=WORK 或 REST。", 14);
    }

    private void settings() {
        page = "settings";
        screen("提醒与请假额度");
        Models.Settings s = store.settings();
        EditText defaultReminder = field("默认提前提醒分钟", "" + s.reminderMinutes);
        EditText dayReminder = field("全天提醒与按天截止的默认提醒时间",
                LocalTime.MIN.plusMinutes(s.dateOnlyReminderMinute).toString());
        DateTimeFields.time(dayReminder);
        text("未设置时使用上午 9 点；此时间不改变待办当天结束时的截止日期。", 14);
        text("手机负责提醒；手表负责查看、记录和同步。关闭手机提醒后，手表不会兜底。", 14);
        text("开始、结束和你主动延后的确认提醒使用系统闹钟，可能显示“下一个闹钟”标记。"
                + "未确认时约15/60分钟后再次提醒，系统省电可能延迟；待确认记录始终保留。", 14);
        reminderAccess();
        boolean legacyTargets = s.reminderTargets == 2;
        int initialTarget = legacyTargets ? 2 : s.reminderTargets & 1;
        Spinner defaultTargets = choice("默认提醒端", legacyTargets
                ? new String[] { "不提醒", "手机", "旧设置待处理（当前不提醒）" }
                : new String[] { "不提醒", "手机" }, initialTarget);
        Spinner phoneEnabled = choice("手机提醒总开关", new String[] { "开启", "关闭" }, s.phoneRemindersEnabled ? 0 : 1);
        EditText week = field("全局每周请假额度", "" + s.leaveWeek);
        EditText month = field("全局每月请假额度", "" + s.leaveMonth);
        EditText category = field("大类覆盖（可空）", "");
        EditText cw = field("该大类每周额度", "1");
        EditText cm = field("该大类每月额度", "4");
        EditText quietStart = field("静音开始 HH:mm", String.format(Locale.ROOT, "%02d:%02d", s.quietStart / 60, s
                .quietStart % 60));
        EditText quietEnd = field("静音结束 HH:mm", String.format(Locale.ROOT, "%02d:%02d", s.quietEnd / 60, s
                .quietEnd % 60));
        DateTimeFields.time(quietStart);
        DateTimeFields.time(quietEnd);
        primaryButton("保存设置", () -> {
            s.reminderMinutes = integer(defaultReminder);
            s.dateOnlyReminderMinute = TimeRange.minute(value(dayReminder));
            require(s.reminderMinutes >= 0 && s.reminderMinutes <= 1440, "提醒分钟无效");
            if (defaultTargets.getSelectedItemPosition() != initialTarget) {
                s.reminderTargets = defaultTargets.getSelectedItemPosition();
            }
            s.phoneRemindersEnabled = phoneEnabled.getSelectedItemPosition() == 0;
            s.watchRemindersEnabled = false;
            s.leaveWeek = integer(week);
            s.leaveMonth = integer(month);
            require(s.leaveWeek >= 0 && s.leaveMonth >= 0, "额度不能小于 0");
            if (!value(category).isEmpty()) {
                require(integer(cw) >= 0 && integer(cm) >= 0, "额度不能小于 0");
                s.categoryWeek.put(value(category), integer(cw));
                s.categoryMonth.put(value(category), integer(cm));
            }
            LocalTime a = LocalTime.parse(value(quietStart));
            LocalTime b = LocalTime.parse(value(quietEnd));
            s.quietStart = a.getHour() * 60 + a.getMinute();
            s.quietEnd = b.getHour() * 60 + b.getMinute();
            store.settings(s);
            scheduleReminders(false);
            home();
        });
    }

    private void suggestions() {
        page = "suggestions";
        screen("本地优化建议");
        new SuggestionScreen(this, content).show();
    }

    private void capacity() {
        page = "capacity";
        screen("数据容量");
        new CapacityScreen(this, content).show();
    }

    private void files() {
        page = "files";
        screen("本地备份与 ICS");
        text("备份包含私人日程和记录，未加密。仅通过你选择的文件位置保存；保管好备份。恢复按版本和事件 ID 合并，不清空现有数据。分块备份上限 32 MiB，兼容旧 JSON 备份。", 14);
        button("导出备份", () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "schedule.x3backup"), 24));
        button("恢复备份（先预览）", () -> pick(21));
        button("导出未来 180 天 ICS（不含待办）", () -> {
            long now = System.currentTimeMillis();
            List<Instance> out = new ArrayList<>();
            for (Instance i : store.instances()) {
                if (i.kind != Kind.TODO && i.start >= now && i.start <= now + 180L * 86400000) {
                    out.add(i);
                }
            }
            export(Ics.export(out).getBytes(StandardCharsets.UTF_8), "text/calendar", "schedule.ics");
        });
        button("导入 ICS（先预览）", () -> pick(22));
        text("ICS 仅交换带时间的执行型/仅提醒型单次事件，不含待办或完成记录；完整数据请用分块备份。外部事件默认仅提醒型。", 14);
    }

    private void export(byte[] b, String mime, String name) {
        exportBytes = b;
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent
                .CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name), 20);
    }

    private void pick(int code) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent
                .CATEGORY_OPENABLE), code);
    }

    @Override
    public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PresenceBinding.REQUEST && Build.VERSION.SDK_INT >= 31) {
            safe(() -> companionResult(result));
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (request == 21 || request == 24) {
            backupDocument(request, data.getData());
            return;
        }
        safe(() -> {
            try {
                if (request == 20) {
                    writeDocument(data.getData());
                } else {
                    previewImport(request, readDocument(data.getData()));
                }
            } catch (IOException error) {
                toast("文件读写失败，原数据保留");
            }
        });
    }

    private void welcome() {
        page = "welcome";
        screen("欢迎使用日程");
        PrivacyScreen.explain(content, store.phone);
        primaryButton("开始使用", () -> {
            store.putMeta("privacy_notice", "1");
            home();
            resumeFeatures();
        });
    }

    private void privacy() {
        page = "privacy";
        screen("隐私与本机数据");
        PrivacyScreen.explain(content, store.phone);
        if (store.phone) {
            button("先导出备份", this::files);
        }
        PrivacyScreen.resetButton(this, content);
    }

    @android.annotation.TargetApi(31)
    private void companionResult(int result) {
        if (result == RESULT_OK) {
            PresenceBinding.observe(this, store.meta("peer", ""));
            runtime.reconnect();
        }
        if (page.equals("connection")) {
            connection();
        }
    }

    private void backupDocument(int request, android.net.Uri uri) {
        page = "backup";
        screen(request == 24 ? "导出备份" : "恢复预览");
        BackupScreen backup = new BackupScreen(this, content);
        if (request == 24) {
            backup.export(uri);
        } else {
            backup.preview(uri);
        }
    }

    private void writeDocument(android.net.Uri uri) throws IOException {
        require(exportBytes != null, "导出已过期，请重新发起");
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            require(out != null, "无法写入");
            out.write(exportBytes);
        }
        exportBytes = null;
        toast("已导出");
    }

    private byte[] readDocument(android.net.Uri uri) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            require(in != null, "无法读取");
            byte[] part = new byte[4096];
            int count;
            while ((count = in.read(part)) != -1) {
                require(buffer.size() + count <= Wire.MAX_BYTES, "文件超过 2 MiB");
                buffer.write(part, 0, count);
            }
        }
        return buffer.toByteArray();
    }

    private String documentText(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }

    private void previewImport(int request, byte[] bytes) throws IOException {
        if (request == 23) {
            require(calendarPlan != null, "导入已过期，请重新选择计划");
            Map<String, Boolean> days = WorkCalendar.preview(documentText(bytes));
            Plan draft = Wire.copy(calendarPlan, Plan.class);
            WorkCalendar.apply(draft, days, calendarCompany);
            preview(Collections.singletonList(draft), true);
        } else if (request == 22) {
            preview(Ics.preview(documentText(bytes)), true);

        }
    }

    private void connection() {
        page = "connection";
        screen("连接与权限");
        text("分批同步需要手机和手表都升级到 0.5.0 或兼容版本。先在系统蓝牙设置配对，两端各选择对方。"
                + "Android 12+ 手机可授权伴随设备以后台重连；手表后台连接服务保持可用。系统强行停止或省电限制可能中断连接，恢复后自动补传。", 14);
        button("授予提醒 / 蓝牙权限", this::requestFeaturePermissions);
        button("选择已配对设备", this::peers);
        if (store.meta("sync_paused", "false").equals("true")) {
            text("本机同步已暂停。恢复后，另一台设备保留的数据可能重新传入。", 14);
            button("恢复双端同步", () -> {
                store.putMeta("sync_paused", "false");
                runtime.reconnect();
                startLink();
                connection();
            });
        }
        if (store.phone && Build.VERSION.SDK_INT >= 31) {
            phoneBackgroundConnection();
        }
        if (!store.phone) {
            button("切换手表后台连接", () -> {
                boolean enabled = !store.meta("background_sync", "true").equals("true");
                store.putMeta("background_sync", Boolean.toString(enabled));
                if (enabled) {
                    WatchConnectionService.startIfConfigured(this);
                } else {
                    stopService(new Intent(this, WatchConnectionService.class));
                }
                toast(enabled ? "后台连接已开启" : "后台连接已关闭");
            });
        }
        button("系统蓝牙设置", () -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
        if (store.phone && Build.VERSION.SDK_INT >= 31) {
            button("允许准时提醒", () -> startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(android.net.Uri.parse("package:" + getPackageName()))));
        }
        text("关闭手机提醒不会发送手机同步状态通知。系统禁用通知、强行停止或限制后台后，提醒可靠性会受影响。", 13);
    }

    @android.annotation.TargetApi(31)
    private void phoneBackgroundConnection() {
        String address = store.meta("peer", "");
        boolean associated = PresenceBinding.isAssociated(this, address);
        text("所选手表后台自动连接：" + (associated ? "已授权" : "未授权"), 14);
        text("授权状态不代表当前已连接。关闭授权会保留蓝牙配对和日程数据。", 13);
        if (associated) {
            button("关闭手机后台自动连接", () -> {
                PresenceBinding.disassociate(this, address);
                connection();
                toast("已关闭后台自动连接");
            });
        } else {
            button("授权手机后台自动连接", () -> PresenceBinding.associate(this, address));
        }
    }

    private void requestFeaturePermissions() {
        List<String> ps = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            ps.add(Manifest.permission.BLUETOOTH_CONNECT);
            ps.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            if (store.phone) {
                ps.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ps.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!ps.isEmpty()) {
            requestPermissions(ps.toArray(new String[0]), 8);
        }
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != 8 || page.equals("welcome")) {
            return;
        }
        runtime.reconnect();
        startLink();
        scheduleReminders(true);
        if (page.equals("connection")) {
            safe(this::connection);
        }
    }

    private void peers() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission
                .BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            toast("请先授予蓝牙权限");
            return;
        }
        page = "peers";
        screen("选择已配对设备");
        BluetoothAdapter a = getSystemService(BluetoothManager.class).getAdapter();
        require(a != null, "没有蓝牙");
        for (BluetoothDevice d : a.getBondedDevices()) {
            button((d.getName() == null ? "蓝牙设备" : d.getName()) + "\n" + d.getAddress(), () -> {
                store.putMeta("peer", d.getAddress());
                store.putMeta("sync_paused", "false");
                runtime.reconnect();
                startLink();
                home();
            });
        }
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent e) {
        if (!store.phone) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getX();
                downY = e.getY();
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP && e.getX() - downX > dp(70) && Math.abs(e
                    .getY() - downY) < dp(45)) {
                if (page.equals("home") || page.equals("welcome")) {
                    finish();
                } else {
                    home();
                }
                return true;
            }
        }
        return super.dispatchTouchEvent(e);
    }

    @Override
    public void onBackPressed() {
        if (page.equals("home") || page.equals("welcome")) {
            super.onBackPressed();
        } else {
            home();
        }
    }
}
