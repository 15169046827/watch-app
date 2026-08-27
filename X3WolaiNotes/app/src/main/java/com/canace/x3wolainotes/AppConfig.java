package com.canace.x3wolainotes;

final class AppConfig {
    static final String API_BASE_URL = "https://openapi.wolai.com/v1";
    static final String TARGET_PAGE_ID = BuildConfig.WOLAI_TARGET_PAGE_ID;
    static final String TARGET_PAGE_LABEL = "手表同步";
    static final String FAVORITES_PAGE_ID = BuildConfig.WOLAI_FAVORITES_PAGE_ID;
    static final String FAVORITES_PAGE_LABEL = "手表收藏";
    static final int MAX_NOTE_LENGTH = 240;
    static final int MAX_READER_BLOCKS = 200;
    static final int MAX_READER_DEPTH = 4;

    private AppConfig() {
    }
}
