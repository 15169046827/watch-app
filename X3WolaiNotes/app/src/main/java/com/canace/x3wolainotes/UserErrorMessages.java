package com.canace.x3wolainotes;

final class UserErrorMessages {
    private UserErrorMessages() {
    }

    static String syncStatus(int status) {
        if (status == 401) return "授权已失效 · 请重新配置";
        if (status == 403) return "没有同步页面权限";
        if (status == 404) return "同步页面不可用";
        if (status == 429) return "请求过快 · 稍后重试";
        if (status >= 500) return "Wolai 服务暂时不可用";
        return "网络不可用 · 稍后重试";
    }

    static String readerStatus(int status) {
        if (status == 401) return "授权已失效 · 请重新配置";
        if (status == 403) return "没有页面权限 · 请检查 Wolai 授权";
        if (status == 404) return "页面或表格不可用";
        if (status == 429) return "请求过快 · 稍后刷新";
        if (status >= 500) return "Wolai 服务暂时不可用";
        return "网络不可用 · 请联网后重试";
    }
}
