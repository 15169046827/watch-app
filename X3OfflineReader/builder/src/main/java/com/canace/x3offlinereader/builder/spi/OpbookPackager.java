package com.canace.x3offlinereader.builder.spi;

import java.nio.file.Path;

/** Validates a staged v1 package tree, writes checksums, then emits an .opbook ZIP. */
public interface OpbookPackager {
    void packageDirectory(Path stagedDirectory, Path outputOpbook) throws Exception;
}

