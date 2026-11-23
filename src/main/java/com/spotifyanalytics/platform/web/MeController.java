package com.spotifyanalytics.platform.web;

import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class MeController {
    @GetMapping("/api/me")
    public ResponseEntity<Map<String, Object>> me(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(401).body(Map.of(
                    "authenticated", false,
                    "message", "Connect Spotify to continue."));
        }

        return ResponseEntity.ok(Map.of(
                "authenticated", true,
                "spotifyUserId", authentication.getName()));
    }
}
