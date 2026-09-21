package com.canace.x3offlinereader.importer;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import com.canace.x3offlinereader.data.LibraryDb;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide single import queue so page recreation cannot unlock a live task. */
public final class BookImportCoordinator {

    public interface Listener {

        void onState(State state);
    }

    public static final class Source {

        public final Uri uri;

        public final String displayName;

        public Source(Uri uri, String displayName) {
            this.uri = uri;
            this.displayName = displayName;
        }
    }

    public static final class State {

        public final boolean busy;

        public final String status;

        State(boolean busy, String status) {
            this.busy = busy;
            this.status = status;
        }
    }

    private static final BookImportCoordinator INSTANCE = new BookImportCoordinator();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Handler main = new Handler(Looper.getMainLooper());

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private volatile State state = new State(false, "");

    private BookImportCoordinator() { }

    public static BookImportCoordinator get() {
        return INSTANCE;
    }

    public State state() {
        return state;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onState(state);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized boolean start(Context context, List<Source> selected) {
        if (state.busy || selected.isEmpty()) {
            return false;
        }
        Context app = context.getApplicationContext();
        List<Source> sources = new ArrayList<>(selected);
        publish(new State(true, "准备建立索引"));
        executor.execute(() -> run(app, sources));
        return true;
    }

    private void run(Context context, List<Source> sources) {
        int added = 0;
        int skipped = 0;
        int failed = 0;
        String lastError = null;
        try (LibraryDb db = new LibraryDb(context)) {
            OpbookImporter importer = new OpbookImporter(context, db);
            for (int i = 0; i < sources.size(); i++) {
                Source source = sources.get(i);
                String uri = source.uri.toString();
                if (db.containsSource(uri)) {
                    skipped++;
                    continue;
                }
                publish(
                        new State(
                                true,
                                source.displayName
                                        + " 校验并建立索引（"
                                        + (i + 1)
                                        + "/"
                                        + sources.size()
                                        + "）"));
                try {
                    importer.indexDocument(source.uri);
                    added++;
                } catch (OpbookImporter.AlreadyImportedException duplicate) {
                    skipped++;
                    BookSourceAccess.release(context, uri);
                } catch (Exception failure) {
                    failed++;
                    lastError = source.displayName + "：" + safeMessage(failure);
                    BookSourceAccess.release(context, uri);
                }
            }
        } catch (Exception failure) {
            failed++;
            lastError = safeMessage(failure);
        }
        String result =
                "已添加 "
                        + added
                        + " 本"
                        + (skipped > 0 ? " · 跳过 " + skipped + " 本" : "")
                        + (failed > 0 ? " · 失败 " + failed + " 本" : "");
        if (lastError != null) {
            result += "\n" + lastError;
        }
        publish(new State(false, result));
    }

    private static String safeMessage(Exception failure) {
        if (failure instanceof BookSourceAccess.SourceMissingException
                || failure instanceof SecurityException) {
            return "源文件不存在或已失去访问权限";
        }
        if (failure instanceof IllegalArgumentException) {
            return "文件格式或校验不通过";
        }
        return "文件访问或索引写入失败";
    }

    private void publish(State next) {
        state = next;
        main.post(
                () -> {
                    for (Listener listener : listeners) {
                        listener.onState(next);
                    }
                });
    }
}
