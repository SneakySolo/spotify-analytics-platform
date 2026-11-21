package com.wrapitup.wrap_it_up.analytics;

import com.wrapitup.wrap_it_up.spotify.SpotifySnapshot;

import java.util.List;

public record WrapResult(
        int recentListeningMinutes,
        SpotifySnapshot.Artist topArtist,
        List<SpotifySnapshot.Track> topTracks,
        SpotifySnapshot.Album topAlbum,
        double albumConcentration,
        String topGenre,
        SpotifySnapshot.Artist risingStar,
        double artistLoyalty,
        double genreDiversity,
        double familiarityRatio,
        double discoveryRatio,
        Personality personality) {
}
