package sg.busboard;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import org.json.JSONException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

abstract class BoardActivity extends Activity {
    static final int PAPER=0xfff5f6ed, INK=0xff123d34, MUTED=0xff556b61,
            LIME=0xffe0f789, LINE=0xffcfdbcc, WHITE=Color.WHITE;
    final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService searchWorker=Executors.newSingleThreadExecutor();
    private Future<?> pendingSearch;
    List<Board.Stop> catalog;
    LinearLayout content;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(PAPER);
        if(Build.VERSION.SDK_INT>=35)scroll.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets edges=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(edges.left,edges.top,edges.right,edges.bottom);return insets;
        });
        content=column();pad(content,20,16);scroll.addView(content);setContentView(scroll);
        CompletableFuture.supplyAsync(()->{
            try{return Board.catalog(getApplicationContext());}
            catch(IOException | JSONException e){return Collections.<Board.Stop>emptyList();}
        }).thenAccept(list->handler.post(()->{
            if(isDestroyed())return;
            catalog=list;
            onCatalogLoaded();
        }));
    }

    @Override public void onDestroy(){
        searchWorker.shutdownNow();
        super.onDestroy();
    }

    abstract void onCatalogLoaded();

    final int dp(float n){return Math.round(getResources().getDisplayMetrics().density*n);}
    final GradientDrawable shape(int color,int radius,int border) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if(border!=0) d.setStroke(dp(1),border); return d;
    }
    final TextView text(String value,int size,int color,String face) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setTypeface(Typeface.create(face,Typeface.NORMAL)); v.setIncludeFontPadding(false); return v;
    }
    final TextView label(String value,int color) {
        TextView v=text(value,11,color,"sans-serif-medium"); v.setLetterSpacing(.11f); return v;
    }
    final LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    final LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    final void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    final void gap(LinearLayout parent,int h){parent.addView(new View(this),1,dp(h));}
    final void flex(LinearLayout parent,View child){parent.addView(child,new LinearLayout.LayoutParams(0,-2,1));}
    final ImageView image(int res){ImageView i=new ImageView(this);i.setImageResource(res);i.setScaleType(ImageView.ScaleType.FIT_CENTER);return i;}
    final void rule(LinearLayout v){View line=new View(this); line.setBackgroundColor(LINE); v.addView(line,-1,dp(1));}
    final TextView button(String name,boolean primary,Runnable click){
        TextView v=text(name,15,primary?WHITE:INK,"sans-serif-medium");v.setGravity(Gravity.CENTER);
        v.setBackground(shape(primary?INK:WHITE,12,primary?0:LINE));pad(v,12,12);v.setMinHeight(dp(48));
        v.setOnClickListener(w->click.run());v.setFocusable(true);return v;
    }
    final TextView route(String service){
        TextView v=text(service,23,INK,"sans-serif-condensed-medium");v.setBackground(shape(LIME,8,0));pad(v,9,3);v.setGravity(Gravity.CENTER);v.setMinWidth(dp(53));return v;
    }
    final void arrivalCell(LinearLayout parent,Board.Arrival a,long now){
        parent.setBaselineAligned(false);
        View cell=getLayoutInflater().inflate(R.layout.arrival_cell,parent,false);
        TextView eta=cell.findViewById(R.id.eta);
        eta.setText(a==null?"—":a.eta(now));eta.setTextColor(a==null?MUTED:INK);
        ImageView deck=cell.findViewById(R.id.deck);
        ImageView crowd=cell.findViewById(R.id.crowd);
        if(a!=null){
            deck.setImageResource(a.icon(false));
            crowd.setImageResource(a.occupancy.icon);crowd.setColorFilter(a.occupancy.onPaper);
        }
        cell.setContentDescription(a==null?"No arrival prediction":a.description(now));
        parent.addView(cell);
    }
    final LinearLayout arrivalRow(String service, List<Board.Arrival> arrivals, long now) {
        LinearLayout line=row();
        line.addView(route(service));
        for(int i=0;i<3;i++) arrivalCell(line,i<arrivals.size()?arrivals.get(i):null,now);
        return line;
    }

    interface StopSelection {
        void select(Board.Stop stop, StopSearch.Match match, String query);
    }

    private static final class SearchRow {
        final Board.Stop stop;
        final StopSearch.Match match;
        SearchRow(Board.Stop stop,StopSearch.Match match){this.stop=stop;this.match=match;}
    }
    final void showStopSearch(String title,String previous,StopSelection selection){
        if(catalog==null || catalog.isEmpty()){Toast.makeText(this,catalog==null?"Loading stops…":"Stop directory unavailable",Toast.LENGTH_SHORT).show();return;}
        Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box=column();box.setBackgroundColor(PAPER);pad(box,16,14);
        LinearLayout heading=row();flex(heading,text(title,23,INK,"sans-serif-medium"));
        heading.addView(button("Close",false,dialog::dismiss));box.addView(heading);gap(box,10);
        EditText query=new EditText(this);query.setSingleLine();query.setHint("Bus, block, stop code or road");query.setTextSize(16);
        query.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        query.setText(previous);query.setSelection(previous.length());box.addView(query);
        TextView status=text("Searching…",13,MUTED,"sans-serif");pad(status,4,8);box.addView(status);
        ListView list=new ListView(this);list.setDividerHeight(dp(1));box.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        List<SearchRow> results=new ArrayList<>();
        ArrayAdapter<SearchRow> adapter=new ArrayAdapter<SearchRow>(this,android.R.layout.simple_list_item_1,results){
            @Override public View getView(int position,View convert,ViewGroup parent){
                SearchRow result=getItem(position);Board.Stop stop=result.stop;
                LinearLayout item=column();pad(item,4,9);
                String prefix=result.match.bus==null?"":"Bus "+result.match.bus+"  ·  ";
                item.addView(text(prefix+stop.name,16,INK,"sans-serif-medium"));gap(item,3);
                item.addView(text(stop.id+"  ·  "+stop.road,13,MUTED,"sans-serif"));return item;
            }
        };
        list.setAdapter(adapter);
        Runnable search=()->{
            String input=query.getText().toString();
            if(pendingSearch!=null)pendingSearch.cancel(true);
            list.setEnabled(false);status.setText("Searching…");
            pendingSearch=searchWorker.submit(()->{
                StopSearch.Query q=new StopSearch.Query(input);List<SearchRow> matches=new ArrayList<>();
                for(Board.Stop stop:catalog){
                    if(Thread.currentThread().isInterrupted())return;
                    StopSearch.Match match=q.match(stop.index);
                    if(match!=null)matches.add(new SearchRow(stop,match));
                }
                matches.sort(Comparator.comparingInt(r->r.match.score));
                runOnUiThread(()->{
                    if(!dialog.isShowing() || !input.contentEquals(query.getText()))return;
                    results.clear();results.addAll(matches);adapter.notifyDataSetChanged();list.setSelection(0);list.setEnabled(true);
                    status.setText(results.isEmpty()?"No matches. Try a bus number, road or block number.":results.size()+(results.size()==1?" stop":" stops"));
                });
            });
        };
        query.addTextChangedListener(new TextWatcher(){
            public void onTextChanged(CharSequence s,int start,int before,int count){search.run();}
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void afterTextChanged(Editable e){}
        });
        list.setOnItemClickListener((parent,view,pos,id)->{
            SearchRow result=results.get(pos);String input=query.getText().toString();dialog.dismiss();
            selection.select(result.stop,result.match,input);
        });
        dialog.setContentView(box);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();dialog.getWindow().setLayout(-1,-1);query.requestFocus();search.run();
    }
}
