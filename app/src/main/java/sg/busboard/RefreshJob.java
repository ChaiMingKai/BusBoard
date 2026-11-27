package sg.busboard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public final class RefreshJob extends JobService {
    private static final int REFRESH_JOB_ID=172;
    private static final int LIVE_JOB_ID=173;
    private static final String LIVE_INTERVAL_KEY="live.interval_ms";
    private static final long DEFAULT_LIVE_INTERVAL_MS=30000L;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private JobParameters runningLive;

    static long liveIntervalMs(Context c) {
        return Board.prefs(c).getLong(LIVE_INTERVAL_KEY,DEFAULT_LIVE_INTERVAL_MS);
    }
    static void setLiveIntervalMs(Context c,long intervalMs) {
        boolean changed=intervalMs!=liveIntervalMs(c);
        Board.prefs(c).edit().putLong(LIVE_INTERVAL_KEY,intervalMs).apply();
        if(changed && BoardWidget.isLive(c)) {
            stopLive(c);
            startLive(c);
        }
    }
    static void start(Context c) {
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        JobInfo job=new JobInfo.Builder(REFRESH_JOB_ID,new ComponentName(c,RefreshJob.class))
                .setOverrideDeadline(0).build();
        scheduler.schedule(job);
    }
    static void startLive(Context c) {
        scheduleLive(c,0);
    }
    static void stopLive(Context c) {
        c.getSystemService(JobScheduler.class).cancel(LIVE_JOB_ID);
    }
    private static void scheduleLive(Context c,long delay) {
        if(!BoardWidget.isLive(c)) return;
        ComponentName provider=new ComponentName(c,BoardWidget.class);
        if(AppWidgetManager.getInstance(c).getAppWidgetIds(provider).length==0) return;
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(LIVE_JOB_ID)!=null) return;
        JobInfo job=new JobInfo.Builder(LIVE_JOB_ID,new ComponentName(c,RefreshJob.class))
                .setMinimumLatency(delay).setOverrideDeadline(delay).setPersisted(true).build();
        scheduler.schedule(job);
    }
    @Override public boolean onStartJob(JobParameters params) {
        boolean live=params.getJobId()==LIVE_JOB_ID;
        if(live){
            if(!BoardWidget.isLive(this)) return false;
            runningLive=params;
        }
        Board.refresh(this,Board.widgetSlots(this)).whenComplete((v,e)->handler.post(()->{
            if(live){
                if(runningLive!=params) return;
                runningLive=null;
            }
            jobFinished(params,false);
            // Let JobService report completion before reusing the job ID.
            if(live) handler.post(()->scheduleLive(this,liveIntervalMs(this)));
        }));
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        if(params.getJobId()!=LIVE_JOB_ID) return false;
        runningLive=null;
        return BoardWidget.isLive(this);
    }
}
