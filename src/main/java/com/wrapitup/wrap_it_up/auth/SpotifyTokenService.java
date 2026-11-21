package com.wrapitup.wrap_it_up.auth;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.*;
import java.util.Base64;

@Service
public class SpotifyTokenService {
    private final SpotifyConnectionRepository repo; private final TokenCryptoService crypto;
    private final RestClient http=RestClient.builder().baseUrl("https://accounts.spotify.com").build();
    private final String clientId; private final String clientSecret;
    public SpotifyTokenService(SpotifyConnectionRepository repo,TokenCryptoService crypto,org.springframework.core.env.Environment env){this.repo=repo;this.crypto=crypto;clientId=env.getProperty("SPOTIFY_CLIENT_ID","");clientSecret=env.getProperty("SPOTIFY_CLIENT_SECRET","");}
    public String validAccessToken(String userId){SpotifyConnection c=repo.findById(userId).orElseThrow(()->new ReauthRequiredException("Connect Spotify again."));if(c.getConnectionStatus().equals("DISCONNECTED"))throw new ReauthRequiredException("Connect Spotify again.");if(c.getAccessTokenExpiresAt()!=null&&c.getAccessTokenExpiresAt().isAfter(Instant.now().plusSeconds(60)))return crypto.decrypt(c.getAccessTokenCiphertext());return refresh(c);}
    private String refresh(SpotifyConnection c){try{String basic=Base64.getEncoder().encodeToString((clientId+":"+clientSecret).getBytes());JsonNode n=http.post().uri("/api/token").contentType(MediaType.APPLICATION_FORM_URLENCODED).header("Authorization","Basic "+basic).body("grant_type=refresh_token&refresh_token="+crypto.decrypt(c.getRefreshTokenCiphertext())).retrieve().body(JsonNode.class);String access=n.path("access_token").asText();String refresh=n.path("refresh_token").asText(null);long seconds=n.path("expires_in").asLong(3600);c.updateTokens(crypto.encrypt(access),refresh==null?null:crypto.encrypt(refresh),Instant.now().plusSeconds(seconds));repo.save(c);return access;}catch(org.springframework.web.client.HttpClientErrorException.BadRequest e){if(e.getResponseBodyAsString().contains("invalid_grant")){c.disconnect();repo.save(c);throw new ReauthRequiredException("Your Spotify connection expired. Connect again.");}throw e;}}
    public static class ReauthRequiredException extends RuntimeException { public ReauthRequiredException(String m){super(m);} }
}
