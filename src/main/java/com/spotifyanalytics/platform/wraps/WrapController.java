package com.spotifyanalytics.platform.wraps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotifyanalytics.platform.analytics.AnalyticsService;
import com.spotifyanalytics.platform.analytics.WrapResult;
import com.spotifyanalytics.platform.auth.SpotifyTokenService;
import com.spotifyanalytics.platform.spotify.SpotifyClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/wraps")
public class WrapController {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final SpotifyClient spotify;
    private final AnalyticsService analytics;
    private final Duration cacheTtl;

    public WrapController(StringRedisTemplate redis, ObjectMapper mapper, SpotifyClient spotify,
                          AnalyticsService analytics,
                          @Value("${cache.wrap.ttl:15m}") Duration cacheTtl) {
        this.redis = redis;
        this.mapper = mapper;
        this.spotify = spotify;
        this.analytics = analytics;
        this.cacheTtl = cacheTtl;
    }

    @PostMapping
    public ResponseEntity<?> generate(OAuth2AuthenticationToken authentication) {
        String key = "spotify_wrap:" + authentication.getName();
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                JsonNode cachedResult = mapper.readTree(cached);
                return ResponseEntity.ok(cachedResult);
            }
        } catch (Exception ignored) {
            // Redis is an optional performance cache; generate a fresh Wrap if unavailable.
        }
        try {
            WrapResult result = analytics.analyze(spotify.fetch(authentication));
            try {
                redis.opsForValue().set(key, mapper.writeValueAsString(result), cacheTtl);
            } catch (Exception ignored) {
                // A cache write failure must not fail Wrap generation.
            }
            return ResponseEntity.ok(result);
        } catch (SpotifyTokenService.ReauthRequiredException exception) {
            return ResponseEntity.status(401).body(Map.of(
                    "code", "reauth_required",
                    "message", exception.getMessage()));
        } catch (SpotifyClient.SpotifyException exception) {
            return ResponseEntity.status(502).body(Map.of(
                    "code", "spotify_error",
                    "message", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "code", "generation_failed",
                    "message", "We could not generate your wrap. Please try again."));
        }
    }
}
