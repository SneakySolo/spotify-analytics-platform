package com.spotifyanalytics.platform.analytics;

import com.spotifyanalytics.platform.spotify.SpotifySnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AnalyticsServiceTest {

    @Test
    void rankAndPersonalityAreDeterministic() {
        var artistA = new SpotifySnapshot.Artist(
                "a", "A", List.of("hip hop"), null);
        var artistB = new SpotifySnapshot.Artist(
                "b", "B", List.of("pop"), null);
        var track = new SpotifySnapshot.Track(
                "t", "T", 180000, artistA, "al", "Album", null);
        var snapshot = new SpotifySnapshot(
                List.of(artistA),
                List.of(artistA),
                List.of(artistA, artistB),
                List.of(track),
                List.of(track));

        WrapResult result = new AnalyticsService().analyze(snapshot);

        assertEquals("A", result.topArtist().name());
        assertEquals("Hip-Hop", result.topGenre());
        assertEquals(3, result.recentListeningMinutes());
        assertEquals("Album", result.topAlbum().name());
        assertNotNull(result.personality());
    }

    @Test
    void genreNormalizationUsesBroadCategories() {
        assertEquals("Hip-Hop", AnalyticsService.normalizeGenre("trap"));
        assertEquals("Pop", AnalyticsService.normalizeGenre("indie pop"));
    }

    @Test
    void tasteChangesComparesRecentAndLongTermFavorites() {
        var artist = new SpotifySnapshot.Artist("a", "A", List.of(), null);
        var usual = new SpotifySnapshot.Track("usual", "Usual", 180000, artist, "al", "Album", null);
        var recent = new SpotifySnapshot.Track("recent", "Recent", 180000, artist, "al", "Album", null);
        var snapshot = new SpotifySnapshot(null, List.of(usual), List.of(usual, recent), List.of(artist), List.of());

        var changes = new AnalyticsService().analyze(snapshot).tasteChanges;

        assertEquals(50d, changes.differencePercent().value());
        assertEquals(1d, changes.discoveredFavorites().value());
        assertEquals("favorites", changes.discoveredFavorites().unit());
    }
}
