package com.canace.x3wolainotes;

final class AppConfig {
    static final String API_BASE_URL = "https://openapi.wolai.com/v1";
    static final String TARGET_PAGE_LABEL = "手表同步";
    static final String FAVORITES_PAGE_LABEL = "手表收藏";
    static final int MAX_NOTE_LENGTH = 240;
    static final int MAX_READER_BLOCKS = 200;
    static final int MAX_READER_DEPTH = 4;
    static final int MAX_DATABASE_COLUMNS = 64;
    static final int MAX_DATABASE_ROWS = 200;
    static final int MAX_REMOTE_TEXT_LENGTH = 12_000;
    static final int MAX_CELL_TEXT_LENGTH = 2_000;
    static final int MAX_JSON_DEPTH = 8;

    static boolean isConfiguredPage(String pageId) {
        return ConfigValidator.isConfigured(pageId)
                && ApiInputPolicy.isValidResourceId(pageId);
    }

    private AppConfig() {
    }
}
