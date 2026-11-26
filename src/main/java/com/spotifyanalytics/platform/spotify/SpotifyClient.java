package com.spotifyanalytics.platform.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.spotifyanalytics.platform.auth.SpotifyTokenService;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

@Service
public class SpotifyClient {
    private static final Logger log = LoggerFactory.getLogger(SpotifyClient.class);
    private final SpotifyTokenService tokens;
    private final CloseableHttpClient httpClient;
    private final RestClient rest;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SpotifyClient(SpotifyTokenService tokens) {
        this.tokens = tokens;

        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(50)
                .setMaxConnPerRoute(20)
                .build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(Timeout.ofSeconds(2))
                .setResponseTimeout(Timeout.ofSeconds(15))
                .build();
        this.httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                .evictExpiredConnections()
                .build();
        this.rest = RestClient.builder()
                .baseUrl("https://api.spotify.com/v1")
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }

    public SpotifySnapshot fetch(OAuth2AuthenticationToken authentication) {
        String token = tokens.validAccessToken(authentication.getName());
        Future<SpotifySnapshot.Profile> profile = executor.submit(() -> timed("profile", () -> profile(token)));
        Future<SavedCounts> saved = executor.submit(() -> timed("saved-track-counts", () -> savedTrackCounts(token)));
        Future<List<SpotifySnapshot.Track>> longTracks = executor.submit(() -> timed("top-tracks-long-term", () -> topTracks(token, "long_term")));
        Future<List<SpotifySnapshot.Track>> shortTracks = executor.submit(() -> timed("top-tracks-short-term", () -> topTracks(token, "short_term")));
        Future<List<SpotifySnapshot.Artist>> artists = executor.submit(() -> timed("top-artists", () -> topArtists(token)));
        Future<List<SpotifySnapshot.PlayedTrack>> recent = executor.submit(() -> timed("recently-played", () -> recentlyPlayed(token)));

        try {
            SavedCounts savedCounts = await(saved);
            return new SpotifySnapshot(await(profile), savedCounts.total(), savedCounts.saved2025(), savedCounts.saved2026(),
                    await(longTracks), await(shortTracks), await(artists), await(recent));
        }
        catch (RuntimeException exception) {
            profile.cancel(true);
            saved.cancel(true);
            longTracks.cancel(true);
            shortTracks.cancel(true);
            artists.cancel(true);
            recent.cancel(true);
            throw exception;
        }
    }

    private <T> T timed(String endpoint, Supplier<T> operation) {
        long started = System.nanoTime();
        try {
            T result = operation.get();
            log.info("spotify_endpoint={} duration_ms={} outcome=success", endpoint, elapsedMillis(started));
            return result;
        }
        catch (RuntimeException exception) {
            log.info("spotify_endpoint={} duration_ms={} outcome=failure", endpoint, elapsedMillis(started));
            throw exception;
        }
    }

    private long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private <T> T await(Future<T> future) {
        try {
            return future.get();
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SpotifyException("Spotify request was interrupted.", true, exception);
        }
        catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw new SpotifyException("Spotify is temporarily unavailable. Please try again.", true, exception);
        }
    }
    private SpotifySnapshot.Profile profile(String token) {
        JsonNode n = get(token, "/me");
        return new SpotifySnapshot.Profile(n.path("display_name").asText(null), image(n));
    }

    private SavedCounts savedTrackCounts(String token) {
        JsonNode first = get(token, "/me/tracks?limit=50&offset=0");
        long total = 0; int saved2025 = 0; int saved2026 = 0; int offset = 0;

        while (true) {
            JsonNode page = offset == 0 ? first : get(token, "/me/tracks?limit=50&offset=" + offset);
            int count = page.path("items").size();
            total += count;
            for (JsonNode item : page.path("items")) {
                try {
                    int year = Instant.parse(item.path("added_at").asText()).atZone(ZoneOffset.UTC).getYear();
                    if (year == 2025) saved2025++;
                    if (year == 2026) saved2026++;
                }
                catch (RuntimeException ignored) {
                    /* Missing added_at is unavailable, not invented. */
                }
            }
            if (count < 50) return new SavedCounts(first.path("total").isNumber() ? first.path("total").asLong() : total, saved2025, saved2026);
            offset += count;
        }
    }
    private List<SpotifySnapshot.Track> topTracks(String token, String range) {
        JsonNode root = get(token, "/me/top/tracks?time_range=" + range + "&limit=50");
        List<SpotifySnapshot.Track> out = new ArrayList<>();
        root.path("items").forEach(n -> out.add(track(n)));
        return out;
    }

    private List<SpotifySnapshot.Artist> topArtists(String token) {
        JsonNode root = get(token, "/me/top/artists?time_range=long_term&limit=50");
        List<SpotifySnapshot.Artist> out = new ArrayList<>();
        root.path("items").forEach(n -> out.add(artist(n)));
        return out;
    }

    private List<SpotifySnapshot.PlayedTrack> recentlyPlayed(String token) {
        JsonNode root = get(token, "/me/player/recently-played?limit=50");
        List<SpotifySnapshot.PlayedTrack> out = new ArrayList<>();
        root.path("items").forEach(n -> {
            try {
                out.add(new SpotifySnapshot.PlayedTrack(track(n.path("track")),
                        Instant.parse(n.path("played_at").asText())));
            }
            catch (RuntimeException ignored) {} });
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

    private SpotifySnapshot.Artist artist(JsonNode n) {
        List<String> genres = new ArrayList<>();
        n.path("genres").forEach(g -> genres.add(g.asText()));
        return new SpotifySnapshot.Artist(n.path("id").asText(null), n.path("name").asText(null), genres, image(n));
    }

    private SpotifySnapshot.Track track(JsonNode n) {
        JsonNode as = n.path("artists");
        SpotifySnapshot.Artist a = as.isArray() && !as.isEmpty() ? artist(as.get(0)) : new SpotifySnapshot.Artist(null, null, List.of(), null);
        JsonNode album = n.path("album");
        return new SpotifySnapshot.Track(n.path("id").asText(null), n.path("name").asText(null), n.path("duration_ms").asInt(0), a, album.path("id").asText(null), album.path("name").asText(null), image(album));
    }

    private String image(JsonNode n) {
        return n.path("images").isArray() && !n.path("images").isEmpty() ? n.path("images").get(0).path("url").asText(null) : null;
    }
    private record SavedCounts(long total, int saved2025, int saved2026) {}

    @PreDestroy
    void shutdown() {
        executor.close();
        try {
            httpClient.close();
        }
        catch (Exception exception) {
            log.warn("Could not close Spotify HTTP client cleanly.", exception);
        }
    }

    public static class SpotifyException extends RuntimeException {
        private final boolean retryable;

        public SpotifyException(String m, boolean r) {
            super(m); retryable = r;
        }

        public SpotifyException(String m, boolean r, Throwable c) {
            super(m, c); retryable = r;
        }
        public boolean retryable() {
            return retryable;
        }
    }
}
