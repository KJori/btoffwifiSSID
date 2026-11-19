package local.btoffonwifi;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/**
 * Safety net: every ~15 minutes re-apply the rule and revive the watcher, in case the
 * phone's battery manager killed it.
 */
public class WatchJob extends JobService {
    private static final int ID = 1;

    static void schedule(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null || js.getPendingJob(ID) != null) return;
            js.schedule(new JobInfo.Builder(ID, new ComponentName(c, WatchJob.class))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build());
        } catch (RuntimeException e) {
            // the foreground service still does the work
        }
    }

    static void cancel(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(ID);
        } catch (RuntimeException e) {
            // nothing scheduled
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (!new Prefs(this).enabled()) {
            cancel(this);
            return false;
        }
        Controller.rescheduleExpiry(this);
        Controller.evaluate(this, null);
        if (!GuardService.running) Controller.ensureRunning(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
