package com.canace.x3voiceledger.phone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class YimuCategoryMapper {
    private static final List<Parent> CATALOG = Collections.unmodifiableList(Arrays.asList(
            parent("健康医疗", "滋补保健", "医院", "买药"),
            parent("送礼人情", "还款", "请客", "孝敬长辈", "礼物", "借出", "红包", "打赏"),
            parent("其他", "保险", "意外损失", "理财", "邮费", "会员", "罚款赔偿", "理财支出", "慈善捐助"),
            parent("文化教育", "学费", "书报杂志", "培训考试"),
            parent("出差", "续签", "话费", "住宿", "交通"),
            parent("居家生活", "话费宽带", "电费", "水费", "燃气费", "物业费", "房租还贷", "车位费", "家政清洁"),
            parent("休闲娱乐", "游戏", "旅游度假", "电影唱歌", "运动健身", "足浴按摩", "棋牌桌游", "酒吧", "演出"),
            parent("出行交通", "共享单车", "巴士", "打车", "公共交通", "停车费", "加油", "火车", "飞机", "保养修车"),
            parent("食品餐饮", "早餐", "午餐", "晚餐", "饮料酒水", "休闲零食", "生鲜食品", "请客吃饭", "粮油调味"),
            parent("购物消费", "休闲娱乐", "日常家居", "个护美妆", "手机数码", "虚拟充值", "生活电器", "配饰腕表", "母婴玩具", "服饰运动", "宠物用品", "办公用品", "装修装饰"),
            parent("收入", "退款", "红包", "其他", "中奖", "理财盈利", "礼金人情", "借入", "奖金", "兼职外快", "工资", "二手闲置", "补贴", "报销"),
            parent("滑雪", "门票", "补给", "住宿", "交通", "装备")
    ));

    private YimuCategoryMapper() { }

    static List<Parent> catalog() {
        return CATALOG;
    }

    static List<Mapping> suggestions(String category, String note) {
        List<Mapping> result = new ArrayList<>();
        Mapping primary = map(category, note);
        addUnique(result, primary);
        Parent parent = findParent(primary.parent);
        if (parent != null) {
            for (String child : parent.children) {
                addUnique(result, new Mapping(parent.name, child));
                if (result.size() == 3) break;
            }
        }
        return result;
    }

    static Mapping map(String category, String note) {
        String c = category == null ? "" : category.trim();
        String text = (note == null ? "" : note) + " " + c;

        if ("收入".equals(c)) return new Mapping("收入", incomeChild(text));
        if ("餐饮".equals(c) || "食品餐饮".equals(c)) return new Mapping("食品餐饮", foodChild(text));
        if ("交通".equals(c) || "出行交通".equals(c)) return new Mapping("出行交通", transportChild(text));
        if ("医疗".equals(c) || "健康医疗".equals(c)) return new Mapping("健康医疗", healthChild(text));
        if ("娱乐".equals(c) || "休闲娱乐".equals(c)) return new Mapping("休闲娱乐", leisureChild(text));
        if ("购物".equals(c) || "购物消费".equals(c)) return new Mapping("购物消费", shoppingChild(text));
        if ("居家生活".equals(c)) return new Mapping(c, homeChild(text));
        if ("文化教育".equals(c)) return new Mapping(c, educationChild(text));
        if ("送礼人情".equals(c)) return new Mapping(c, giftChild(text));
        if ("出差".equals(c) || "滑雪".equals(c)) return new Mapping(c, "");
        if ("其他".equals(c)) return new Mapping("其他", otherChild(text));

        if (contains(text, "工资", "奖金", "兼职", "补贴", "报销", "理财盈利", "中奖",
                "借入", "二手闲置")) return new Mapping("收入", incomeChild(text));
        if (contains(text, "早餐", "午餐", "午饭", "晚餐", "晚饭", "咖啡", "奶茶", "饮料",
                "零食", "水果", "生鲜", "吃饭")) return new Mapping("食品餐饮", foodChild(text));
        if (contains(text, "地铁", "公交", "打车", "滴滴", "出租", "停车", "加油", "火车",
                "机票", "飞机", "修车")) return new Mapping("出行交通", transportChild(text));
        if (contains(text, "医院", "买药", "药品", "保健", "体检", "门诊", "挂号")) {
            return new Mapping("健康医疗", healthChild(text));
        }
        if (contains(text, "游戏", "旅游", "电影", "唱歌", "健身", "按摩", "棋牌", "酒吧",
                "演出")) return new Mapping("休闲娱乐", leisureChild(text));
        if (contains(text, "衣服", "鞋", "数码", "家居", "美妆", "电器", "玩具", "宠物",
                "办公", "装修")) return new Mapping("购物消费", shoppingChild(text));
        return new Mapping("其他", otherChild(text));
    }

    private static String incomeChild(String text) {
        return first(text, new String[]{"退款", "红包", "中奖", "理财盈利", "礼金人情", "借入",
                "奖金", "兼职外快", "工资", "二手闲置", "补贴", "报销", "其他"});
    }

    private static String foodChild(String text) {
        if (contains(text, "早餐", "早饭")) return "早餐";
        if (contains(text, "午餐", "午饭")) return "午餐";
        if (contains(text, "晚餐", "晚饭")) return "晚餐";
        if (contains(text, "咖啡", "奶茶", "饮料", "酒水")) return "饮料酒水";
        if (contains(text, "零食")) return "休闲零食";
        if (contains(text, "水果", "蔬菜", "生鲜", "买菜")) return "生鲜食品";
        if (contains(text, "请客", "聚餐")) return "请客吃饭";
        if (contains(text, "米", "面", "粮油", "调味")) return "粮油调味";
        return "";
    }

    private static String transportChild(String text) {
        if (contains(text, "共享单车", "单车")) return "共享单车";
        if (contains(text, "巴士")) return "巴士";
        if (contains(text, "打车", "滴滴", "出租")) return "打车";
        if (contains(text, "地铁", "公交")) return "公共交通";
        if (contains(text, "停车")) return "停车费";
        if (contains(text, "加油")) return "加油";
        if (contains(text, "火车", "高铁")) return "火车";
        if (contains(text, "机票", "飞机")) return "飞机";
        if (contains(text, "修车", "保养")) return "保养修车";
        return "";
    }

    private static String healthChild(String text) {
        if (contains(text, "保健", "滋补")) return "滋补保健";
        if (contains(text, "医院", "体检", "门诊", "挂号")) return "医院";
        if (contains(text, "买药", "药品", "药")) return "买药";
        return "";
    }

    private static String leisureChild(String text) {
        if (contains(text, "旅游", "度假")) return "旅游度假";
        if (contains(text, "电影", "唱歌", "KTV")) return "电影唱歌";
        if (contains(text, "运动", "健身")) return "运动健身";
        if (contains(text, "足浴", "按摩")) return "足浴按摩";
        if (contains(text, "棋牌", "桌游")) return "棋牌桌游";
        return first(text, new String[]{"游戏", "酒吧", "演出"});
    }

    private static String shoppingChild(String text) {
        if (contains(text, "美妆", "护肤")) return "个护美妆";
        if (contains(text, "手机", "电脑", "数码")) return "手机数码";
        if (contains(text, "充值", "会员充值")) return "虚拟充值";
        if (contains(text, "电器")) return "生活电器";
        if (contains(text, "手表", "首饰", "配饰")) return "配饰腕表";
        if (contains(text, "母婴", "玩具")) return "母婴玩具";
        if (contains(text, "衣服", "鞋", "服饰", "运动装备")) return "服饰运动";
        if (contains(text, "宠物")) return "宠物用品";
        if (contains(text, "办公")) return "办公用品";
        if (contains(text, "装修")) return "装修装饰";
        if (contains(text, "家居", "日用品")) return "日常家居";
        return "";
    }

    private static String homeChild(String text) {
        return first(text, new String[]{"话费宽带", "电费", "水费", "燃气费", "物业费", "房租还贷",
                "车位费", "家政清洁"});
    }

    private static String educationChild(String text) {
        return first(text, new String[]{"学费", "书报杂志", "培训考试"});
    }

    private static String giftChild(String text) {
        return first(text, new String[]{"还款", "请客", "孝敬长辈", "礼物", "借出", "红包", "打赏"});
    }

    private static String otherChild(String text) {
        return first(text, new String[]{"保险", "意外损失", "理财支出", "理财", "邮费", "会员",
                "罚款赔偿", "慈善捐助"});
    }

    private static String first(String text, String[] values) {
        for (String value : values) if (text.contains(value)) return value;
        return "";
    }

    private static boolean contains(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static Parent parent(String name, String... children) {
        return new Parent(name, Collections.unmodifiableList(Arrays.asList(children)));
    }

    private static Parent findParent(String name) {
        for (Parent parent : CATALOG) if (parent.name.equals(name)) return parent;
        return null;
    }

    private static void addUnique(List<Mapping> values, Mapping candidate) {
        for (Mapping value : values) {
            if (value.parent.equals(candidate.parent) && value.child.equals(candidate.child)) return;
        }
        values.add(candidate);
    }

    static final class Parent {
        final String name;
        final List<String> children;

        Parent(String name, List<String> children) {
            this.name = name;
            this.children = children;
        }
    }

    static final class Mapping {
        final String parent;
        final String child;

        Mapping(String parent, String child) {
            this.parent = parent;
            this.child = child;
        }

        String path() {
            return child.isEmpty() ? parent : parent + " → " + child;
        }

        boolean isIncome() {
            return "收入".equals(parent);
        }
    }
}
