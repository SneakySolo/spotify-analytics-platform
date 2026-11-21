package com.wrapitup.wrap_it_up.analytics;
import com.wrapitup.wrap_it_up.spotify.SpotifySnapshot;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AnalyticsServiceTest {
    @Test void rankAndPersonalityAreDeterministic(){var a=new SpotifySnapshot.Artist("a","A",List.of("hip hop"),null);var b=new SpotifySnapshot.Artist("b","B",List.of("pop"),null);var t=new SpotifySnapshot.Track("t","T",180000,a,"al","Album",null);var s=new SpotifySnapshot(List.of(a),List.of(a),List.of(a,b),List.of(t),List.of(t));var r=new AnalyticsService().analyze(s);assertEquals("A",r.topArtist().name());assertEquals("Hip-Hop",r.topGenre());assertEquals(3,r.recentListeningMinutes());assertEquals("Album",r.topAlbum().name());assertNotNull(r.personality());}
    @Test void genreNormalizationUsesBroadCategories(){assertEquals("Hip-Hop",AnalyticsService.normalizeGenre("trap"));assertEquals("Pop",AnalyticsService.normalizeGenre("indie pop"));}
}
