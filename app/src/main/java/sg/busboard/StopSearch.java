package sg.busboard;

import java.util.*;
import java.util.regex.Pattern;

final class StopSearch {
    private static final Pattern SEPARATORS=Pattern.compile("[^a-z0-9]+");
    private static final Pattern JOINED_PREFIX=Pattern.compile("\\b(blocks?|blks?|bus|service)(?=[0-9])");
    private static final Pattern SERVICE_NUMBER=Pattern.compile("[0-9]{1,3}[a-z]{0,2}|[a-z]{1,3}[0-9]{1,3}");
    private static final Map<String,String> aliases=new HashMap<>();
    static {
        String[][] pairs={{"block","blk"},{"blocks","blk"},{"blks","blk"},{"between","bet"},{"opposite","opp"},
                {"before","bef"},{"after","aft"},{"road","rd"},{"street","st"},{"avenue","ave"},{"drive","dr"},
                {"upper","upp"},{"bukit","bt"},{"jalan","jln"},{"interchange","int"},
                {"centre","ctr"},{"central","ctrl"},{"lorong","lor"},{"junction","junc"},{"tamp","tampines"}};
        for(String[] pair:pairs) aliases.put(pair[0],pair[1]);
    }
    private static String[] words(String raw) {
        String lower=raw.toLowerCase(Locale.ROOT).replace("'","").replace("’","");
        String clean=SEPARATORS.matcher(JOINED_PREFIX.matcher(lower).replaceAll("$1 ")).replaceAll(" ").trim();
        List<String> result=new ArrayList<>();
        for(String token:clean.split(" +")) {
            String word=aliases.getOrDefault(token,token);
            if(!word.isEmpty() && !word.equals("and")) result.add(word);
        }
        return result.toArray(new String[0]);
    }
    static final class Index {
        final String id, compact;
        final String[] words;
        final Map<String,String> services=new HashMap<>();
        Index(String id,String name,String road,List<String> routes) {
            this.id=id;
            words=words(name+" "+road);
            compact=String.join("",words);
            for(String route:routes) services.put(route.toLowerCase(Locale.ROOT),route);
        }
    }
    static final class Query {
        final String[] terms;
        final boolean busOnly;
        final String serviceTerm;
        Query(String input) {
            List<String> parts=new ArrayList<>(Arrays.asList(words(input)));
            String candidate=null;
            int numbers=0;
            for(String word:parts) {
                if(SERVICE_NUMBER.matcher(word).matches()) {
                    if(candidate==null) candidate=word;
                    numbers++;
                }
            }
            boolean explicit=candidate!=null && (parts.contains("bus") || parts.contains("service"));
            if(explicit) { parts.remove("bus"); parts.remove("service"); }
            busOnly=explicit;
            serviceTerm=explicit || (numbers==1 && !parts.contains("blk")) ? candidate : null;
            terms=parts.toArray(new String[0]);
        }
        Match match(Index stop) {
            if(terms.length==0) return new Match(0,null);
            int score=0; String bus=null;
            for(String term:terms) {
                String route=term.equals(serviceTerm)?stop.services.get(term):null;
                if(route!=null && bus==null) {
                    bus=route;
                    score+=1;
                } else {
                    int cost=matchWord(term,stop);
                    if(cost<0) return null;
                    score+=cost;
                }
            }
            if(busOnly && bus==null) return null;
            return new Match(score,bus);
        }
    }
    static final class Match {
        final int score; final String bus;
        Match(int score,String bus){ this.score=score; this.bus=bus; }
    }
    private static int matchWord(String term, Index stop) {
        if(term.equals(stop.id)) return 0;
        int best=99;
        for(String word:stop.words){
            if(word.equals(term)) return 0;
            if(word.startsWith(term)) best=Math.min(best,3);
            else if(term.length()>=4 && Character.isLetter(term.charAt(0)) && near(term,word)) best=Math.min(best,8);
        }
        if(term.length()>=3 && stop.compact.contains(term)) best=Math.min(best,4);
        if(term.length()>=3 && stop.id.startsWith(term)) best=Math.min(best,6);
        return best==99?-1:best;
    }
    // One insertion, deletion, substitution or transposition in a place name.
    private static boolean near(String a,String b) {
        int n=a.length(),m=b.length();
        if(Math.abs(n-m)>1) return false;
        int i=0;
        while(i<n && i<m && a.charAt(i)==b.charAt(i)) i++;
        if(i==Math.min(n,m)) return true;
        if(n==m) {
            if(a.regionMatches(i+1,b,i+1,n-i-1)) return true;
            return i+1<n && a.charAt(i)==b.charAt(i+1) && a.charAt(i+1)==b.charAt(i)
                    && a.regionMatches(i+2,b,i+2,n-i-2);
        }
        return n>m ? a.regionMatches(i+1,b,i,n-i-1) : a.regionMatches(i,b,i+1,m-i-1);
    }
}
