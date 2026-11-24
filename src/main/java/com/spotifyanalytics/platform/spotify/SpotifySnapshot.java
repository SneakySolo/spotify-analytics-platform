package com.spotifyanalytics.platform.spotify;

import java.time.Instant;
import java.util.List;

/** The in-memory Spotify responses needed to create one Wrap. */
public record SpotifySnapshot(
        Profile profile, long savedTrackCount, int savedTracks2025, int savedTracks2026, List<Track> longTermTracks,
        List<Track> shortTermTracks, List<Artist> longTermArtists,
        List<PlayedTrack> recentlyPlayed) {
    /** Compatibility constructor for the original in-memory test fixture shape. */
    public SpotifySnapshot(List<Artist> shortTermArtists, List<Artist> mediumTermArtists,
                           List<Artist> longTermArtists, List<Track> longTermTracks,
                           List<Track> recentlyPlayed) {
        this(null, 0, 0, 0, longTermTracks, List.of(), longTermArtists,
                recentlyPlayed == null ? List.of() : recentlyPlayed.stream()
                        .map(track -> new PlayedTrack(track, Instant.EPOCH)).toList());
    }
    public record Profile(String displayName, String imageUrl) {}
    public record Artist(String id, String name, List<String> genres, String imageUrl) {
        public Artist { genres = genres == null ? List.of() : List.copyOf(genres); }
    }
    public record Album(String id, String name, String imageUrl) {}
    public record Track(String id, String name, int durationMs, Artist artist,
                        String albumId, String albumName, String imageUrl) {
        public Album album() { return new Album(albumId, albumName, imageUrl); }
    }
    public record PlayedTrack(Track track, Instant playedAt) {}
}
