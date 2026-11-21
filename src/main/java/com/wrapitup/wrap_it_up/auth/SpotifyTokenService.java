package com.wrapitup.wrap_it_up.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class SpotifyTokenService {

    private final SpotifyConnectionRepository repository;
    private final TokenCryptoService crypto;
    private final RestClient http =
            RestClient.builder().baseUrl("https://accounts.spotify.com").build();
    private final String clientId;
    private final String clientSecret;

    public SpotifyTokenService(
            SpotifyConnectionRepository repository,
            TokenCryptoService crypto,
            Environment environment) {
        this.repository = repository;
        this.crypto = crypto;
        this.clientId = environment.getProperty("SPOTIFY_CLIENT_ID", "");
        this.clientSecret = environment.getProperty("SPOTIFY_CLIENT_SECRET", "");
    }

    public String validAccessToken(String userId) {
        SpotifyConnection connection = repository.findById(userId)
                .orElseThrow(() -> new ReauthRequiredException("Connect Spotify again."));

        if ("DISCONNECTED".equals(connection.getConnectionStatus())) {
            throw new ReauthRequiredException("Connect Spotify again.");
        }

        Instant expiry = connection.getAccessTokenExpiresAt();
        if (expiry != null && expiry.isAfter(Instant.now().plusSeconds(60))) {
            return crypto.decrypt(connection.getAccessTokenCiphertext());
        }
        return refresh(connection);
    }

    private String refresh(SpotifyConnection connection) {
        try {
            String credentials = clientId + ":" + clientSecret;
            String basicAuth = Base64.getEncoder().encodeToString(
                    credentials.getBytes(StandardCharsets.UTF_8));
            String refreshToken = crypto.decrypt(
                    connection.getRefreshTokenCiphertext());

            JsonNode response = http.post()
                    .uri("/api/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .header("Authorization", "Basic " + basicAuth)
                    .body("grant_type=refresh_token&refresh_token=" + refreshToken)
                    .retrieve()
                    .body(JsonNode.class);

            String accessToken = response.path("access_token").asText();
            String replacementRefreshToken =
                    response.path("refresh_token").asText(null);
            long expiresIn = response.path("expires_in").asLong(3600);

            connection.updateTokens(
                    crypto.encrypt(accessToken),
                    replacementRefreshToken == null
                            ? null
                            : crypto.encrypt(replacementRefreshToken),
                    Instant.now().plusSeconds(expiresIn));
            repository.save(connection);
            return accessToken;
        } catch (HttpClientErrorException.BadRequest exception) {
            if (exception.getResponseBodyAsString().contains("invalid_grant")) {
                connection.disconnect();
                repository.save(connection);
                throw new ReauthRequiredException(
                        "Your Spotify connection expired. Connect again.");
            }
            throw exception;
        }
    }

    public static class ReauthRequiredException extends RuntimeException {
        public ReauthRequiredException(String message) {
            super(message);
        }
    }
}
