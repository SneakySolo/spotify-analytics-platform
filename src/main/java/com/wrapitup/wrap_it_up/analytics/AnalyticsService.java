package com.wrapitup.wrap_it_up.analytics;

import com.wrapitup.wrap_it_up.spotify.SpotifySnapshot;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {
    public WrapResult analyze(SpotifySnapshot s) {
        List<SpotifySnapshot.Track> tracks = safe(s.longTermTracks());
        List<SpotifySnapshot.Artist> artists = safe(s.longTermArtists());
        Map<String, Integer> artistCounts = tracks.stream().collect(Collectors.toMap(t -> t.artist().id(), t -> 1, Integer::sum));
        double loyalty = normalizeHhi(artistCounts, 50);
        Map<String, Integer> albums = new HashMap<>();
        for (int i = 0; i < tracks.size(); i++) albums.merge(tracks.get(i).albumId(), Math.max(1, 50 - i), Integer::sum);
        String topAlbumId = albums.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
        long topAlbumTracks = tracks.stream().filter(t -> t.albumId().equals(topAlbumId)).count();
        double concentration = topAlbumTracks / 50.0;
        Map<String, Double> genres = new HashMap<>();
        for (int i = 0; i < artists.size(); i++) for (String genre : safe(artists.get(i).genres())) genres.merge(normalizeGenre(genre), (double) (20 - i), Double::sum);
        double diversity = normalizeEntropy(genres);
        Set<String> recent = safe(s.recentlyPlayed()).stream().map(t -> t.artist().id()).collect(Collectors.toSet());
        Set<String> longTerm = artists.stream().map(SpotifySnapshot.Artist::id).collect(Collectors.toSet());
        double discovery = recent.isEmpty() ? 0 : recent.stream().filter(a -> !longTerm.contains(a)).count() / (double) recent.size();
        String risingId = rising(s.shortTermArtists(), artists);
        SpotifySnapshot.Artist top = artists.isEmpty() ? null : artists.get(0);
        SpotifySnapshot.Track topTrack = tracks.isEmpty() ? null : tracks.get(0);
        SpotifySnapshot.Album topAlbum = tracks.stream().filter(t -> t.albumId().equals(topAlbumId)).findFirst().map(SpotifySnapshot.Track::album).orElse(null);
        Personality personality = personality(loyalty, concentration, diversity, discovery);
        SpotifySnapshot.Artist rising = safe(s.shortTermArtists()).stream().filter(a -> a.id().equals(risingId)).findFirst().orElse(null);
        int recentMinutes = (int) (safe(s.recentlyPlayed()).stream().mapToLong(SpotifySnapshot.Track::durationMs).sum() / 60000.0);
        return new WrapResult(recentMinutes, top, tracks.stream().limit(5).toList(), topAlbum, concentration, genres.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("Unknown"), rising, loyalty, diversity, recent.isEmpty() ? 0 : 1 - recent.stream().filter(longTerm::contains).count() / (double) recent.size(), discovery, personality);
    }
    private String rising(List<SpotifySnapshot.Artist> shortTerm, List<SpotifySnapshot.Artist> longTerm) { Map<String,Integer> l = rank(longTerm), sh = rank(shortTerm); return safe(shortTerm).stream().limit(10).filter(a -> sh.getOrDefault(a.id(),0)-l.getOrDefault(a.id(),0)>0).max(Comparator.comparingInt(a -> sh.getOrDefault(a.id(),0)-l.getOrDefault(a.id(),0))).map(SpotifySnapshot.Artist::id).orElse(null); }
    private Map<String,Integer> rank(List<SpotifySnapshot.Artist> a) { Map<String,Integer> m=new HashMap<>(); for(int i=0;i<a.size();i++)m.put(a.get(i).id(),20-i); return m; }
    private double normalizeHhi(Map<String,Integer> counts, int total) { return Math.min(100, counts.values().stream().mapToDouble(c -> Math.pow(c/(double)total,2)).sum()*100); }
    private double normalizeEntropy(Map<String,Double> scores) { if(scores.size()<2)return 0; double total=scores.values().stream().mapToDouble(Double::doubleValue).sum(); double h=scores.values().stream().mapToDouble(v->{double p=v/total;return -p*Math.log(p);}).sum(); return Math.min(100,h/(Math.log(scores.size()))*100); }
    public static String normalizeGenre(String g) { String x=g.toLowerCase(Locale.ROOT); if(x.contains("rap")||x.contains("hip hop")||x.contains("trap"))return "Hip-Hop"; if(x.contains("pop"))return "Pop"; if(x.contains("rock"))return "Rock"; if(x.contains("electro")||x.contains("dance"))return "Electronic"; return "Other"; }
    private Personality personality(double loyalty,double album,double diversity,double discovery){ if(loyalty>=80&&album>=.5)return new Personality("OBSESSIVE","The Obsessive","You know exactly what you like and return to it often."); if(album>=.4)return new Personality("ALBUM_PERSON","The Album Person","You let a full album become part of your routine."); if(loyalty>=70)return new Personality("LOYALIST","The Loyalist","A few familiar artists keep earning their place."); if(discovery>=.5)return new Personality("DISCOVERER","The Discoverer","Your recent listening keeps finding new artists."); if(diversity>=65&&discovery>=.45)return new Personality("CHAOS_LISTENER","The Chaos Listener","Your listening takes wonderfully unexpected turns."); if(diversity>=70&&loyalty<60)return new Personality("EXPLORER","The Explorer","You roam widely across sounds and styles."); return new Personality("DEFAULT","The Soundtrack Maker","Your listening has its own unmistakable rhythm."); }
    private <T> List<T> safe(List<T> v){return v==null?List.of():v;}
}
