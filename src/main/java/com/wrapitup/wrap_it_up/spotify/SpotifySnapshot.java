package com.wrapitup.wrap_it_up.spotify;

import java.util.List;

public record SpotifySnapshot(List<Artist> shortTermArtists, List<Artist> mediumTermArtists,
                              List<Artist> longTermArtists, List<Track> longTermTracks,
                              List<Track> recentlyPlayed) {
    public record Artist(String id, String name, List<String> genres, String imageUrl) {}
    public record Album(String id, String name, String imageUrl) {}
    public record Track(String id, String name, int durationMs, Artist artist,
                        String albumId, String albumName, String imageUrl) {
        public Album album() { return new Album(albumId, albumName, imageUrl); }
    }
}
