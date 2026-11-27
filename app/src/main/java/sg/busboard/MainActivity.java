package sg.busboard;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class MainActivity extends BoardActivity {
    private static final String RELEASE_API="https://api.github.com/repos/ChaiMingKai/BusBoard/releases/latest";
    private static final String RELEASE_PAGE="https://github.com/ChaiMingKai/BusBoard/releases/latest";
    private static final String RELEASE_DOWNLOADS="https://github.com/ChaiMingKai/BusBoard/releases/download/";
    private static final String VERSION_PATTERN="[0-9]+(\\.[0-9]+)*";
    private static final String LAST_UPDATE_CHECK="updates.last_check_ms";
    private static final long UPDATE_CHECK_INTERVAL_MS=60*60*1000L;
    private final Runnable tick=new Runnable(){ public void run(){ refresh(); handler.postDelayed(this,30000); } };
    private boolean loading, resumed;
    private UpdateCheck updateCheck;

    private static final class UpdateCheck {
        final CompletableFuture<UpdateResult> result;
        boolean manual;

        UpdateCheck(CompletableFuture<UpdateResult> result, boolean manual){
            this.result=result;
            this.manual=manual;
        }
    }

    private static final class UpdateResult {
        final boolean success;
        final String newerVersion;

        UpdateResult(boolean success,String newerVersion){
            this.success=success;
            this.newerVersion=newerVersion;
        }
    }

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        updateCheck=(UpdateCheck)getLastNonConfigurationInstance();
        if(updateCheck!=null) observeUpdateCheck();
        render();
    }
    @Override public Object onRetainNonConfigurationInstance(){return updateCheck;}
    @Override void onCatalogLoaded(){render();}

    private void checkForUpdate(boolean manual){
        if(updateCheck!=null){
            if(manual) updateCheck.manual=true;
            return;
        }
        SharedPreferences prefs=Board.prefs(this);
        long now=System.currentTimeMillis();
        long last=prefs.getLong(LAST_UPDATE_CHECK,0);
        if(!manual && last>0 && now>=last && now-last<UPDATE_CHECK_INTERVAL_MS) return;
        prefs.edit().putLong(LAST_UPDATE_CHECK,now).apply();

        Context context=getApplicationContext();
        updateCheck=new UpdateCheck(CompletableFuture.supplyAsync(()->fetchUpdate(context)),manual);
        observeUpdateCheck();
    }

    private void observeUpdateCheck(){
        updateCheck.result.thenRun(()->handler.post(this::showUpdateResult));
    }

    private void showUpdateResult(){
        if(!resumed || isFinishing() || isDestroyed() || updateCheck==null) return;
        UpdateResult result=updateCheck.result.getNow(null);
        if(result==null) return;
        boolean manual=updateCheck.manual;
        updateCheck=null;
        if(result.newerVersion!=null){
            new AlertDialog.Builder(this).setTitle("Update available")
                    .setMessage("Bus Board "+result.newerVersion+" is available.")
                    .setPositiveButton("Update",(dialog,which)->openUpdate(result.newerVersion))
                    .setNegativeButton("Later",null).show();
        } else if(manual){
            Toast.makeText(this,result.success?"You're up to date":"Couldn't check for updates. Try again later.",Toast.LENGTH_SHORT).show();
        }
    }

    private void openUpdate(String version){
        if(version!=null && version.matches(VERSION_PATTERN)){
            String tag="v"+version;
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(RELEASE_DOWNLOADS+tag+"/BusBoard-"+tag+".apk")));
                return;
            } catch(ActivityNotFoundException | SecurityException ignored) {
                // Try the release page if the APK link cannot be opened.
            }
        }
        openReleasePage();
    }

    private void openReleasePage(){
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(RELEASE_PAGE)));
        } catch(ActivityNotFoundException | SecurityException ignored) {
            Toast.makeText(this,"Couldn't open the release page.",Toast.LENGTH_SHORT).show();
        }
    }

    private static UpdateResult fetchUpdate(Context context){
        HttpURLConnection connection=null;
        try {
            String installed=context.getPackageManager().getPackageInfo(context.getPackageName(),0).versionName;
            connection=(HttpURLConnection)new URL(RELEASE_API).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept","application/vnd.github+json");
            connection.setRequestProperty("User-Agent","BusBoard/"+installed);
            if(connection.getResponseCode()!=HttpURLConnection.HTTP_OK) return new UpdateResult(false,null);
            String tag=new JSONObject(Board.read(connection.getInputStream())).getString("tag_name");
            String latest=tag.startsWith("v")?tag.substring(1):tag;
            return new UpdateResult(true,isNewerVersion(latest,installed)?latest:null);
        } catch(IOException | JSONException | PackageManager.NameNotFoundException | SecurityException e) {
            return new UpdateResult(false,null);
        } finally {
            if(connection!=null) connection.disconnect();
        }
    }
    private static boolean isNewerVersion(String latest,String installed){
        if(installed==null || !latest.matches(VERSION_PATTERN) || !installed.matches(VERSION_PATTERN)) return false;
        String[] available=latest.split("\\.");
        String[] current=installed.split("\\.");
        for(int i=0;i<Math.max(available.length,current.length);i++){
            BigInteger next=i<available.length?new BigInteger(available[i]):BigInteger.ZERO;
            BigInteger previous=i<current.length?new BigInteger(current[i]):BigInteger.ZERO;
            int order=next.compareTo(previous);
            if(order!=0) return order>0;
        }
        return false;
    }
    @Override public void onResume(){
        super.onResume();
        resumed=true;
        tick.run();
        checkForUpdate(false);
        showUpdateResult();
    }
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
        gap(content,9);
        content.addView(button("Browse stops",false,()->startActivity(new Intent(this,StopActivity.class))));
        if(!slots.isEmpty()) {
            gap(content,12);LinearLayout head=row();flex(head,label("SAVED SERVICES  ·  "+slots.size(),MUTED));
            TextView choose=button("Widget services",false,this::chooseWidget);choose.setTextSize(13);head.addView(choose);
            content.addView(head);gap(content,10);
            for(int i=0;i<slots.size();i++){savedCard(slots.get(i),i);gap(content,9);}
        }
        gap(content,18);rule(content);gap(content,15);
        List<Board.Slot> widgetSlots=Board.widgetSlots(this);
        LinearLayout pin=row();LinearLayout intro=column();intro.addView(text("Home screen widget",17,INK,"sans-serif-medium"));
        gap(intro,3);intro.addView(text(widgetSlots.size()+" of "+Board.WIDGET_LIMIT+" services selected",13,MUTED,"sans-serif"));flex(pin,intro);
        pin.addView(button("Add widget",false,this::pin));content.addView(pin);
        Set<String> shownStops=new HashSet<>();
        for(Board.Slot slot:widgetSlots){
            if(shownStops.add(slot.id)) addWidgetDirection(slot);
        }
        gap(content,9);
        content.addView(button("Live refresh: every "+liveIntervalLabel(RefreshJob.liveIntervalMs(this)),false,this::chooseLiveInterval));
        gap(content,22);LinearLayout legend=row();legend.addView(image(R.drawable.sd),dp(24),dp(17));legend.addView(text("  Single-decker     ",12,MUTED,"sans-serif"));
        legend.addView(image(R.drawable.dd),dp(24),dp(17));legend.addView(text("  Double-decker",12,MUTED,"sans-serif"));content.addView(legend);
        gap(content,9);
        LinearLayout crowdLegend=row();
        addOccupancyKey(crowdLegend,Board.Occupancy.SEATS,"Seats");
        addOccupancyKey(crowdLegend,Board.Occupancy.STANDING,"Standing");
        addOccupancyKey(crowdLegend,Board.Occupancy.LIMITED,"Limited");
        content.addView(crowdLegend);
        gap(content,12);
        content.addView(button("Check for updates",false,()->{
            Toast.makeText(this,"Checking for updates…",Toast.LENGTH_SHORT).show();
            checkForUpdate(true);
        }));
        gap(content,12);content.addView(text("Arrivals via ArriveLah · LTA DataMall",11,MUTED,"sans-serif"));
    }
    private void addWidgetDirection(Board.Slot slot){
        gap(content,9);
        LinearLayout line=row();
        flex(line,text(slot.name+" ("+slot.id+")",14,INK,"sans-serif"));
        String direction=Board.isWidgetBack(this,slot.id)?"Back":"Out";
        TextView toggle=button(direction,false,()->{
            Board.setWidgetBack(this,slot.id,!Board.isWidgetBack(this,slot.id));
            render();
        });
        toggle.setContentDescription(slot.name+" ("+slot.id+"), widget direction "+direction+". Tap to switch.");
        line.addView(toggle);
        content.addView(line);
    }

    private static String liveIntervalLabel(long intervalMs){
        long seconds=intervalMs/1000L;
        return seconds+(seconds==1?" second":" seconds");
    }
    private void chooseLiveInterval(){
        EditText input=new EditText(this);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(Long.toString(RefreshJob.liveIntervalMs(this)/1000L));
        input.setSelectAllOnFocus(true);
        LinearLayout field=column();
        pad(field,20,0);
        field.addView(input);
        new AlertDialog.Builder(this).setTitle("Live refresh interval")
                .setMessage("Enter a whole number of seconds (1–60).")
                .setView(field)
                .setPositiveButton("Save",(dialog,which)->{
                    long seconds;
                    try {
                        seconds=new BigInteger(input.getText().toString().trim())
                                .max(BigInteger.ONE).min(BigInteger.valueOf(60)).longValue();
                    } catch(NumberFormatException e) {
                        return;
                    }
                    RefreshJob.setLiveIntervalMs(this,seconds*1000L);
                    render();
                }).setNegativeButton("Cancel",null).show();
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
    private void addOccupancyKey(LinearLayout legend,Board.Occupancy occupancy,String label){
        ImageView icon=image(occupancy.icon);icon.setColorFilter(occupancy.onPaper);
        legend.addView(icon,dp(18),dp(18));
        TextView name=text(label,12,MUTED,"sans-serif");name.setPadding(dp(3),0,dp(10),0);
        legend.addView(name);
    }
    private void savedCard(Board.Slot s,int index){
        long now=System.currentTimeMillis();
        Board.Predictions p=new Board.Predictions(this,s,now);
        LinearLayout card=column();pad(card,13,7);card.setBackground(shape(WHITE,14,LINE));content.addView(card);
        LinearLayout head=row();flex(head,text(s.name,16,INK,"sans-serif-medium"));
        TextView edit=text("•••",17,MUTED,"sans-serif");edit.setGravity(Gravity.CENTER);edit.setContentDescription("Manage service "+s.service+" at "+s.name);
        edit.setOnClickListener(v->manage(index,s));edit.setFocusable(true);head.addView(edit,dp(44),dp(44));card.addView(head);
        card.addView(arrivalRow(s.service,p.arrivals,now));
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
        new AlertDialog.Builder(this).setTitle("Widget services · up to "+Board.WIDGET_LIMIT)
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
    private void pickStop(int index){pickStop(index,"");}
    private void pickStop(int index,String previous){
        showStopSearch("Find a bus or stop",previous,(stop,match,query)->{
            if(match.bus!=null)saveService(index,stop,match.bus);
            else pickService(index,stop,query);
        });
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
