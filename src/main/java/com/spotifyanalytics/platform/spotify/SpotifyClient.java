package com.spotifyanalytics.platform.spotify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyanalytics.platform.auth.SpotifyTokenService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class SpotifyClient {
    private final SpotifyTokenService tokens;
    private final ObjectMapper mapper;
    private final RestClient rest = RestClient.builder().baseUrl("https://api.spotify.com/v1").build();
    private final ExecutorService fetchExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public SpotifyClient(SpotifyTokenService tokens, ObjectMapper mapper) {
        this.tokens = tokens;
        this.mapper = mapper;
    }

    public SpotifySnapshot fetch(OAuth2AuthenticationToken authentication) {
        String token = tokens.validAccessToken(authentication.getName());
        Future<List<SpotifySnapshot.Artist>> shortTermArtists =
                fetchExecutor.submit(() -> topArtists(token, "short_term", 20));
        Future<List<SpotifySnapshot.Artist>> mediumTermArtists =
                fetchExecutor.submit(() -> topArtists(token, "medium_term", 20));
        Future<List<SpotifySnapshot.Artist>> longTermArtists =
                fetchExecutor.submit(() -> topArtists(token, "long_term", 20));
        Future<List<SpotifySnapshot.Track>> topTracks =
                fetchExecutor.submit(() -> topTracks(token));
        Future<List<SpotifySnapshot.Track>> recentlyPlayed =
                fetchExecutor.submit(() -> recentlyPlayed(token));

        try {
            return new SpotifySnapshot(
                    await(shortTermArtists),
                    await(mediumTermArtists),
                    await(longTermArtists),
                    await(topTracks),
                    await(recentlyPlayed));
        } catch (RuntimeException exception) {
            shortTermArtists.cancel(true);
            mediumTermArtists.cancel(true);
            longTermArtists.cancel(true);
            topTracks.cancel(true);
            recentlyPlayed.cancel(true);
            throw exception;
        }
    }

    private <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new SpotifyException(
                    "Spotify request was interrupted.", true, interrupted);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new SpotifyException(
                    "Spotify is temporarily unavailable. Please try again.", true, cause);
        }
    }

    @PreDestroy
    void shutdown() {
        fetchExecutor.close();
    }

    private List<SpotifySnapshot.Artist> topArtists(String token, String range, int limit) {
        JsonNode root = get(token, "/me/top/artists?time_range=" + range + "&limit=" + limit);
        List<SpotifySnapshot.Artist> result = new ArrayList<>();
        root.path("items").forEach(n -> result.add(artist(n)));
        return result;
    }

    private List<SpotifySnapshot.Track> topTracks(String token) {
        JsonNode root = get(token, "/me/top/tracks?time_range=long_term&limit=50");
        List<SpotifySnapshot.Track> result = new ArrayList<>();
        root.path("items").forEach(n -> result.add(track(n)));
        return result;
    }

    private List<SpotifySnapshot.Track> recentlyPlayed(String token) {
        JsonNode root = get(token, "/me/player/recently-played?limit=50");
        List<SpotifySnapshot.Track> result = new ArrayList<>();
        root.path("items").forEach(n -> result.add(track(n.path("track"))));
        return result;
    }

    private JsonNode get(String token, String path) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                JsonNode root = rest.get()
                        .uri(path)
                        .headers(headers -> headers.setBearerAuth(token))
                        .retrieve()
                        .body(JsonNode.class);
                if (root == null || !root.isObject()) {
                    throw new SpotifyException(
                            "Spotify returned an invalid response.", false);
                }
                return root;
            } catch (RestClientResponseException ex) {
                if (ex.getStatusCode().value() != 429 || attempt == 2) {
                    throw new SpotifyException(
                            "Spotify is temporarily unavailable. Please try again.",
                            true,
                            ex);
                }
                try {
                    Thread.sleep(200L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SpotifyException(
                            "Spotify request was interrupted.",
                            true,
                            interrupted);
                }
            } catch (SpotifyException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new SpotifyException(
                        "Spotify is temporarily unavailable. Please try again.",
                        true,
                        ex);
            }
        }
        throw new SpotifyException("Spotify is temporarily unavailable. Please try again.", true);
    }

    private SpotifySnapshot.Artist artist(JsonNode n) {
        List<String> genres = new ArrayList<>(); n.path("genres").forEach(g -> genres.add(g.asText()));
        String image = n.path("images").path(0).path("url").asText(null);
        return new SpotifySnapshot.Artist(n.path("id").asText(), n.path("name").asText(), genres, image);
    }

    private SpotifySnapshot.Track track(JsonNode n) {
        JsonNode artists = n.path("artists");
        SpotifySnapshot.Artist a = artists.size() > 0 ? artist(artists.get(0)) : new SpotifySnapshot.Artist("", "Unknown", List.of(), null);
        JsonNode album = n.path("album");
        return new SpotifySnapshot.Track(n.path("id").asText(), n.path("name").asText(), n.path("duration_ms").asInt(), a,
                album.path("id").asText(), album.path("name").asText(), album.path("images").path(0).path("url").asText(null));
    }

    public static class SpotifyException extends RuntimeException {
        private final boolean retryable;

        public SpotifyException(String message, boolean retryable) {
            super(message); this.retryable = retryable;
        }
        public SpotifyException(String message, boolean retryable, Throwable cause) {
            super(message, cause); this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
