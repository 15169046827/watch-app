package com.canace.x3schedule.shared;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.widget.LinearLayout;
import android.widget.Toast;

/** Local data disclosure and explicit system-managed reset of this application only. */
final class PrivacyScreen {
    private PrivacyScreen() {
    }

    static void explain(LinearLayout content, boolean phone) {
        ScheduleUi.heading(content, "日常工具 · 日程");
        ScheduleUi.text(content, phone ? "在手机安排日历、提醒和待办，在手表查看并记录完成情况。"
                : "在手表查看日程和提醒、完成待办。日历安排和备份请在手机操作。", 15);
        ScheduleUi.heading(content, "数据保存在本机");
        ScheduleUi.text(content, "无需账户，不连接互联网，也没有云端备份。日程、完成记录和设置保存在应用私有目录；"
                + "选择已配对设备后，才通过蓝牙同步到另一台设备。", 14);
        ScheduleUi.heading(content, "权限由你选择");
        ScheduleUi.text(content, "蓝牙权限用于双端同步；通知和准时提醒权限用于提醒。拒绝后仍可查看与管理本机数据，"
                + "可在“连接与权限”重新设置。后台连接另行开启，系统省电或强行停止可能延迟提醒和同步。", 14);
        ScheduleUi.heading(content, "备份与清除");
        ScheduleUi.text(content, "手机可导出包含日程和完成记录的备份；文件未加密，仅写到你选择的位置。"
                + "清除入口在“隐私与本机数据”。清除本机不会删除另一台设备的数据或已导出的文件；"
                + "再次选择设备同步时，对方保留的数据可能重新传入。", 14);
        ScheduleUi.text(content, "发布者：Ym_Li · 日程 0.6.0\n本应用没有账户口令或云端凭据。", 13);
    }

    static void resetButton(Activity activity, LinearLayout content) {
        ScheduleUi.heading(content, "按类别管理本机数据");
        ScheduleUi.button(content, "清除本机日程与记录…", () -> confirmCategory(activity,
                DataMaintenance.Kind.BUSINESS, "本机日程与记录", "删除本机全部日程、实例、完成记录、建议历史和同步副本。"
                        + "保留提醒设置和设备选择，暂停同步；另一台设备的数据及外部备份不删除。恢复同步时数据可能重新传入。"));
        ScheduleUi.button(content, "清除缓存…", () -> confirmCategory(activity,
                DataMaintenance.Kind.CACHE, "缓存", "删除本应用私有临时文件及未完成的同步副本；保留日程、记录、设置和设备选择。"
                        + "未完成的同步需要重新传输，不删除已导出的文件。"));
        ScheduleUi.button(content, "清除设备连接配置…", () -> confirmCategory(activity,
                DataMaintenance.Kind.CONNECTION, "设备连接配置", "停止本机自动同步，清除应用内设备选择和本应用后台关联。"
                        + "保留日程、记录、系统蓝牙配对和已授予的权限；重新连接需再次选择设备并授权后台关联。"));
        ScheduleUi.heading(content, "清除本机全部数据");
        ScheduleUi.text(content, "删除本应用的日程、完成记录、设置和私有缓存，并重置本应用权限、通知和文件访问授权。"
                + "应用将关闭，下次打开重新设置。系统蓝牙配对、另一台设备和你导出的备份文件不在清除范围内。"
                + "手表尚未传到手机的记录也会丢失，请先同步并在手机备份。", 14);
        ScheduleUi.button(content, "清除本机全部数据…", () -> confirm(activity));
    }

    private static void confirmCategory(Activity activity, DataMaintenance.Kind kind, String label, String scope) {
        new AlertDialog.Builder(activity).setTitle("清除" + label + "？").setMessage(scope)
                .setNegativeButton("取消", null).setPositiveButton("继续确认", (dialog, which) ->
                        new AlertDialog.Builder(activity).setTitle("确认清除" + label)
                                .setMessage("请确认已完成需要的同步和备份。" + scope)
                                .setNegativeButton("保留数据", null)
                                .setPositiveButton("确认清除", (second, choice) -> DataMaintenance.run(activity, kind))
                                .show()).show();
    }

    private static void confirm(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("先确认数据已备份")
                .setMessage("本机未同步或未备份的日程与记录将无法恢复。此操作不会替你清除另一台设备，也不会删除外部备份文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续确认", (dialog, which) -> finalConfirmation(activity)).show();
    }

    private static void finalConfirmation(Activity activity) {
        new AlertDialog.Builder(activity).setTitle("确认清除这台设备的日程数据？")
                .setMessage("将删除本机全部日程、记录、设置和缓存，并重置本应用权限。应用会关闭，此操作无法撤销。")
                .setNegativeButton("保留数据", null)
                .setPositiveButton("确认清除", (dialog, which) -> clear(activity)).show();
    }

    private static void clear(Activity activity) {
        if (DataMaintenance.active()) {
            return;
        }
        try {
            ActivityManager manager = activity.getSystemService(ActivityManager.class);
            if (manager == null || !manager.clearApplicationUserData()) {
                throw new IllegalStateException("Data reset was not accepted");
            }
        } catch (RuntimeException error) {
            Toast.makeText(activity, "系统未能执行清除，请到系统应用设置中检查。", Toast.LENGTH_LONG).show();
        }
    }
}
