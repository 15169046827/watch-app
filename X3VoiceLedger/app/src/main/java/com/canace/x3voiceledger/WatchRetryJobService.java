package com.canace.x3voiceledger;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public final class WatchRetryJobService extends JobService {
    private static final int JOB_ID = 3102;

    static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) return;
        JobInfo job = new JobInfo.Builder(JOB_ID,
                new ComponentName(context, WatchRetryJobService.class))
                .setPersisted(true)
                .setPeriodic(15 * 60_000L)
                .build();
        scheduler.schedule(job);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try (LedgerStore store = new LedgerStore(this)) {
            if (store.pendingCount() > 0) BleSyncService.start(this);
        }
        jobFinished(params, false);
        return false;
    }

    @Override public boolean onStopJob(JobParameters params) { return true; }
}
