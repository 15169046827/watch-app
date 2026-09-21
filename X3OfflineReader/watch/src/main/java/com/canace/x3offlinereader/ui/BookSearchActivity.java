package com.canace.x3offlinereader.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.canace.x3offlinereader.R;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import com.canace.x3offlinereader.importer.BookSourceAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Round-safe, book-scoped search over the metadata index. */
public final class BookSearchActivity extends SwipeBackActivity {
    private LibraryDb db;
    private String bookId;
    private EditText query;
    private Button search;
    private TextView status;
    private final List<BookSearchEngine.Result> results = new ArrayList<>();
    private ArrayAdapter<BookSearchEngine.Result> adapter;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_book_search);
        enableSwipeBack(R.id.root);
        bookId = getIntent().getStringExtra("book_id");
        ((TextView) findViewById(R.id.search_book_title))
                .setText(getIntent().getStringExtra("book_title"));
        db = new LibraryDb(this);
        query = findViewById(R.id.search_query);
        search = findViewById(R.id.search_submit);
        status = findViewById(R.id.search_status);
        adapter =
                new ArrayAdapter<>(this, R.layout.item_search_result, android.R.id.text1, results);
        ListView list = findViewById(R.id.search_results);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(results.get(position)));
        search.setOnClickListener(v -> search());
        query.setOnEditorActionListener(
                (v, action, event) -> {
                    search();
                    return true;
                });
        query.clearFocus();
    }

    private void search() {
        String value = query.getText().toString().trim();
        if (value.isEmpty()) {
            Toast.makeText(this, R.string.search_query_required, Toast.LENGTH_SHORT).show();
            return;
        }
        if (value.codePointCount(0, value.length()) > BookSearchEngine.MAX_QUERY_CODE_POINTS) {
            Toast.makeText(this, R.string.search_query_too_long, Toast.LENGTH_SHORT).show();
            return;
        }
        hideKeyboard();
        search.setEnabled(false);
        query.setEnabled(false);
        status.setText(R.string.searching);
        results.clear();
        adapter.notifyDataSetChanged();
        List<LibraryModels.ChapterSummary> chapters = db.chapters(bookId);
        boolean accepted =
                LifecycleExecutor.execute(
                        executor,
                        () -> {
                            BookSearchEngine.Outcome outcome = null;
                            try {
                                outcome = BookSearchEngine.search(chapters, value);
                            } catch (Exception ignored) {
                            }
                            BookSearchEngine.Outcome completed = outcome;
                            runOnUiThread(
                                    () -> {
                                        if (destroyed) {
                                            return;
                                        }
                                        search.setEnabled(true);
                                        query.setEnabled(true);
                                        if (completed == null) {
                                            status.setText(R.string.search_failed);
                                            return;
                                        }
                                        results.addAll(completed.results);
                                        adapter.notifyDataSetChanged();
                                        status.setText(
                                                completed.truncated
                                                        ? getString(
                                                                R.string.search_result_limited,
                                                                results.size())
                                                        : getString(
                                                                R.string.search_result_count,
                                                                results.size()));
                                    });
                        });
        if (!accepted) {
            search.setEnabled(true);
            query.setEnabled(true);
            status.setText(R.string.search_failed);
        }
    }

    private void open(BookSearchEngine.Result result) {
        LibraryModels.BookStorage storage = db.storage(bookId);
        if (storage == null) {
            return;
        }
        if (storage.indexedPackage() && !BookSourceAccess.exists(this, storage.sourceUri)) {
            Toast.makeText(this, R.string.book_source_missing, Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(
                new Intent(this, PlayerActivity.class)
                        .putExtra("book_id", bookId)
                        .putExtra("start_track_id", result.trackId)
                        .putExtra("start_position_ms", result.trackPositionMs));
    }

    private void hideKeyboard() {
        Object service = getSystemService(Context.INPUT_METHOD_SERVICE);
        if (service instanceof InputMethodManager) {
            ((InputMethodManager) service).hideSoftInputFromWindow(query.getWindowToken(), 0);
        }
        query.clearFocus();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        executor.shutdownNow();
        if (db != null) {
            db.close();
        }
        super.onDestroy();
    }
}
