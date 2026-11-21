package com.wrapitup.wrap_it_up.wraps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wrapitup.wrap_it_up.analytics.AnalyticsService;
import com.wrapitup.wrap_it_up.analytics.WrapResult;
import com.wrapitup.wrap_it_up.spotify.SpotifyClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

@RestController
@RequestMapping("/wraps")
public class WrapController {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final SpotifyClient spotify;
    private final AnalyticsService analytics;

    public WrapController(StringRedisTemplate redis, ObjectMapper mapper, SpotifyClient spotify, AnalyticsService analytics) {
        this.redis = redis; this.mapper = mapper; this.spotify = spotify; this.analytics = analytics;
    }

    @PostMapping
    public ResponseEntity<?> generate(OAuth2AuthenticationToken authentication) {
        String key = "spotify_wrap:" + authentication.getName();
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return ResponseEntity.ok(mapper.readValue(cached, WrapResult.class));
        } catch (Exception ignored) { /* Redis is an optional cache: continue on failure. */ }
        WrapResult result = analytics.analyze(spotify.fetch(authentication));
        try { redis.opsForValue().set(key, mapper.writeValueAsString(result), Duration.ofMinutes(15)); }
        catch (Exception ignored) { /* A cache failure must not fail generation. */ }
        return ResponseEntity.ok(result);
    }
}
