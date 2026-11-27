package sg.busboard;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;

final class Board {
    static final int WIDGET_LIMIT = 4;
    static final long MAX_AGE = 180000L;
    static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.UK).withZone(ZoneId.of("Asia/Singapore"));
    static final class Stop {
        final String id, name, road;
        final StopSearch.Index index;
        final List<String> services = new ArrayList<>();
        Stop(JSONArray row) throws JSONException {
            id = row.getString(0); name = row.getString(1); road = row.getString(2);
            JSONArray list = row.getJSONArray(3);
            for (int i = 0; i < list.length(); i++) services.add(list.getString(i));
            index = new StopSearch.Index(id,name,road,services);
        }
    }
    static final class Slot {
        final String id, name, service;
        final boolean pinned;
        Slot(String id, String name, String service, boolean pinned) {
            this.id=id; this.name=name; this.service=service; this.pinned=pinned;
        }
        Slot withPin(boolean pin) { return new Slot(id,name,service,pin); }
    }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("board", Context.MODE_PRIVATE); }
    static List<Slot> slots(Context c) {
        List<Slot> result = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("slots","[]"));
            for (int i=0; i<a.length(); i++) {
                JSONArray row = a.getJSONArray(i);
                result.add(new Slot(row.getString(0),row.getString(1),row.getString(2),row.optBoolean(3,i<WIDGET_LIMIT)));
            }
        } catch (JSONException ignored) { }
        return result;
    }
    static List<Slot> widgetSlots(Context c) {
        List<Slot> selected=new ArrayList<>();
        for(Slot slot:slots(c)) if(slot.pinned && selected.size()<WIDGET_LIMIT) selected.add(slot);
        return selected;
    }
    static void save(Context c,List<Slot> list) {
        JSONArray rows = new JSONArray();
        for (Slot s:list) rows.put(new JSONArray().put(s.id).put(s.name).put(s.service).put(s.pinned));
        prefs(c).edit().putString("slots",rows.toString()).apply();
        BoardWidget.draw(c);
    }
    static String read(InputStream input) throws IOException {
        try (InputStream in=input; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] buf=new byte[8192]; int n;
            while ((n=in.read(buf))!=-1) out.write(buf,0,n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
    static List<Stop> catalog(Context c) throws IOException, JSONException {
        List<Stop> stops=new ArrayList<>();
        JSONArray raw=new JSONArray(read(c.getAssets().open("stops.json")));
        for(int i=0;i<raw.length();i++) stops.add(new Stop(raw.getJSONArray(i)));
        return stops;
    }
    enum Occupancy {
        SEATS(R.drawable.seat,"Seats available",0xff006655,0xffb5e8cf),
        STANDING(R.drawable.standing,"Standing room",0xff8a5800,0xffffd184),
        LIMITED(R.drawable.crowded,"Limited standing room",0xff9a443d,0xffffaca0),
        UNKNOWN(R.drawable.load_unknown,"Crowding unknown",0xff596a62,0xffc0ccc5);

        final int icon, onPaper, onGreen;
        final String label;
        Occupancy(int icon,String label,int onPaper,int onGreen) {
            this.icon=icon; this.label=label; this.onPaper=onPaper; this.onGreen=onGreen;
        }
        static Occupancy from(String code) {
            switch(code) {
                case "SEA": return SEATS;
                case "SDA": return STANDING;
                case "LSD": return LIMITED;
                default: return UNKNOWN;
            }
        }
    }
    static final class Arrival {
        final long time;
        final String type;
        final Occupancy occupancy;
        final boolean scheduled;
        Arrival(long time,String type,String load,boolean scheduled) {
            this.time=time; this.type=type; this.occupancy=Occupancy.from(load); this.scheduled=scheduled;
        }
        String description(long now) {
            String value=eta(now);
            return ("Arr".equals(value)?"Arriving now":value+" minutes")+", "+typeName()+", "+occupancy.label+
                    (scheduled?", scheduled":"");
        }
        String eta(long now) {
            long minutes=(time-now)/60000;
            return minutes<=0 ? "Arr" : Long.toString(minutes);
        }
        String typeName() {
            switch(type) {
                case "SD": return "Single-decker";
                case "DD": return "Double-decker";
                case "BD": return "Bendy bus";
                default: return "Bus type unavailable";
            }
        }
        int icon(boolean light) {
            switch(type) {
                case "SD": return light?R.drawable.sd_light:R.drawable.sd;
                case "DD": return light?R.drawable.dd_light:R.drawable.dd;
                case "BD": return light?R.drawable.bd_light:R.drawable.bd;
                default: return light?R.drawable.na_light:R.drawable.na;
            }
        }
    }
    static final class Predictions {
        final List<Arrival> arrivals=new ArrayList<>();
        final long fetched;
        final boolean error;
        Predictions(Context c, Slot s, long now) {
            SharedPreferences p=prefs(c);
            fetched=p.getLong("time."+s.id,0);
            error=p.getBoolean("error."+s.id,false);
            if (fetched==0 || now-fetched>MAX_AGE) return;
            try {
                JSONArray services=new JSONArray(p.getString("data."+s.id,"[]"));
                for(int i=0;i<services.length();i++) {
                    JSONObject service=services.getJSONObject(i);
                    if(!s.service.equalsIgnoreCase(service.optString("no"))) continue;
                    for(String key:new String[]{"next","next2","next3"}) {
                        JSONObject bus=service.optJSONObject(key);
                        if(bus==null) continue;
                        try {
                            long time=OffsetDateTime.parse(bus.optString("time")).toInstant().toEpochMilli();
                            if(time>=now-45000L) arrivals.add(new Arrival(time,bus.optString("type"),bus.optString("load"),bus.optInt("monitored",-1)==0));
                        } catch(java.time.format.DateTimeParseException ignored) { }
                    }
                }
                arrivals.sort(Comparator.comparingLong(a -> a.time));
                while(arrivals.size()>3) arrivals.remove(3);
            } catch(JSONException ignored) { }
        }
        String status(long now) {
            if(error) return arrivals.isEmpty()?"Update failed":"Cached · update failed";
            if(fetched==0) return "Awaiting update";
            if(now-fetched>MAX_AGE) return "Refresh needed";
            return arrivals.isEmpty()?"No predictions":"";
        }
    }
    private static final ExecutorService pool=Executors.newFixedThreadPool(4);
    private static final Map<String,CompletableFuture<Void>> active=new HashMap<>();
    private static synchronized CompletableFuture<Void> request(Context c,String id) {
        if(active.containsKey(id)) return active.get(id);
        CompletableFuture<Void> future=CompletableFuture.runAsync(() -> {
            HttpURLConnection con=null;
            try {
                con=(HttpURLConnection)new URL("https://arrivelah2.busrouter.sg/?id="+id).openConnection();
                con.setConnectTimeout(8000); con.setReadTimeout(8000); con.setUseCaches(false);
                con.setRequestProperty("Accept","application/json");
                JSONArray services=new JSONObject(read(con.getInputStream())).getJSONArray("services");
                prefs(c).edit().putString("data."+id,services.toString()).putLong("time."+id,System.currentTimeMillis()).putBoolean("error."+id,false).apply();
            } catch(IOException | JSONException e) {
                android.util.Log.w("BusBoard", "Arrival update failed for "+id,e);
                prefs(c).edit().putBoolean("error."+id,true).apply();
            } finally { if(con!=null) con.disconnect(); }
        },pool);
        active.put(id,future);
        future.whenComplete((v,e)->{ synchronized(Board.class){ active.remove(id,future); }});
        return future;
    }
    static CompletableFuture<Void> refresh(Context context, List<Slot> selection) {
        Context c=context.getApplicationContext();
        Set<String> stops=new LinkedHashSet<>();
        for(Slot s:selection) stops.add(s.id);
        List<CompletableFuture<Void>> futures=new ArrayList<>();
        for(String id:stops) futures.add(request(c,id));
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                .thenRun(()->BoardWidget.draw(c));
    }
}
