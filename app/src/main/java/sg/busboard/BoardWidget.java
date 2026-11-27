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
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids) {
        draw(c); RefreshJob.start(c);
    }
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options) {
        draw(c);
    }
    @Override public void onReceive(Context c,Intent intent) {
        if(REFRESH.equals(intent.getAction())) {
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
        long now=System.currentTimeMillis();
        RemoteViews board=new RemoteViews(c.getPackageName(),R.layout.widget);
        PendingIntent open=PendingIntent.getActivity(c,1,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        board.setOnClickPendingIntent(R.id.board,open);
        Intent update=new Intent(c,BoardWidget.class).setAction(REFRESH);
        board.setOnClickPendingIntent(R.id.refresh,PendingIntent.getBroadcast(c,2,update,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT));
        board.removeAllViews(R.id.rows);
        List<Board.Slot> slots=Board.widgetSlots(c);
        board.setViewVisibility(R.id.empty,slots.isEmpty()?View.VISIBLE:View.GONE);
        board.setTextViewText(R.id.asof,"Updating…");
        board.setViewVisibility(R.id.age,slots.isEmpty()||updating?View.GONE:View.VISIBLE);
        board.setViewVisibility(R.id.asof,updating?View.VISIBLE:View.GONE);
        board.setChronometer(R.id.age,SystemClock.elapsedRealtime(),"Min as of %s ago",!slots.isEmpty()&&!updating);
        for(int index=0;index<slots.size();index++) {
            Board.Slot s=slots.get(index);
            Board.Predictions predictions=new Board.Predictions(c,s,now);
            RemoteViews row=new RemoteViews(c.getPackageName(),R.layout.row);
            row.setTextViewText(R.id.service,s.service);
            String status=predictions.status(now);
            row.setTextViewText(R.id.stop,s.name+" · "+s.id+(status.isEmpty()?"":" · "+status));
            row.setViewVisibility(R.id.separator,index==0?View.INVISIBLE:View.VISIBLE);
            for(int i=0;i<3;i++) {
                Board.Arrival a=i<predictions.arrivals.size()?predictions.arrivals.get(i):null;
                RemoteViews cell=new RemoteViews(c.getPackageName(),R.layout.arrival_cell);
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
        float density=c.getResources().getDisplayMetrics().density;
        for(int id:ids){
            Bundle options=manager.getAppWidgetOptions(id);
            int height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,0);
            RemoteViews sized=board.clone();
            if(slots.size()==Board.WIDGET_LIMIT && height>0 && height<275){
                int side=Math.round(13*density);
                sized.setViewPadding(R.id.board,side,0,side,0);
            }
            manager.updateAppWidget(id,sized);
        }
    }
}
