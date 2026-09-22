package com.canace.x3offlinereader;

import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.importer.BookImportCoordinator;
import com.canace.x3offlinereader.importer.BookMediaCache;
import com.canace.x3offlinereader.importer.BookSourceAccess;
import com.canace.x3offlinereader.importer.OpbookImporter;
import com.canace.x3offlinereader.playback.PlaybackService;
import com.canace.x3offlinereader.playback.PlaybackTargetStore;
import com.canace.x3offlinereader.ui.PlayerActivity;
import com.canace.x3offlinereader.ui.RoundChoiceDialog;
import com.canace.x3offlinereader.ui.StoragePicker;
import com.canace.x3offlinereader.ui.SwipeBackActivity;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Round-safe indexed novel library. Selected packages remain at their original SAF location. */
public final class BookLibraryActivity extends SwipeBackActivity {
    private static final int PICK_OPBOOK = 101;
    private LibraryDb db;
    private final List<LibraryModels.BookSummary> books = new ArrayList<>();
    private ArrayAdapter<LibraryModels.BookSummary> adapter;
    private final BookImportCoordinator.Listener importListener = this::renderImportState;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_book_library);
        enableSwipeBack(R.id.root);
        db = new LibraryDb(this);
        adapter = new ArrayAdapter<>(this, R.layout.item_library_entry, android.R.id.text1, books);
        ListView list = findViewById(R.id.book_list);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openBook(books.get(position)));
        list.setOnItemLongClickListener(
                (parent, view, position, id) -> {
                    offerDelete(books.get(position));
                    return true;
                });
        findViewById(R.id.choose_button).setOnClickListener(view -> choosePackages());
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        BookImportCoordinator.get().addListener(importListener);
    }

    @Override
    protected void onStop() {
        BookImportCoordinator.get().removeListener(importListener);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (db == null) {
            return;
        }
        books.clear();
        books.addAll(db.books());
        adapter.notifyDataSetChanged();
        BookImportCoordinator.State state = BookImportCoordinator.get().state();
        if (!state.busy) {
            ((TextView) findViewById(R.id.inbox_status))
                    .setText(
                            state.status.isEmpty()
                                    ? getString(R.string.book_library_count, books.size())
                                    : state.status);
        }
    }

    private void renderImportState(BookImportCoordinator.State state) {
        if (isFinishing()) {
            return;
        }
        findViewById(R.id.choose_button).setEnabled(!state.busy);
        if (state.busy || !state.status.isEmpty()) {
            ((TextView) findViewById(R.id.inbox_status)).setText(state.status);
        }
        if (!state.busy) {
            refresh();
        }
    }

    private void openBook(LibraryModels.BookSummary book) {
        if (!BookSourceAccess.exists(this, book.sourceUri)) {
            Toast.makeText(this, R.string.book_source_missing, Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(
                new Intent(this, PlayerActivity.class)
                        .putExtra("book_id", book.id)
                        .putExtra("resume_latest", true));
    }

    private void choosePackages() {
        Intent intent =
                new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(
                Intent.EXTRA_MIME_TYPES,
                new String[] {
                    "application/zip", "application/octet-stream", "application/x-zip-compressed"
                });
        startActivityForResult(StoragePicker.startInMusic(intent), PICK_OPBOOK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_OPBOOK || resultCode != RESULT_OK || data == null) {
            return;
        }
        int grants = persistableGrants(data);
        List<BookImportCoordinator.Source> sources = new ArrayList<>();
        try {
            requireReadGrant(grants);
            sources.addAll(importSources(data));
            persistGrants(sources, grants);
        } catch (Exception failure) {
            releaseUnused(sources);
            Toast.makeText(this, getString(R.string.persist_source_failed), Toast.LENGTH_LONG)
                    .show();
            return;
        }
        if (!BookImportCoordinator.get().start(this, sources)) {
            releaseUnused(sources);
            Toast.makeText(this, R.string.import_already_running, Toast.LENGTH_SHORT).show();
        }
    }

    private static int persistableGrants(Intent data) {
        return data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    private static void requireReadGrant(int grants) {
        if ((grants & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            throw new SecurityException();
        }
    }

    private List<BookImportCoordinator.Source> importSources(Intent data) {
        Set<Uri> uris = new LinkedHashSet<>();
        ClipData clips = data.getClipData();
        if (clips == null) {
            if (data.getData() != null) {
                uris.add(data.getData());
            }
        } else {
            for (int i = 0; i < clips.getItemCount(); i++) {
                uris.add(clips.getItemAt(i).getUri());
            }
        }
        List<BookImportCoordinator.Source> sources = new ArrayList<>();
        for (Uri uri : uris) {
            String name = displayName(uri, "book.opbook");
            if (!name.toLowerCase(Locale.ROOT).endsWith(".opbook")) {
                throw new IllegalArgumentException("只支持 .opbook 文件");
            }
            sources.add(new BookImportCoordinator.Source(uri, name));
        }
        return sources;
    }

    private void persistGrants(List<BookImportCoordinator.Source> sources, int grants) {
        int permissions = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if ((grants & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            permissions |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        for (BookImportCoordinator.Source source : sources) {
            getContentResolver().takePersistableUriPermission(source.uri, permissions);
        }
    }

    private void releaseUnused(List<BookImportCoordinator.Source> sources) {
        for (BookImportCoordinator.Source source : sources) {
            if (!db.containsSource(source.uri.toString())) {
                BookSourceAccess.release(this, source.uri.toString());
            }
        }
    }
    private String displayName(Uri uri, String fallback) {
        try (Cursor c =
                getContentResolver()
                        .query(
                                uri,
                                new String[] {OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
            return c != null && c.moveToFirst() && !c.isNull(0) ? c.getString(0) : fallback;
        }
    }

    private void offerDelete(LibraryModels.BookSummary book) {
        if (BookImportCoordinator.get().state().busy) {
            Toast.makeText(this, R.string.import_already_running, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] choices =
                book.sourceUri == null || book.sourceUri.isEmpty()
                        ? new String[] {getString(R.string.delete_book_data)}
                        : new String[] {
                            getString(R.string.remove_from_library),
                            getString(R.string.delete_book_with_source)
                        };
        RoundChoiceDialog.show(
                this,
                getString(R.string.delete_book_title, book.title),
                choices,
                -1,
                which -> confirmDelete(book, which == 1));
    }

    private void confirmDelete(LibraryModels.BookSummary book, boolean source) {
        String label =
                source
                        ? getString(R.string.confirm_delete_source)
                        : getString(R.string.confirm_remove_book);
        RoundChoiceDialog.show(
                this,
                label,
                new String[] {getString(R.string.confirm), getString(android.R.string.cancel)},
                -1,
                which -> {
                    if (which == 0) {
                        deleteBook(book, source);
                    }
                });
    }

    private void deleteBook(LibraryModels.BookSummary book, boolean source) {
        findViewById(R.id.choose_button).setEnabled(false);
        new Thread(
                        () -> {
                            LibraryModels.BookStorage storage = db.storage(book.id);
                            boolean sourceDeleted =
                                    !source
                                            || storage == null
                                            || !storage.indexedPackage()
                                            || BookSourceAccess.delete(this, storage.sourceUri);
                            if (sourceDeleted && storage != null) {
                                stopService(new Intent(this, PlaybackService.class));
                                PlaybackTargetStore.clear(this);
                                db.removeBook(book.id);
                                BookMediaCache.clear(this, book.id);
                                OpbookImporter.delete(new File(storage.rootPath));
                                releaseGrant(storage.sourceUri);
                            }
                            runOnUiThread(
                                    () -> {
                                        findViewById(R.id.choose_button).setEnabled(true);
                                        refresh();
                                        Toast.makeText(
                                                        this,
                                                        sourceDeleted
                                                                ? R.string.book_removed
                                                                : R.string.source_delete_failed,
                                                        Toast.LENGTH_LONG)
                                                .show();
                                    });
                        },
                        "book-delete")
                .start();
    }

    private void releaseGrant(String sourceUri) {
        BookSourceAccess.release(this, sourceUri);
    }

    @Override
    protected void onDestroy() {
        if (db != null) {
            db.close();
        }
        super.onDestroy();
    }
}
