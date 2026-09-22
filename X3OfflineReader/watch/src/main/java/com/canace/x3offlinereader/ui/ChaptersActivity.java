package com.canace.x3offlinereader.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import com.canace.x3offlinereader.R;
import com.canace.x3offlinereader.data.LibraryDb;
import com.canace.x3offlinereader.data.LibraryModels;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ChaptersActivity extends SwipeBackActivity {

    private LibraryDb db;

    private String bookId;

    private String bookTitle;

    private ListView list;

    private Button resume;

    private final List<LibraryModels.ChapterSummary> chapters = new ArrayList<>();

    private final List<String> labels = new ArrayList<>();

    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_chapters);
        enableSwipeBack(R.id.root);
        bookId = getIntent().getStringExtra("book_id");
        bookTitle = getIntent().getStringExtra("book_title");
        ((TextView) findViewById(R.id.title)).setText(bookTitle);
        db = new LibraryDb(this);
        list = findViewById(R.id.chapter_list);
        resume = findViewById(R.id.resume_button);
        adapter = new ArrayAdapter<>(this, R.layout.item_chapter_entry, android.R.id.text1, labels);
        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) ->
                        startActivity(
                                new Intent(this, PlayerActivity.class)
                                        .putExtra("book_id", bookId)
                                        .putExtra("chapter_id", chapters.get(position).id)));
        findViewById(R.id.search_button)
                .setOnClickListener(
                        v ->
                                startActivity(
                                        new Intent(this, BookSearchActivity.class)
                                                .putExtra("book_id", bookId)
                                                .putExtra("book_title", bookTitle)));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        chapters.clear();
        chapters.addAll(db.chapters(bookId));
        LibraryModels.ResumePoint latest = db.latestProgress(bookId);
        labels.clear();
        int latestIndex = -1;
        LibraryModels.ChapterSummary savedChapter = null;
        for (int i = 0; i < chapters.size(); i++) {
            LibraryModels.ChapterSummary chapter = chapters.get(i);
            String numbered = ChapterLabels.numbered(i + 1, chapter.title);
            if (latest != null && chapter.id.equals(latest.chapterId)) {
                labels.add(getString(R.string.last_played, numbered));
                latestIndex = i;
                savedChapter = chapter;
            } else {
                labels.add(numbered);
            }
        }
        adapter.notifyDataSetChanged();
        resume.setOnClickListener(null);
        if (savedChapter == null) {
            resume.setText(R.string.no_listening_history);
            resume.setEnabled(false);
        } else {
            resume.setEnabled(true);
            resume.setText(
                    getString(
                            R.string.resume_listening,
                            savedChapter.title,
                            format(latest.chapterPositionMs)));
            resume.setOnClickListener(
                    v ->
                            startActivity(
                                    new Intent(this, PlayerActivity.class)
                                            .putExtra("book_id", bookId)
                                            .putExtra("resume_latest", true)));
        }
        int target = latestIndex;
        if (target >= 0) {
            list.post(() -> list.setSelection(target));
        }
    }

    private static String format(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    @Override
    protected void onDestroy() {
        db.close();
        super.onDestroy();
    }
}
