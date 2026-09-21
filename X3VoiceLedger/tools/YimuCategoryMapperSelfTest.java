package com.canace.x3voiceledger.phone;

public final class YimuCategoryMapperSelfTest {
    public static void main(String[] args) {
        expect("餐饮", "晚饭", "食品餐饮", "晚餐");
        expect("餐饮", "咖啡", "食品餐饮", "饮料酒水");
        expect("交通", "坐地铁", "出行交通", "公共交通");
        expect("医疗", "医院挂号", "健康医疗", "医院");
        expect("收入", "本月工资", "收入", "工资");
        expect("购物", "买衣服", "购物消费", "服饰运动");
        expect("交通", "打车去喝咖啡", "出行交通", "打车");
        expect("娱乐", "看电影", "休闲娱乐", "电影唱歌");
        expect("其他", "普通支出", "其他", "");
        System.out.println("YimuCategoryMapperSelfTest: all cases passed");
    }

    private static void expect(String category, String note, String parent, String child) {
        YimuCategoryMapper.Mapping actual = YimuCategoryMapper.map(category, note);
        if (!parent.equals(actual.parent) || !child.equals(actual.child)) {
            throw new AssertionError(
                    category + "/" + note + " => " + actual.parent + "/" + actual.child);
        }
    }
}
