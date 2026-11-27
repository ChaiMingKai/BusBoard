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
import android.os.PersistableBundle;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;

public final class RefreshJob extends JobService {
    private static final int REFRESH_JOB_ID=172;
    private static final int LIVE_JOB_ID=173;
    private static final String LIVE_INTERVAL_KEY="live.interval_ms";
    private static final String LIVE_AUTO_OFF_KEY="live.auto_off_ms";
    private static final String LIVE_REFRESH_AT="live.refresh_at_ms";
    private static final BigInteger DEFAULT_LIVE_INTERVAL_MS=BigInteger.valueOf(30000L);
    private static final BigInteger MAX_JOB_DELAY_MS=BigInteger.valueOf(TimeUnit.DAYS.toMillis(365));
    private final Handler handler=new Handler(Looper.getMainLooper());
    private JobParameters runningLive;

    static BigInteger liveIntervalMs(Context c) {
        Object saved=Board.prefs(c).getAll().get(LIVE_INTERVAL_KEY);
        if(saved instanceof String) return new BigInteger((String)saved);
        if(saved instanceof Long) return BigInteger.valueOf((Long)saved);
        return DEFAULT_LIVE_INTERVAL_MS;
    }
    static void setLiveIntervalMs(Context c,BigInteger intervalMs) {
        boolean changed=!intervalMs.equals(liveIntervalMs(c));
        Board.prefs(c).edit().putString(LIVE_INTERVAL_KEY,intervalMs.toString()).apply();
        if(changed && BoardWidget.isLive(c)) {
            stopLive(c);
            startLive(c);
        }
    }
    static BigInteger liveAutoOffMs(Context c) {
        return new BigInteger(Board.prefs(c).getString(LIVE_AUTO_OFF_KEY,"0"));
    }
    static void setLiveAutoOffMs(Context c,BigInteger limitMs) {
        if(limitMs.equals(liveAutoOffMs(c))) return;
        Board.prefs(c).edit().putString(LIVE_AUTO_OFF_KEY,limitMs.toString()).apply();
        BoardWidget.draw(c);
        if(BoardWidget.isLive(c)) {
            stopLive(c);
            startLive(c);
        }
    }
    private static BigInteger autoOffRemainingMs(Context c) {
        BigInteger limit=liveAutoOffMs(c);
        return limit.signum()==0?null:limit.subtract(BigInteger.valueOf(BoardWidget.liveElapsedMs(c)));
    }
    static boolean expireLive(Context c) {
        if(!BoardWidget.isLive(c)) return false;
        BigInteger remaining=autoOffRemainingMs(c);
        if(remaining==null || remaining.signum()>0) return false;
        BoardWidget.turnLiveOff(c);
        return true;
    }
    private static boolean canRunLive(Context c) {
        if(expireLive(c)) BoardWidget.draw(c);
        return BoardWidget.isLive(c);
    }
    private static BigInteger nowMs() {
        return BigInteger.valueOf(System.currentTimeMillis());
    }
    static void start(Context c) {
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        JobInfo job=new JobInfo.Builder(REFRESH_JOB_ID,new ComponentName(c,RefreshJob.class))
                .setOverrideDeadline(0).build();
        scheduler.schedule(job);
    }
    static void startLive(Context c) {
        scheduleLive(c,nowMs());
    }
    static void stopLive(Context c) {
        c.getSystemService(JobScheduler.class).cancel(LIVE_JOB_ID);
    }
    private static void scheduleLive(Context c,BigInteger refreshAt) {
        if(!canRunLive(c)) return;
        ComponentName provider=new ComponentName(c,BoardWidget.class);
        if(AppWidgetManager.getInstance(c).getAppWidgetIds(provider).length==0) return;
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(LIVE_JOB_ID)!=null) return;
        BigInteger delay=refreshAt.subtract(nowMs());
        BigInteger untilAutoOff=autoOffRemainingMs(c);
        if(untilAutoOff!=null) delay=delay.min(untilAutoOff);
        // Recheck long delays in stages so the stored duration never has to fit in a JobInfo long.
        long waitMs=delay.max(BigInteger.ZERO).min(MAX_JOB_DELAY_MS).longValue();
        PersistableBundle extras=new PersistableBundle();
        extras.putString(LIVE_REFRESH_AT,refreshAt.toString());
        JobInfo job=new JobInfo.Builder(LIVE_JOB_ID,new ComponentName(c,RefreshJob.class))
                .setMinimumLatency(waitMs).setOverrideDeadline(waitMs).setExtras(extras).setPersisted(true).build();
        scheduler.schedule(job);
    }
    @Override public boolean onStartJob(JobParameters params) {
        boolean live=params.getJobId()==LIVE_JOB_ID;
        if(live){
            if(!canRunLive(this)) return false;
            String savedRefreshAt=params.getExtras().getString(LIVE_REFRESH_AT);
            if(savedRefreshAt!=null){
                BigInteger refreshAt=new BigInteger(savedRefreshAt);
                if(refreshAt.compareTo(nowMs())>0){
                    handler.post(()->scheduleLive(this,refreshAt));
                    return false;
                }
            }
            runningLive=params;
        }
        Board.refresh(this,Board.widgetSlots(this)).whenComplete((v,e)->handler.post(()->{
            if(live){
                if(runningLive!=params) return;
                runningLive=null;
            }
            jobFinished(params,false);
            // Let JobService report completion before reusing the job ID.
            if(live) handler.post(()->scheduleLive(this,nowMs().add(liveIntervalMs(this))));
        }));
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        if(params.getJobId()!=LIVE_JOB_ID) return false;
        runningLive=null;
        return BoardWidget.isLive(this);
    }
}
