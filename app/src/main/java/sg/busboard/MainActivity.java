package sg.busboard;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    private static final int PAPER=0xfff5f6ed, INK=0xff123d34, GREEN=0xff006956,
            MUTED=0xff556b61, LIME=0xffe0f789, LINE=0xffcfdbcc, WHITE=Color.WHITE;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){ public void run(){ refresh(); handler.postDelayed(this,30000); } };
    private List<Board.Stop> catalog;
    private boolean loading, resumed;
    private final ExecutorService searchWorker=Executors.newSingleThreadExecutor();
    private Future<?> pendingSearch;
    @Override public void onDestroy(){searchWorker.shutdownNow();super.onDestroy();}
    private LinearLayout content;
    private ScrollView scroll;
    private int dp(float n){return Math.round(getResources().getDisplayMetrics().density*n);}
    private GradientDrawable shape(int color,int radius,int border) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius));
        if(border!=0) d.setStroke(dp(1),border); return d;
    }
    private TextView text(String value,int size,int color,String face) {
        TextView v=new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setTypeface(Typeface.create(face,Typeface.NORMAL)); v.setIncludeFontPadding(false); return v;
    }
    private TextView label(String value,int color) {
        TextView v=text(value,11,color,"sans-serif-medium"); v.setLetterSpacing(.11f); return v;
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private void pad(View v,int x,int y){v.setPadding(dp(x),dp(y),dp(x),dp(y));}
    private void gap(LinearLayout parent,int h){parent.addView(new View(this),1,dp(h));}
    private void flex(LinearLayout parent,View child){parent.addView(child,new LinearLayout.LayoutParams(0,-2,1));}
    private ImageView image(int res){ImageView i=new ImageView(this);i.setImageResource(res);i.setScaleType(ImageView.ScaleType.FIT_CENTER);return i;}
    private void rule(LinearLayout v){View line=new View(this); line.setBackgroundColor(LINE); v.addView(line,-1,dp(1));}
    private TextView button(String name,boolean primary,Runnable click){
        TextView v=text(name,15,primary?WHITE:INK,"sans-serif-medium");v.setGravity(Gravity.CENTER);
        v.setBackground(shape(primary?INK:WHITE,12,primary?0:LINE));pad(v,12,12);v.setMinHeight(dp(48));
        v.setOnClickListener(w->click.run());v.setFocusable(true);return v;
    }
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(PAPER);
        if(Build.VERSION.SDK_INT>=35)scroll.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets edges=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(edges.left,edges.top,edges.right,edges.bottom);return insets;
        });
        content=column();pad(content,20,16);scroll.addView(content);setContentView(scroll);
        CompletableFuture.supplyAsync(()->{
            try{return Board.catalog(this);}catch(Exception e){return Collections.<Board.Stop>emptyList();}
        }).thenAccept(list->runOnUiThread(()->{catalog=list;if(!isDestroyed())render();}));
        render();
    }
    @Override public void onResume(){super.onResume();resumed=true;tick.run();}
    @Override public void onPause(){resumed=false;handler.removeCallbacks(tick);super.onPause();}
    private void refresh(){
        if(loading || Board.slots(this).isEmpty()){render();return;}
        loading=true;render();
        Board.refresh(this,Board.slots(this)).whenComplete((v,e)->runOnUiThread(()->{loading=false;if(resumed&&!isDestroyed())render();}));
    }
    private void render(){
        List<Board.Slot> slots=Board.slots(this);
        content.removeAllViews();
        LinearLayout top=row();content.addView(top);
        ImageView logo=image(R.drawable.brand);logo.setBackground(shape(INK,9,0));logo.setClipToOutline(true);
        top.addView(logo,dp(34),dp(34));TextView brand=text("Bus Board",20,INK,"sans-serif-medium");pad(brand,9,0);flex(top,brand);
        if(!slots.isEmpty()){
            TextView update=button(loading?"Updating…":"↻  Refresh",false,this::refresh);update.setTextSize(13);top.addView(update);
        }
        gap(content,24); content.addView(label("SINGAPORE  /  "+LocalDate.now(ZoneId.of("Asia/Singapore"))
                .format(DateTimeFormatter.ofPattern("EEE, d MMM",Locale.UK)).toUpperCase(Locale.UK),MUTED));
        gap(content,5);content.addView(text("My departures",36,INK,"sans-serif-medium"));gap(content,16);
        if(slots.isEmpty()) preview();
        content.addView(button(slots.isEmpty()?"+   Choose my first service":"+   Find a bus or stop",true,()->pickStop(-1)));
        if(!slots.isEmpty()) {
            gap(content,12);LinearLayout head=row();flex(head,label("SAVED SERVICES  ·  "+slots.size(),MUTED));
            TextView choose=button("Widget services",false,this::chooseWidget);choose.setTextSize(13);head.addView(choose);
            content.addView(head);gap(content,10);
            for(int i=0;i<slots.size();i++){savedCard(slots.get(i),i);gap(content,9);}
        }
        gap(content,18);rule(content);gap(content,15);
        LinearLayout pin=row();LinearLayout intro=column();intro.addView(text("Home screen widget",17,INK,"sans-serif-medium"));
        gap(intro,3);intro.addView(text(Board.widgetSlots(this).size()+" of 4 services selected",13,MUTED,"sans-serif"));flex(pin,intro);
        pin.addView(button("Add widget",false,this::pin));content.addView(pin);
        gap(content,22);LinearLayout legend=row();legend.addView(image(R.drawable.sd),dp(24),dp(17));legend.addView(text("  Single-decker     ",12,MUTED,"sans-serif"));
        legend.addView(image(R.drawable.dd),dp(24),dp(17));legend.addView(text("  Double-decker",12,MUTED,"sans-serif"));content.addView(legend);
        gap(content,9);
        LinearLayout crowdLegend=row();
        addOccupancyKey(crowdLegend,Board.Occupancy.SEATS,"Seats");
        addOccupancyKey(crowdLegend,Board.Occupancy.STANDING,"Standing");
        addOccupancyKey(crowdLegend,Board.Occupancy.LIMITED,"Limited");
        content.addView(crowdLegend);
        gap(content,12);content.addView(text("Arrivals via ArriveLah · LTA DataMall",11,MUTED,"sans-serif"));
    }
    private void preview(){
        LinearLayout ticket=column(); ticket.setBackground(shape(WHITE,17,LINE)); ticket.setClipToOutline(true);content.addView(ticket);
        ImageView art=image(R.drawable.streetscape);art.setScaleType(ImageView.ScaleType.CENTER_CROP);ticket.addView(art,-1,dp(108));
        LinearLayout body=column();pad(body,15,13);ticket.addView(body);
        LinearLayout head=row();flex(head,label("EXAMPLE WIDGET ROW",MUTED));head.addView(text("min",12,MUTED,"sans-serif"));body.addView(head);
        gap(body,9);body.addView(text("Choa Chu Kang Int · 44009",14,INK,"sans-serif-medium"));gap(body,6);
        LinearLayout line=row();line.addView(route("172"));
        long now=System.currentTimeMillis();
        arrivalCell(line,new Board.Arrival(now+180000,"SD","SEA",false),now);
        arrivalCell(line,new Board.Arrival(now+300000,"DD","SDA",false),now);
        arrivalCell(line,new Board.Arrival(now+420000,"SD","LSD",true),now);
        body.addView(line);gap(content,11);
    }
    private TextView route(String service){
        TextView v=text(service,23,INK,"sans-serif-condensed-medium");v.setBackground(shape(LIME,8,0));pad(v,9,3);v.setGravity(Gravity.CENTER);v.setMinWidth(dp(53));return v;
    }
    private void addOccupancyKey(LinearLayout legend,Board.Occupancy occupancy,String label){
        ImageView icon=image(occupancy.icon);icon.setColorFilter(occupancy.onPaper);
        legend.addView(icon,dp(18),dp(18));
        TextView name=text(label,12,MUTED,"sans-serif");name.setPadding(dp(3),0,dp(10),0);
        legend.addView(name);
    }
    private void arrivalCell(LinearLayout parent,Board.Arrival a,long now){
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
    private void savedCard(Board.Slot s,int index){
        long now=System.currentTimeMillis();
        Board.Predictions p=new Board.Predictions(this,s,now);
        LinearLayout card=column();pad(card,13,7);card.setBackground(shape(WHITE,14,LINE));content.addView(card);
        LinearLayout head=row();flex(head,text(s.name,16,INK,"sans-serif-medium"));
        TextView edit=text("•••",17,MUTED,"sans-serif");edit.setGravity(Gravity.CENTER);edit.setContentDescription("Manage service "+s.service+" at "+s.name);
        edit.setOnClickListener(v->manage(index,s));edit.setFocusable(true);head.addView(edit,dp(44),dp(44));card.addView(head);
        LinearLayout line=row();line.addView(route(s.service));for(int i=0;i<3;i++)arrivalCell(line,i<p.arrivals.size()?p.arrivals.get(i):null,now);card.addView(line);
        gap(card,8);String info=p.status(now);
        if(info.isEmpty())info="Updated "+Board.CLOCK.format(Instant.ofEpochMilli(p.fetched))+" · min";
        card.addView(text(s.id+(s.pinned?"  ·  Widget":"")+"  ·  "+info,12,MUTED,"sans-serif"));gap(card,3);
    }
    private void manage(int index,Board.Slot slot){
        List<String> ops=new ArrayList<>();ops.add("Change stop or service");if(index>0)ops.add("Move up");if(index+1<Board.slots(this).size())ops.add("Move down");ops.add("Remove slot");
        new AlertDialog.Builder(this).setTitle(slot.service+" · "+slot.name).setItems(ops.toArray(new String[0]),(d,k)->{
            String op=ops.get(k);if(op.startsWith("Change")){pickStop(index);return;}
            List<Board.Slot> slots=Board.slots(this);
            if(op.startsWith("Remove"))slots.remove(index);else Collections.swap(slots,index,index+(op.equals("Move up")?-1:1));
            Board.save(this,slots);render();
        }).show();
    }
    private void chooseWidget(){
        List<Board.Slot> slots=Board.slots(this);
        String[] names=new String[slots.size()];
        boolean[] checked=new boolean[slots.size()];
        for(int i=0;i<slots.size();i++){
            Board.Slot s=slots.get(i); names[i]=s.service+"  ·  "+s.name+" ("+s.id+")"; checked[i]=s.pinned;
        }
        new AlertDialog.Builder(this).setTitle("Widget services · up to 4")
                .setMultiChoiceItems(names,checked,(dialog,index,value)->{
                    checked[index]=value;
                    int count=0;for(boolean selected:checked) if(selected) count++;
                    if(count>Board.WIDGET_LIMIT){
                        checked[index]=false;
                        ((AlertDialog)dialog).getListView().setItemChecked(index,false);
                        Toast.makeText(this,"Clear a widget service to choose another",Toast.LENGTH_SHORT).show();
                    }
                }).setPositiveButton("Save",(dialog,w)->{
                    for(int i=0;i<slots.size();i++) slots.set(i,slots.get(i).withPin(checked[i]));
                    Board.save(this,slots);render();
                    Board.refresh(this,Board.widgetSlots(this)).whenComplete((v,e)->runOnUiThread(()->{if(resumed&&!isDestroyed())render();}));
                }).setNegativeButton("Cancel",null).show();
    }
    private static final class SearchRow {
        final Board.Stop stop;
        final StopSearch.Match match;
        SearchRow(Board.Stop stop,StopSearch.Match match){this.stop=stop;this.match=match;}
    }
    private void pickStop(int index){pickStop(index,"");}
    private void pickStop(int index,String previous){
        if(catalog==null || catalog.isEmpty()){Toast.makeText(this,catalog==null?"Loading stops…":"Stop directory unavailable",Toast.LENGTH_SHORT).show();return;}
        Dialog dialog=new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box=column();box.setBackgroundColor(PAPER);pad(box,16,14);
        LinearLayout heading=row();flex(heading,text("Find a bus or stop",23,INK,"sans-serif-medium"));
        heading.addView(button("Close",false,dialog::dismiss));box.addView(heading);gap(box,10);
        EditText query=new EditText(this);query.setSingleLine();query.setHint("Bus, block, stop code or road");query.setTextSize(16);
        query.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
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
            if(result.match.bus!=null)saveService(index,result.stop,result.match.bus);
            else pickService(index,result.stop,input);
        });
        dialog.setContentView(box);
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();dialog.getWindow().setLayout(-1,-1);query.requestFocus();search.run();
    }
    private void pickService(int index,Board.Stop stop,String query){
        String[] services=stop.services.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle(stop.name+" · "+stop.id)
                .setItems(services,(d,pos)->saveService(index,stop,services[pos]))
                .setNegativeButton("Back",(d,w)->pickStop(index,query)).show();
    }
    private void saveService(int index,Board.Stop stop,String service){
        List<Board.Slot> slots=Board.slots(this);
        for(int i=0;i<slots.size();i++)if(i!=index && slots.get(i).id.equals(stop.id)&&slots.get(i).service.equals(service)){
            Toast.makeText(this,"This stop and service are already saved",Toast.LENGTH_SHORT).show();return;
        }
        boolean editing=index>=0 && index<slots.size();
        boolean pinned=editing?slots.get(index).pinned:slots.size()<Board.WIDGET_LIMIT;
        Board.Slot slot=new Board.Slot(stop.id,stop.name,service,pinned);
        if(editing)slots.set(index,slot);else slots.add(slot);
        Board.save(this,slots);render();
        Toast.makeText(this,"Saved "+service+" at "+stop.name,Toast.LENGTH_SHORT).show();
        Board.refresh(this,Collections.singletonList(slot)).whenComplete((v,e)->runOnUiThread(()->{if(resumed&&!isDestroyed())render();}));
    }
    private void pin(){
        AppWidgetManager m=getSystemService(AppWidgetManager.class);
        if(m.isRequestPinAppWidgetSupported() && m.requestPinAppWidget(new ComponentName(this,BoardWidget.class),null,null))return;
        new AlertDialog.Builder(this).setTitle("Add Bus Board to your home screen").setMessage("Touch and hold an empty spot on your home screen, open Widgets, then drag Bus Board into place.").setPositiveButton("Done",null).show();
    }
}
