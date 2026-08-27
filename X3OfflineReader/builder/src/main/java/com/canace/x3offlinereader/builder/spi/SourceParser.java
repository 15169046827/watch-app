package com.canace.x3offlinereader.builder.spi;

import com.canace.x3offlinereader.builder.model.BookModel;
import java.nio.file.Path;

/** Boundary for user-owned, no-DRM EPUB/TXT parsing. */
public interface SourceParser {
    boolean supports(Path source);
    BookModel.Volume parse(Path source, int volumeOrder) throws Exception;
}
