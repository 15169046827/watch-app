package com.canace.x3voiceledger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

final class WatchProfileStore {
    private static final String PREFS = "ledger_profile";
    private static final String KEY_JSON = "profile_json";
    private final SharedPreferences preferences;

    WatchProfileStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean apply(byte[] value) {
        try {
            JSONObject object = new JSONObject(new String(value, StandardCharsets.UTF_8));
            int schema = object.optInt("schema");
            if (!"ledgerProfile".equals(object.optString("type")) || schema < 1 || schema > 2) {
                return false;
            }
            preferences.edit().putString(KEY_JSON, object.toString()).apply();
            return true;
        } catch (JSONException error) {
            return false;
        }
    }

    String inferAccount(String rawText) {
        JSONObject profile = profile();
        JSONArray accounts = profile.optJSONArray("accounts");
        if (accounts != null) {
            for (int i = 0; i < accounts.length(); i++) {
                String account = accounts.optString(i).trim();
                if (!account.isEmpty() && rawText.contains(account)) return account;
            }
        }
        return profile.optString("defaultAccount").trim();
    }

    String bookName() {
        return profile().optString("bookName").trim();
    }

    String tags() {
        return profile().optString("tags").trim();
    }

    CategorySelection categoryFor(String broadCategory, String note) {
        String text = note == null ? "" : note;
        String parent;
        String child = "";
        if ("收入".equals(broadCategory)) {
            parent = "收入";
            child = first(text, "退款", "红包", "中奖", "理财盈利", "礼金人情", "借入",
                    "奖金", "兼职外快", "工资", "二手闲置", "补贴", "报销");
        } else if ("餐饮".equals(broadCategory) || "食品餐饮".equals(broadCategory)) {
            parent = "食品餐饮";
            if (contains(text, "早餐", "早饭")) child = "早餐";
            else if (contains(text, "午餐", "午饭")) child = "午餐";
            else if (contains(text, "晚餐", "晚饭")) child = "晚餐";
            else if (contains(text, "咖啡", "奶茶", "饮料", "酒水")) child = "饮料酒水";
            else if (contains(text, "零食")) child = "休闲零食";
            else if (contains(text, "水果", "蔬菜", "生鲜", "买菜")) child = "生鲜食品";
        } else if ("交通".equals(broadCategory) || "出行交通".equals(broadCategory)) {
            parent = "出行交通";
            if (contains(text, "打车", "滴滴", "出租")) child = "打车";
            else if (contains(text, "地铁", "公交")) child = "公共交通";
            else child = first(text, "共享单车", "巴士", "停车费", "加油", "火车", "飞机", "保养修车");
        } else if ("医疗".equals(broadCategory) || "健康医疗".equals(broadCategory)) {
            parent = "健康医疗";
            if (contains(text, "医院", "体检", "门诊", "挂号")) child = "医院";
            else if (contains(text, "药")) child = "买药";
        } else if ("娱乐".equals(broadCategory) || "休闲娱乐".equals(broadCategory)) {
            parent = "休闲娱乐";
            child = first(text, "游戏", "旅游度假", "电影唱歌", "运动健身", "足浴按摩",
                    "棋牌桌游", "酒吧", "演出");
        } else if ("购物".equals(broadCategory) || "购物消费".equals(broadCategory)) {
            parent = "购物消费";
        } else {
            parent = broadCategory == null || broadCategory.trim().isEmpty() ? "其他" : broadCategory.trim();
        }
        if (!categoryExists(parent, child)) return new CategorySelection(parent, "");
        return new CategorySelection(parent, child);
    }

    String summary() {
        JSONObject profile = profile();
        JSONArray accounts = profile.optJSONArray("accounts");
        int count = accounts == null ? 0 : accounts.length();
        JSONArray books = profile.optJSONArray("books");
        int bookCount = books == null ? (bookName().isEmpty() ? 0 : 1) : books.length();
        if (count == 0 && bookName().isEmpty()) return "尚未从手机接收账务配置";
        return "账户 " + count + " 个 · 账本 " + bookCount + " 个"
                + (bookName().isEmpty() ? "" : " · 默认 " + bookName());
    }

    private boolean categoryExists(String parent, String child) {
        JSONArray categories = profile().optJSONArray("categories");
        if (categories == null || categories.length() == 0) return true;
        for (int i = 0; i < categories.length(); i++) {
            JSONObject item = categories.optJSONObject(i);
            if (item == null || !parent.equals(item.optString("name"))) continue;
            if (child.isEmpty()) return true;
            JSONArray children = item.optJSONArray("children");
            if (children == null) return false;
            for (int j = 0; j < children.length(); j++) if (child.equals(children.optString(j))) return true;
            return false;
        }
        return false;
    }

    private JSONObject profile() {
        try {
            return new JSONObject(preferences.getString(KEY_JSON, "{}"));
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    private static boolean contains(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String first(String text, String... values) {
        for (String value : values) if (text.contains(value)) return value;
        return "";
    }

    static final class CategorySelection {
        final String parent;
        final String child;

        CategorySelection(String parent, String child) {
            this.parent = parent;
            this.child = child;
        }
    }
}
