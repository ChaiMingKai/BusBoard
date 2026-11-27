package sg.busboard;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.view.View;
import android.os.*;
import android.widget.RemoteViews;
import java.util.*;

public final class BoardWidget extends AppWidgetProvider {
    private static final String REFRESH="sg.busboard.REFRESH";
    private static final String TOGGLE_LIVE="sg.busboard.TOGGLE_LIVE";
    private static final String LIVE_ENABLED="widget.live";
    private static final int STANDARD_ROWS=4;

    static boolean isLive(Context c) {
        return Board.prefs(c).getBoolean(LIVE_ENABLED,false);
    }
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids) {
        draw(c);
        if(isLive(c)) RefreshJob.startLive(c);
        else RefreshJob.start(c);
    }
    @Override public void onDisabled(Context c) {
        RefreshJob.stopLive(c);
    }
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options) {
        draw(c);
    }
    @Override public void onReceive(Context c,Intent intent) {
        if(TOGGLE_LIVE.equals(intent.getAction())) {
            boolean live=!isLive(c);
            Board.prefs(c).edit().putBoolean(LIVE_ENABLED,live).apply();
            draw(c);
            if(live) RefreshJob.startLive(c);
            else RefreshJob.stopLive(c);
        } else if(REFRESH.equals(intent.getAction())) {
            PendingResult pending=goAsync();
            Handler handler=new Handler(Looper.getMainLooper());
            boolean[] finished={false};
            Runnable expire=()->{
                if(!finished[0]) {
                    finished[0]=true;
                    RefreshJob.start(c);
                    pending.finish();
                }
            };
            handler.postDelayed(expire,7500);
            draw(c,true);
            Board.refresh(c,Board.widgetSlots(c)).whenComplete((result,error)->handler.post(()->{
                handler.removeCallbacks(expire);
                if(!finished[0]) { finished[0]=true; pending.finish(); }
            }));
        } else super.onReceive(c,intent);
    }
    static void draw(Context c) { draw(c,false); }
    private static void draw(Context c,boolean updating) {
        AppWidgetManager manager=AppWidgetManager.getInstance(c);
        int[] ids=manager.getAppWidgetIds(new ComponentName(c,BoardWidget.class));
        if(ids.length==0) return;
        List<Board.Slot> slots=Board.widgetSlots(c);
        boolean live=isLive(c);
        float density=c.getResources().getDisplayMetrics().density;
        RemoteViews standardBoard=null, compactBoard=null;
        for(int id:ids){
            Bundle options=manager.getAppWidgetOptions(id);
            int height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,0);
            // The standard layout needs 62 dp for its header and padding and 52 dp per row.
            boolean compact=slots.size()>STANDARD_ROWS && (height==0 || height<62+52*slots.size());
            RemoteViews sized;
            if(compact){
                if(compactBoard==null) compactBoard=build(c,slots,live,updating,true);
                sized=compactBoard;
            } else {
                if(standardBoard==null) standardBoard=build(c,slots,live,updating,false);
                sized=standardBoard;
            }
            if(compact || (slots.size()==STANDARD_ROWS && height>0 && height<275)){
                int side=Math.round(13*density);
                sized=sized.clone();
                sized.setViewPadding(R.id.board,side,0,side,0);
            }
            manager.updateAppWidget(id,sized);
        }
    }
    private static RemoteViews build(Context c,List<Board.Slot> slots,boolean live,boolean updating,boolean compact) {
        long now=System.currentTimeMillis();
        RemoteViews board=new RemoteViews(c.getPackageName(),R.layout.widget);
        PendingIntent open=PendingIntent.getActivity(c,1,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        board.setOnClickPendingIntent(R.id.board,open);
        Intent update=new Intent(c,BoardWidget.class).setAction(REFRESH);
        board.setOnClickPendingIntent(R.id.refresh,PendingIntent.getBroadcast(c,2,update,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        Intent toggle=new Intent(c,BoardWidget.class).setAction(TOGGLE_LIVE);
        board.setOnClickPendingIntent(R.id.live,PendingIntent.getBroadcast(c,3,toggle,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        board.setInt(R.id.live,"setBackgroundColor",live?0xffe0f789:0x00000000);
        board.setTextColor(R.id.live,live?0xff123d34:0xffc5ddcf);
        board.setContentDescription(R.id.live,live?"Live refresh on":"Live refresh off");
        board.removeAllViews(R.id.rows);
        board.setViewVisibility(R.id.empty,slots.isEmpty()?View.VISIBLE:View.GONE);
        board.setTextViewText(R.id.asof,"Updating…");
        board.setViewVisibility(R.id.age,slots.isEmpty()||updating?View.GONE:View.VISIBLE);
        board.setViewVisibility(R.id.asof,updating?View.VISIBLE:View.GONE);
        board.setChronometer(R.id.age,SystemClock.elapsedRealtime(),"Min as of %s ago",!slots.isEmpty()&&!updating);
        for(int index=0;index<slots.size();index++) {
            Board.Slot s=slots.get(index);
            Board.Predictions predictions=new Board.Predictions(c,s,now);
            RemoteViews row=new RemoteViews(c.getPackageName(),compact?R.layout.row_compact:R.layout.row);
            row.setTextViewText(R.id.service,s.service);
            String status=predictions.status(now);
            row.setTextViewText(R.id.stop,s.name+" · "+s.id+(status.isEmpty()?"":" · "+status));
            row.setViewVisibility(R.id.separator,index==0?View.INVISIBLE:View.VISIBLE);
            for(int i=0;i<3;i++) {
                Board.Arrival a=i<predictions.arrivals.size()?predictions.arrivals.get(i):null;
                RemoteViews cell=new RemoteViews(c.getPackageName(),compact?R.layout.arrival_cell_compact:R.layout.arrival_cell);
                cell.setTextViewText(R.id.eta,a==null?"—":a.eta(now));
                cell.setContentDescription(R.id.arrival_cell,a==null?"No arrival prediction":a.description(now));
                if(a==null) {
                    cell.setViewVisibility(R.id.deck,View.INVISIBLE);
                    cell.setViewVisibility(R.id.crowd,View.INVISIBLE);
                } else {
                    cell.setImageViewResource(R.id.deck,a.icon(true));
                    cell.setImageViewResource(R.id.crowd,a.occupancy.icon);
                    cell.setInt(R.id.crowd,"setColorFilter",a.occupancy.onGreen);
                }
                row.addView(R.id.arrivals,cell);
            }
            board.addView(R.id.rows,row);
        }
        return board;
    }
}
