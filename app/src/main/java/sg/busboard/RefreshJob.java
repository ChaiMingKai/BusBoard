package sg.busboard;

import android.app.job.*;
import android.content.*;

public final class RefreshJob extends JobService {
    static void start(Context c) {
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        JobInfo job=new JobInfo.Builder(172,new ComponentName(c,RefreshJob.class))
                .setOverrideDeadline(0).build();
        scheduler.schedule(job);
    }
    @Override public boolean onStartJob(JobParameters params) {
        Board.refresh(this,Board.widgetSlots(this)).whenComplete((v,e)->new android.os.Handler(getMainLooper()).post(()->jobFinished(params,false)));
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return false; }
}
