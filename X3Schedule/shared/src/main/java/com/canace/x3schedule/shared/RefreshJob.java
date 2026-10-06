package com.canace.x3schedule.shared;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

public final class RefreshJob extends JobService {
    private JobParameters active;

    public static void register(Context c) {
        c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(31, new ComponentName(c, RefreshJob
                .class)).setPeriodic(15 * 60 * 1000L).setPersisted(true).build());
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        active = p;
        ReminderWork.request(this, true, success -> {
            if (active == p) {
                active = null;
                jobFinished(p, !success);
            }
        });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        if (active == p) {
            active = null;
        }
        return true;
    }
}
