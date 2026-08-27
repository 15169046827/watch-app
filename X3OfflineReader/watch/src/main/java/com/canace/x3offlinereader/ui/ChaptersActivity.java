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
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

public final class ChaptersActivity extends SwipeBackActivity {
    private LibraryDb db;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);setContentView(R.layout.activity_chapters);enableSwipeBack(R.id.root);
        String bookId=getIntent().getStringExtra("book_id");((TextView)findViewById(R.id.title)).setText(getIntent().getStringExtra("book_title"));
        db=new LibraryDb(this);List<LibraryModels.ChapterSummary> chapters=db.chapters(bookId);LibraryModels.ResumePoint latest=db.latestProgress(bookId);List<String> labels=new ArrayList<>();int latestIndex=-1;for(int i=0;i<chapters.size();i++){LibraryModels.ChapterSummary chapter=chapters.get(i);if(latest!=null&&chapter.id.equals(latest.chapterId)){labels.add(getString(R.string.last_played,chapter.title));latestIndex=i;}else labels.add(chapter.title);}
        ListView list=findViewById(R.id.chapter_list);list.setAdapter(new ArrayAdapter<>(this,R.layout.item_chapter_entry,android.R.id.text1,labels));
        list.setOnItemClickListener((parent,view,position,id)->{
            LibraryModels.ChapterSummary chapter=chapters.get(position);
            startActivity(new Intent(this,PlayerActivity.class).putExtra("book_id",bookId).putExtra("chapter_id",chapter.id));
        });
        Button resume=findViewById(R.id.resume_button);if(latest==null){resume.setEnabled(false);}else{LibraryModels.ChapterSummary savedChapter=null;for(LibraryModels.ChapterSummary candidate:chapters)if(candidate.id.equals(latest.chapterId))savedChapter=candidate;if(savedChapter!=null){resume.setText(getString(R.string.resume_listening,savedChapter.title,format(latest.chapterPositionMs)));resume.setOnClickListener(v->startActivity(new Intent(this,PlayerActivity.class).putExtra("book_id",bookId).putExtra("resume_latest",true)));}else resume.setEnabled(false);}int target=latestIndex;if(target>=0)list.post(()->list.setSelection(target));
    }
    private static String format(long ms){long total=Math.max(0,ms)/1000;return String.format(Locale.ROOT,"%02d:%02d",total/60,total%60);}
    @Override protected void onDestroy(){db.close();super.onDestroy();}
}
