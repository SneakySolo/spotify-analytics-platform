package com.spotifyanalytics.platform.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.spotifyanalytics.platform.auth.SpotifyTokenService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

@Service
public class SpotifyClient {
    private final SpotifyTokenService tokens;
    private final RestClient rest = RestClient.builder().baseUrl("https://api.spotify.com/v1").build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SpotifyClient(SpotifyTokenService tokens, com.fasterxml.jackson.databind.ObjectMapper ignored) { this.tokens = tokens; }

    /** Fetch one in-memory sample for this request. No Wrap data is persisted. */
    public SpotifySnapshot fetch(OAuth2AuthenticationToken authentication) {
        String token = tokens.validAccessToken(authentication.getName());
        Future<SpotifySnapshot.Profile> profile = executor.submit(() -> profile(token));
        Future<Long> saved = executor.submit(() -> savedTrackCount(token));
        Future<List<SpotifySnapshot.Track>> longTracks = executor.submit(() -> topTracks(token, "long_term"));
        Future<List<SpotifySnapshot.Track>> shortTracks = executor.submit(() -> topTracks(token, "short_term"));
        Future<List<SpotifySnapshot.Artist>> artists = executor.submit(() -> topArtists(token));
        Future<List<SpotifySnapshot.PlayedTrack>> recent = executor.submit(() -> recentlyPlayed(token));
        try {
            return new SpotifySnapshot(await(profile), await(saved), await(longTracks), await(shortTracks), await(artists), await(recent));
        } catch (RuntimeException ex) {
            profile.cancel(true); saved.cancel(true); longTracks.cancel(true); shortTracks.cancel(true); artists.cancel(true); recent.cancel(true);
            throw ex;
        }
    }
    private <T> T await(Future<T> future) {
        try { return future.get(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new SpotifyException("Spotify request was interrupted.", true, ex); }
        catch (ExecutionException ex) { if (ex.getCause() instanceof RuntimeException r) throw r; throw new SpotifyException("Spotify is temporarily unavailable. Please try again.", true, ex); }
    }
    private SpotifySnapshot.Profile profile(String token) { JsonNode n = get(token, "/me"); return new SpotifySnapshot.Profile(n.path("display_name").asText(null), image(n)); }
    private long savedTrackCount(String token) {
        JsonNode first = get(token, "/me/tracks?limit=50&offset=0");
        if (first.has("total")) return first.path("total").asLong();
        long total = 0; int offset = 0;
        while (true) { JsonNode page = offset == 0 ? first : get(token, "/me/tracks?limit=50&offset=" + offset); int count = page.path("items").size(); total += count; if (count < 50) return total; offset += count; }
    }
    private List<SpotifySnapshot.Track> topTracks(String token, String range) { JsonNode root = get(token, "/me/top/tracks?time_range=" + range + "&limit=50"); List<SpotifySnapshot.Track> out = new ArrayList<>(); root.path("items").forEach(n -> out.add(track(n))); return out; }
    private List<SpotifySnapshot.Artist> topArtists(String token) { JsonNode root = get(token, "/me/top/artists?time_range=long_term&limit=50"); List<SpotifySnapshot.Artist> out = new ArrayList<>(); root.path("items").forEach(n -> out.add(artist(n))); return out; }
    private List<SpotifySnapshot.PlayedTrack> recentlyPlayed(String token) {
        JsonNode root = get(token, "/me/player/recently-played?limit=50"); List<SpotifySnapshot.PlayedTrack> out = new ArrayList<>();
        root.path("items").forEach(n -> { try { out.add(new SpotifySnapshot.PlayedTrack(track(n.path("track")), Instant.parse(n.path("played_at").asText()))); } catch (RuntimeException ignored) {} });
        return out;
    }
    private JsonNode get(String token, String path) {
        for (int attempt = 0; attempt < 3; attempt++) try {
            JsonNode root = rest.get().uri(path).headers(h -> h.setBearerAuth(token)).retrieve().body(JsonNode.class);
            if (root == null || !root.isObject()) throw new SpotifyException("Spotify returned an invalid response.", false); return root;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() != 429 || attempt == 2) throw new SpotifyException("Spotify is temporarily unavailable. Please try again.", true, ex);
            try { Thread.sleep(200L * (attempt + 1)); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new SpotifyException("Spotify request was interrupted.", true, interrupted); }
        } catch (SpotifyException ex) { throw ex; } catch (Exception ex) { throw new SpotifyException("Spotify is temporarily unavailable. Please try again.", true, ex); }
        throw new SpotifyException("Spotify is temporarily unavailable. Please try again.", true);
    }
    private SpotifySnapshot.Artist artist(JsonNode n) { List<String> genres = new ArrayList<>(); n.path("genres").forEach(g -> genres.add(g.asText())); return new SpotifySnapshot.Artist(n.path("id").asText(null), n.path("name").asText(null), genres, image(n)); }
    private SpotifySnapshot.Track track(JsonNode n) { JsonNode as = n.path("artists"); SpotifySnapshot.Artist a = as.isArray() && !as.isEmpty() ? artist(as.get(0)) : new SpotifySnapshot.Artist(null, null, List.of(), null); JsonNode album = n.path("album"); return new SpotifySnapshot.Track(n.path("id").asText(null), n.path("name").asText(null), n.path("duration_ms").asInt(0), a, album.path("id").asText(null), album.path("name").asText(null), image(album)); }
    private String image(JsonNode n) { return n.path("images").isArray() && !n.path("images").isEmpty() ? n.path("images").get(0).path("url").asText(null) : null; }
    @PreDestroy void shutdown() { executor.close(); }
    public static class SpotifyException extends RuntimeException { private final boolean retryable; public SpotifyException(String m, boolean r) { super(m); retryable = r; } public SpotifyException(String m, boolean r, Throwable c) { super(m, c); retryable = r; } public boolean retryable() { return retryable; } }
}
