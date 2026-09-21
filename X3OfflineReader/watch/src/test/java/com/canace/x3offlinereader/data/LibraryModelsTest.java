package com.canace.x3offlinereader.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class LibraryModelsTest {
    @Test
    void favoriteBookDoesNotShowAStarInTheLibrary() {
        LibraryModels.BookSummary book =
                new LibraryModels.BookSummary("book", "公开小说", "作者", "uri", true);
        assertEquals("公开小说\n作者", book.toString());
    }
}
