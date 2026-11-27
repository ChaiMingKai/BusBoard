package sg.busboard;

import android.app.*;
import android.appwidget.*;
import android.content.*;
import android.graphics.Paint;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.SizeF;
import android.util.TypedValue;
import android.view.View;
import android.os.*;
import android.widget.RemoteViews;
import java.util.*;

public final class BoardWidget extends AppWidgetProvider {
    private static final String REFRESH="sg.busboard.REFRESH";
    private static final String TOGGLE_LIVE="sg.busboard.TOGGLE_LIVE";
    private static final String NEXT_PAGE="sg.busboard.NEXT_WIDGET_PAGE";
    private static final String PAGE_PREFIX="widget.page.";
    private static final String DIRECTION_LAYOUT="widget.direction_layout";
    private static final String LIVE_ENABLED="widget.live";
    private static final String LIVE_STARTED_AT="live.started_at_ms";
    private static final int HEADER_DP=36;
    private static final int PADDING_DP=13;
    private static final int DEFAULT_HEIGHT_DP=110;
    private static final int MAX_SIZE_LAYOUTS=16;
    private static final int OUT_COLOR=0xff17613a;
    private static final int RETURN_COLOR=0xffa3222a;

    static boolean isLive(Context c) {
        return Board.prefs(c).getBoolean(LIVE_ENABLED,false);
    }
    private static void setLive(Context c,boolean live) {
        SharedPreferences.Editor edit=Board.prefs(c).edit().putBoolean(LIVE_ENABLED,live);
        if(live) edit.putLong(LIVE_STARTED_AT,System.currentTimeMillis());
        else edit.remove(LIVE_STARTED_AT);
        edit.apply();
    }
    static long liveElapsedMs(Context c) {
        SharedPreferences prefs=Board.prefs(c);
        long now=System.currentTimeMillis();
        long started=prefs.getLong(LIVE_STARTED_AT,0);
        if(started==0){
            // Live may already be enabled when upgrading from a version without a timestamp.
            prefs.edit().putLong(LIVE_STARTED_AT,now).apply();
            return 0;
        }
        return Math.max(0,now-started);
    }
    static void turnLiveOff(Context c) {
        setLive(c,false);
        RefreshJob.stopLive(c);
    }
    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids) {
        draw(c);
        if(isLive(c)) RefreshJob.startLive(c);
        else RefreshJob.start(c);
    }
    @Override public void onDisabled(Context c) {
        RefreshJob.stopLive(c);
    }
    @Override public void onDeleted(Context c,int[] ids) {
        SharedPreferences.Editor edit=Board.prefs(c).edit();
        for(int id:ids) edit.remove(PAGE_PREFIX+id);
        edit.apply();
    }
    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options) {
        draw(c);
    }
    @Override public void onReceive(Context c,Intent intent) {
        if(TOGGLE_LIVE.equals(intent.getAction())) {
            boolean live=!isLive(c);
            if(live) setLive(c,true);
            else turnLiveOff(c);
            draw(c);
            if(live) RefreshJob.startLive(c);
        } else if(NEXT_PAGE.equals(intent.getAction())) {
            int id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID);
            if(id==AppWidgetManager.INVALID_APPWIDGET_ID) return;
            int nextPage=intent.getIntExtra(NEXT_PAGE,0);
            Board.prefs(c).edit().putInt(PAGE_PREFIX+id,Math.max(0,nextPage)).apply();
            draw(c);
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
    static void redrawDirections(Context c) {
        // Alternate layout IDs so the host inflates fresh rows instead of reapplying the old view.
        SharedPreferences prefs=Board.prefs(c);
        prefs.edit().putBoolean(DIRECTION_LAYOUT,!prefs.getBoolean(DIRECTION_LAYOUT,false)).apply();
        draw(c);
    }
    private static void draw(Context c,boolean updating) {
        RefreshJob.expireLive(c);
        AppWidgetManager manager=AppWidgetManager.getInstance(c);
        int[] ids=manager.getAppWidgetIds(new ComponentName(c,BoardWidget.class));
        if(ids.length==0) return;
        List<Board.Slot> slots=Board.widgetSlots(c);
        SharedPreferences prefs=Board.prefs(c);
        int layout=prefs.getBoolean(DIRECTION_LAYOUT,false)?R.layout.widget_direction_refresh:R.layout.widget;
        boolean live=isLive(c);
        DisplayMetrics metrics=c.getResources().getDisplayMetrics();
        int standardRow=Math.max(52,1+lineHeightDp(metrics,11)+Math.max(24,17+lineHeightDp(metrics,18)));
        int compactRow=Math.max(28,1+Math.max(lineHeightDp(metrics,11)+lineHeightDp(metrics,10),lineHeightDp(metrics,13)));
        int side=Math.round(PADDING_DP*metrics.density);
        for(int id:ids){
            Bundle options=manager.getAppWidgetOptions(id);
            int savedPage=prefs.getInt(PAGE_PREFIX+id,0);
            ArrayList<SizeF> sizes=Build.VERSION.SDK_INT>=31
                    ?options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES):null;
            if(sizes!=null && !sizes.isEmpty() && sizes.size()<=MAX_SIZE_LAYOUTS){
                Map<SizeF,RemoteViews> layouts=new HashMap<>();
                for(SizeF size:sizes){
                    layouts.put(size,buildForHeight(c,slots,live,updating,layout,id,savedPage,
                            (int)size.getHeight(),standardRow,compactRow,side));
                }
                manager.updateAppWidget(id,new RemoteViews(layouts));
            } else {
                int height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,DEFAULT_HEIGHT_DP);
                manager.updateAppWidget(id,buildForHeight(c,slots,live,updating,layout,id,savedPage,
                        height,standardRow,compactRow,side));
            }
        }
    }
    private static RemoteViews buildForHeight(Context c,List<Board.Slot> slots,boolean live,boolean updating,
            int layout,int id,int savedPage,int height,int standardRow,int compactRow,int side) {
        int rowArea=(height>0?height:DEFAULT_HEIGHT_DP)-HEADER_DP;
        boolean compact=!slots.isEmpty() && rowArea-2*PADDING_DP<standardRow*slots.size();
        int capacity=compact?Math.max(1,rowArea/compactRow):Board.WIDGET_LIMIT;
        int pages=Math.max(1,(slots.size()+capacity-1)/capacity);
        int page=Math.min(savedPage,pages-1);
        int first=page*capacity;
        List<Board.Slot> visible=slots.subList(first,Math.min(first+capacity,slots.size()));
        RemoteViews board=build(c,visible,live,updating,compact,layout);
        int vertical=compact?0:side;
        board.setViewPadding(R.id.board,side,vertical,side,vertical);
        if(pages>1) addPageButton(c,board,id,page,pages);
        return board;
    }
    private static int lineHeightDp(DisplayMetrics metrics,int sp) {
        Paint text=new Paint();
        text.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,sp,metrics));
        Paint.FontMetricsInt font=text.getFontMetricsInt();
        return (int)Math.ceil((font.descent-font.ascent)/metrics.density);
    }
    private static void addPageButton(Context c,RemoteViews board,int id,int page,int pages) {
        int nextPage=(page+1)%pages;
        Intent next=new Intent(c,BoardWidget.class).setAction(NEXT_PAGE)
                .setData(Uri.parse("busboard://widget/"+id+"/page/"+nextPage))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra(NEXT_PAGE,nextPage);
        PendingIntent pending=PendingIntent.getBroadcast(c,id,next,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        board.setViewVisibility(R.id.widget_page,View.VISIBLE);
        board.setTextViewText(R.id.widget_page,(page+1)+"/"+pages);
        board.setContentDescription(R.id.widget_page,"Widget page "+(page+1)+" of "+pages+". Show next services.");
        board.setOnClickPendingIntent(R.id.widget_page,pending);
    }
    private static RemoteViews build(Context c,List<Board.Slot> slots,boolean live,boolean updating,boolean compact,int layout) {
        long now=System.currentTimeMillis();
        RemoteViews board=new RemoteViews(c.getPackageName(),layout);
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
        board.setViewVisibility(R.id.widget_page,View.GONE);
        board.setViewVisibility(R.id.empty,slots.isEmpty()?View.VISIBLE:View.GONE);
        board.setTextViewText(R.id.asof,"Updating…");
        board.setViewVisibility(R.id.age,slots.isEmpty()||updating?View.GONE:View.VISIBLE);
        board.setViewVisibility(R.id.asof,updating?View.VISIBLE:View.GONE);
        board.setChronometer(R.id.age,SystemClock.elapsedRealtime(),"Min as of %s ago",!slots.isEmpty()&&!updating);
        for(int index=0;index<slots.size();index++) {
            Board.Slot s=slots.get(index);
            Board.Predictions predictions=new Board.Predictions(c,s,now);
            RemoteViews row=new RemoteViews(c.getPackageName(),compact?R.layout.row_compact:R.layout.row);
            int color=Board.isWidgetBack(c,s.id)?RETURN_COLOR:OUT_COLOR;
            SpannableString service=new SpannableString(s.service);
            service.setSpan(new ForegroundColorSpan(color),0,service.length(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            row.setTextViewText(R.id.service,service);
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
