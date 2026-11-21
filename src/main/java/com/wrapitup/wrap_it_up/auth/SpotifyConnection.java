package com.wrapitup.wrap_it_up.auth;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "spotify_users")
public class SpotifyConnection {
    @Id @Column(name = "spotify_user_id", nullable = false)
    private String spotifyUserId;
    private String displayName;
    @Column(name = "access_token_ciphertext") private String accessTokenCiphertext;
    @Column(name = "refresh_token_ciphertext") private String refreshTokenCiphertext;
    private Instant accessTokenExpiresAt;
    @Column(nullable = false) private String connectionStatus = "CONNECTED";
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;

    protected SpotifyConnection() {}
    public SpotifyConnection(String id, String name, String access, String refresh, Instant expires) {
        this.spotifyUserId=id; this.displayName=name; this.accessTokenCiphertext=access;
        this.refreshTokenCiphertext=refresh; this.accessTokenExpiresAt=expires; this.connectionStatus="CONNECTED";
        this.createdAt=Instant.now(); this.updatedAt=this.createdAt;
    }
    @PrePersist void created() { if(createdAt==null)createdAt=Instant.now(); updatedAt=Instant.now(); }
    @PreUpdate void updated() { updatedAt=Instant.now(); }
    public String getSpotifyUserId(){return spotifyUserId;} public String getDisplayName(){return displayName;}
    public String getAccessTokenCiphertext(){return accessTokenCiphertext;} public String getRefreshTokenCiphertext(){return refreshTokenCiphertext;}
    public Instant getAccessTokenExpiresAt(){return accessTokenExpiresAt;} public String getConnectionStatus(){return connectionStatus;}
    public void updateTokens(String access,String refresh,Instant expires){accessTokenCiphertext=access;if(refresh!=null)refreshTokenCiphertext=refresh;accessTokenExpiresAt=expires;connectionStatus="CONNECTED";}
    public void disconnect(){accessTokenCiphertext=null;refreshTokenCiphertext=null;accessTokenExpiresAt=null;connectionStatus="DISCONNECTED";}
}
