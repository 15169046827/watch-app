package com.canace.x3offlinereader.builder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cooperative stop signal used to preserve reusable work before leaving the GUI. */
public final class BuildControl {
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final Path externalStopFile;

    public BuildControl(){this(null);}
    public BuildControl(Path externalStopFile){this.externalStopFile=externalStopFile==null?null:externalStopFile.toAbsolutePath().normalize();}

    public void requestStop() { stopRequested.set(true); }
    public boolean isStopRequested() { return stopRequested.get()||(externalStopFile!=null&&Files.exists(externalStopFile)); }

    public void checkpoint() throws PauseRequestedException {
        if (isStopRequested() || Thread.currentThread().isInterrupted()) {
            throw new PauseRequestedException();
        }
    }

    public static final class PauseRequestedException extends Exception {
        public PauseRequestedException() { super("转换进度已保存，可在下次启动时继续"); }
        public PauseRequestedException(String message) { super(message); }
    }
}
