package com.wrapitup.wrap_it_up.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "spotify_users")
@Getter
public class SpotifyConnection {

    @Id
    @Column(name = "spotify_user_id", nullable = false)
    private String spotifyUserId;

    private String displayName;

    @Column(name = "access_token_ciphertext")
    private String accessTokenCiphertext;

    @Column(name = "refresh_token_ciphertext")
    private String refreshTokenCiphertext;

    private Instant accessTokenExpiresAt;

    @Column(nullable = false)
    private String connectionStatus = "CONNECTED";

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SpotifyConnection() {
    }

    public SpotifyConnection(
            String id,
            String name,
            String accessToken,
            String refreshToken,
            Instant expiresAt) {
        this.spotifyUserId = id;
        this.displayName = name;
        this.accessTokenCiphertext = accessToken;
        this.refreshTokenCiphertext = refreshToken;
        this.accessTokenExpiresAt = expiresAt;
        this.connectionStatus = "CONNECTED";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PrePersist
    void created() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    @PreUpdate
    void updated() {
        updatedAt = Instant.now();
    }

    public void updateTokens(
            String accessToken,
            String refreshToken,
            Instant expiresAt) {
        accessTokenCiphertext = accessToken;
        if (refreshToken != null) {
            refreshTokenCiphertext = refreshToken;
        }
        accessTokenExpiresAt = expiresAt;
        connectionStatus = "CONNECTED";
    }

    public void disconnect() {
        accessTokenCiphertext = null;
        refreshTokenCiphertext = null;
        accessTokenExpiresAt = null;
        connectionStatus = "DISCONNECTED";
    }
}
