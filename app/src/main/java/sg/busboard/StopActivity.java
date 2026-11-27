package sg.busboard;

import android.os.Bundle;
import android.widget.LinearLayout;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public final class StopActivity extends BoardActivity {
    private static final String SELECTED_STOP="selected_stop";
    private static final long REFRESH_INTERVAL_MS=30000L;
    private final Runnable tick=new Runnable(){
        public void run(){
            refresh();
            handler.postDelayed(this,REFRESH_INTERVAL_MS);
        }
    };
    private String stopId;
    private Board.Stop stop;
    private CompletableFuture<Void> pendingRefresh;
    private boolean resumed;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(state!=null) stopId=state.getString(SELECTED_STOP);
        render();
    }

    @Override public void onResume(){
        super.onResume();
        resumed=true;
        tick.run();
    }

    @Override public void onPause(){
        resumed=false;
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);
        state.putString(SELECTED_STOP,stopId);
    }

    @Override void onCatalogLoaded(){
        if(stopId==null) return;
        for(Board.Stop candidate:catalog){
            if(stopId.equals(candidate.id)){
                selectStop(candidate);
                return;
            }
        }
    }

    private void selectStop(Board.Stop selected){
        if(stop!=selected) pendingRefresh=null;
        stop=selected;
        stopId=selected.id;
        if(resumed && pendingRefresh==null) refresh();
        else render();
    }

    private void refresh(){
        if(stop==null || pendingRefresh!=null) return;
        CompletableFuture<Void> request=Board.refreshStop(this,stop.id);
        pendingRefresh=request;
        render();
        request.whenComplete((value,error)->handler.post(()->{
            if(pendingRefresh!=request) return;
            pendingRefresh=null;
            if(resumed && !isDestroyed()) render();
        }));
    }

    private void render(){
        content.removeAllViews();
        content.addView(text("Browse stops",28,INK,"sans-serif-medium"));
        gap(content,12);
        content.addView(button(stop==null?"Find a stop":"Change stop",false,
                ()->showStopSearch("Find a stop","",(selected,match,query)->selectStop(selected))));
        if(stop==null) return;

        gap(content,18);
        content.addView(text(stop.name,24,INK,"sans-serif-medium"));
        gap(content,4);
        content.addView(text(stop.id+"  ·  "+stop.road,13,MUTED,"sans-serif"));
        gap(content,14);
        content.addView(label(pendingRefresh==null?"NEXT ARRIVALS  ·  MIN":"UPDATING ARRIVALS…",MUTED));
        gap(content,10);

        Collection<String> services=Board.servicesAt(this,stop);
        if(services.isEmpty() && pendingRefresh==null){
            String status=Board.arrivalUpdateFailed(Board.prefs(this),stop.id)
                    ?"Couldn't load arrivals.":"No services listed for this stop.";
            content.addView(text(status,14,MUTED,"sans-serif"));
        }
        long now=System.currentTimeMillis();
        for(String service:services){
            Board.Slot slot=new Board.Slot(stop.id,stop.name,service,false);
            Board.Predictions predictions=new Board.Predictions(this,slot,now);
            LinearLayout card=column();
            pad(card,13,10);
            card.setBackground(shape(WHITE,14,LINE));
            card.addView(arrivalRow(service,predictions.arrivals,now));
            gap(card,8);
            String status=predictions.status(now);
            if(status.isEmpty()) status="Updated "+Board.CLOCK.format(Instant.ofEpochMilli(predictions.fetched))+" · min";
            card.addView(text(status,12,MUTED,"sans-serif"));
            content.addView(card);
            gap(content,9);
        }
    }
}
