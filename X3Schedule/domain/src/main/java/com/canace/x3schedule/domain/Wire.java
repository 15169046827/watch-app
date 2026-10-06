package com.canace.x3schedule.domain;

import static com.canace.x3schedule.domain.Models.require;

import com.canace.x3schedule.domain.Models.Event;
import com.canace.x3schedule.domain.Models.Instance;
import com.canace.x3schedule.domain.Models.Kind;
import com.canace.x3schedule.domain.Models.Plan;
import com.canace.x3schedule.domain.Models.Settings;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Version 2 bounded snapshots. ACK is bound to the exact transmitted body; retries retain the same message id.
 */
public final class Wire {

    public static final Gson JSON = new Gson();

    public static final int MAX_BYTES = 2 * 1024 * 1024;

    public static final int MAX_ITEMS = 12000;

    private Wire() {
    }

    public static class Snapshot {

        public int protocol = 2;

        public int schema = 4;

        public String id = UUID.randomUUID().toString();

        public String source;

        public String deviceId;

        public List<Plan> plans = new ArrayList<>();

        public List<Instance> instances = new ArrayList<>();

        public List<Event> events = new ArrayList<>();

        public Settings settings = new Settings();

        public long settingsVersion;

        public long revision;
    }

    public static class Ack {

        public int protocol = 2;

        public String messageId;

        public String digest;
    }

    public static <T> T copy(T value, Class<T> type) {
        return JSON.fromJson(JSON.toJson(value), type);
    }

    public static byte[] encode(Snapshot s) {
        validate(s);
        byte[] bytes = JSON.toJson(s).getBytes(StandardCharsets.UTF_8);
        require(bytes.length <= MAX_BYTES, "同步超过 2 MiB");
        return bytes;
    }

    public static Snapshot decode(byte[] bytes) {
        return decode(bytes, false);
    }

    public static Snapshot decodeBackup(byte[] bytes) {
        return decode(bytes, true);
    }

    private static Snapshot decode(byte[] bytes, boolean allowLegacy) {
        try {
            Snapshot s = JSON.fromJson(SnapshotInput.checkedText(bytes), Snapshot.class);
            if (allowLegacy && s.protocol == 1 && s.schema == 2) {
                validateLegacy(s);
                s.protocol = 2;
                s.schema = 3;
            }
            if (allowLegacy && s.protocol == 2 && s.schema == 3) {
                validateMinutePrecision(s);
                s.schema = 4;
            }
            validate(s);
            return s;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("数据格式或版本无效");
        }
    }

    private static void validateLegacy(Snapshot snapshot) {
        require("phone".equals(snapshot.source), "旧格式仅用于手机备份恢复");
        for (Plan plan : snapshot.plans) {
            require(plan.kind == Kind.EXECUTION, "旧备份不能包含新类型");
            for (Plan segment : plan.previous) {
                require(segment.kind == Kind.EXECUTION, "旧备份不能包含新类型");
            }
        }
        for (Instance instance : snapshot.instances) {
            require(instance.kind == Kind.EXECUTION && instance.timed, "旧备份实例类型无效");
        }
        for (Event event : snapshot.events) {
            require(event.action != Models.Action.REOPEN, "旧备份动作无效");
            require(event.snapshot == null || (event.snapshot.kind == Kind.EXECUTION && event.snapshot.timed),
                    "旧备份执行快照类型无效");
        }
    }

    private static void validateMinutePrecision(Snapshot snapshot) {
        for (Plan plan : snapshot.plans) {
            validateOldPlan(plan);
            for (Plan segment : plan.previous) {
                validateOldPlan(segment);
            }
        }
        for (Instance instance : snapshot.instances) {
            validateOldInstance(instance);
        }
        for (Event event : snapshot.events) {
            if (event.snapshot != null) {
                validateOldInstance(event.snapshot);
            }
        }
        require(snapshot.settings.dateOnlyReminderMinute == 540, "旧备份不能包含按天提醒设置");
    }

    private static void validateOldPlan(Plan plan) {
        require(!plan.dateOnly && plan.daySpan == 1 && plan.durationMinutes <= 1440
                && plan.rule != Models.Rule.MONTH_DAY && plan.rule != Models.Rule.YEARLY, "旧备份不能包含新日历语义");
        for (Models.ExceptionDay value : plan.exceptions.values()) {
            require(value.duration <= 1440 && value.title == null && value.category == null
                    && value.scheduledDate == null && value.daySpan == null, "旧备份不能包含新单次调整");
        }
    }

    private static void validateOldInstance(Instance item) {
        require(!item.dateOnly && item.daySpan == 1 && item.end - item.start <= 86400000L,
                "旧备份不能包含新日历语义");
    }

    public static void validate(Snapshot s) {
        validate(s, MAX_ITEMS);
    }

    static void validate(Snapshot s, int maxItems) {
        validateEnvelope(s, maxItems);
        validateAuthority(s);
        validatePlans(s.plans);
        validateInstances(s.instances);
        validateEvents(s.events);
        validateSettings(s);
    }

    private static void validateEnvelope(Snapshot s, int maxItems) {
        require(s != null && s.protocol == 2 && s.schema == 4, "不支持的数据版本");
        require(s.id != null && s.id.matches("[A-Za-z0-9_-]{1,80}")
                && s.deviceId != null && s.deviceId.matches("[A-Za-z0-9_-]{1,80}"), "来源无效");
        require("phone".equals(s.source) || "watch".equals(s.source), "来源无效");
        require(s.plans != null && s.instances != null && s.events != null, "数据缺失");
        require(s.plans.size() <= 1000 && s.instances.size() <= maxItems && s.events.size() <= maxItems, "数据过多");
    }

    private static void validateAuthority(Snapshot s) {
        require(s.source.equals("phone") || (s.plans.isEmpty() && s.instances.isEmpty()
                && s.events.stream().noneMatch(e -> e.correction)), "手表不能编辑计划或人工修正");
    }

    private static void validatePlans(List<Plan> plans) {
        Set<String> ids = new HashSet<>();
        for (Plan plan : plans) {
            plan.validate();
            require(ids.add(plan.id), "重复计划");
        }
    }

    private static void validateInstances(List<Instance> instances) {
        Set<String> ids = new HashSet<>();
        for (Instance instance : instances) {
            validateInstance(instance);
            require(ids.add(instance.id), "重复实例");
        }
    }

    private static void validateInstance(Instance i) {
        require(i != null && i.id != null && i.planId != null
                && i.id.equals(i.planId + ":" + i.date), "实例无效");
        java.time.LocalDate.parse(i.date);
        java.time.ZoneId.of(i.zone);
        require(i.steps != null && i.steps.size() <= 30, "实例步骤无效");
        require(i.reminderMinutes >= 0 && i.reminderMinutes <= 1440
                && i.reminderTargets >= 0 && i.reminderTargets <= 3, "实例提醒无效");
        validateInstanceTiming(i);
    }

    private static void validateInstanceTiming(Instance i) {
        Kinds.validateInstance(i);
        require(i.title != null && i.title.length() <= 160 && i.category != null && i.category.length() <= 80, "实例无效");
    }

    private static void validateEvents(List<Event> events) {
        Set<String> ids = new HashSet<>();
        for (Event event : events) {
            event.validate();
            require(ids.add(event.id), "重复事件");
        }
    }

    private static void validateSettings(Snapshot s) {
        require(s.settings != null && s.settingsVersion >= 0 && s.revision >= 0, "设置无效");
        s.settings.validate();
    }

    public static String digest(byte[] body) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder b = new StringBuilder();
            for (byte v : d) {
                b.append(String.format(Locale.ROOT, "%02x", v & 255));
            }
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static Ack ack(Snapshot s, byte[] body) {
        Ack a = new Ack();
        a.messageId = s.id;
        a.digest = digest(body);
        return a;
    }

    public static boolean accepts(Ack a, Snapshot s, byte[] body) {
        return a != null && a.protocol == 2 && s.id.equals(a.messageId) && digest(body).equals(a.digest);
    }

    public static Plan mergePlan(Plan old, Plan incoming, String source) {
        require("phone".equals(source), "只有手机可编辑日程");
        if (old == null || incoming.version > old.version) {
            if (old != null) {
                Kinds.requireChange(old, incoming);
            }
            return incoming;
        }
        if (incoming.version == old.version) {
            require(JSON.toJson(old).equals(JSON.toJson(incoming)), "同版本内容冲突");
        }
        return old;
    }

    public static List<Event> mergeEvents(Collection<Event> old, Collection<Event> incoming) {
        Map<String, Event> result = new TreeMap<>();
        for (Event e : old) {
            result.put(e.id, e);
        }
        for (Event e : incoming) {
            e.validate();
            Event prior = result.get(e.id);
            require(prior == null || JSON.toJson(prior).equals(JSON.toJson(e)), "事件 ID 冲突");
            result.put(e.id, e);
        }
        return new ArrayList<>(result.values());
    }
}
