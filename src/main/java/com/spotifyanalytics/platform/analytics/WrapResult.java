package com.spotifyanalytics.platform.analytics;

import com.spotifyanalytics.platform.spotify.SpotifySnapshot;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** JSON response for one on-demand 13-slide Wrap. */
public class WrapResult {
    public final SpotifySnapshot.Profile profile;
    public final long savedTrackCount;
    public final LibraryGrowth libraryGrowth;
    public final List<SpotifySnapshot.Track> topTracks;
    public final List<SpotifySnapshot.Artist> topArtists;
    public final Metric estimatedListeningTime;
    public final Metric estimatedYearListeningTime;
    public final Metric totalTracksListened;
    public final Metric totalAlbumsListened;
    @JsonIgnore public final List<GenreCount> topGenres;
    public final List<SpotifySnapshot.Track> hiddenGems;
    public final Metric tasteDiversityScore;
    public final Metric tasteEvolutionScore;
    public final Metric replayIntensityScore;
    public final String outro;

    public WrapResult(SpotifySnapshot.Profile profile, long savedTrackCount, LibraryGrowth libraryGrowth, List<SpotifySnapshot.Track> topTracks,
                      List<SpotifySnapshot.Artist> topArtists, Metric estimatedListeningTime,
                      Metric estimatedYearListeningTime,
                      Metric totalTracksListened, Metric totalAlbumsListened, List<GenreCount> topGenres,
                      List<SpotifySnapshot.Track> hiddenGems, Metric tasteDiversityScore,
                      Metric tasteEvolutionScore, Metric replayIntensityScore, String outro) {
        this.profile = profile; this.savedTrackCount = savedTrackCount; this.libraryGrowth = libraryGrowth; this.topTracks = topTracks; this.topArtists = topArtists;
        this.estimatedListeningTime = estimatedListeningTime; this.estimatedYearListeningTime = estimatedYearListeningTime; this.totalTracksListened = totalTracksListened;
        this.totalAlbumsListened = totalAlbumsListened; this.topGenres = topGenres; this.hiddenGems = hiddenGems;
        this.tasteDiversityScore = tasteDiversityScore; this.tasteEvolutionScore = tasteEvolutionScore;
        this.replayIntensityScore = replayIntensityScore; this.outro = outro;
    }
    public record Metric(Double value, String unit, boolean available, String note) {}
    public record LibraryGrowth(int savedIn2025, int savedIn2026, int difference, String trend, boolean available, String note) {}
    public record GenreCount(String genre, int artistCount) {}

    // Compatibility accessors for callers of the first Wrap API; the UI uses the new fields above.
    @JsonIgnore public int recentListeningMinutes() { return estimatedListeningTime.available() ? (int) (estimatedListeningTime.value() / 60000) : topTracks.stream().mapToInt(SpotifySnapshot.Track::durationMs).sum() / 60000; }
    @JsonIgnore public SpotifySnapshot.Artist topArtist() { return topArtists.isEmpty() ? null : topArtists.get(0); }
    @JsonIgnore public SpotifySnapshot.Album topAlbum() { return topTracks.isEmpty() ? null : topTracks.get(0).album(); }
    @JsonIgnore public double albumConcentration() { return 0; }
    @JsonIgnore public String topGenre() { return topGenres.isEmpty() ? "Unknown" : AnalyticsService.normalizeGenre(topGenres.get(0).genre()); }
    @JsonIgnore public SpotifySnapshot.Artist risingStar() { return null; }
    @JsonIgnore public double artistLoyalty() { return 0; }
    @JsonIgnore public double genreDiversity() { return tasteDiversityScore.available() ? tasteDiversityScore.value() : 0; }
    @JsonIgnore public double familiarityRatio() { return 0; }
    @JsonIgnore public double discoveryRatio() { return 0; }
    @JsonIgnore public Personality personality() { return new Personality("WRAP", "Your Wrap", "Your current Spotify samples tell this story."); }
}
