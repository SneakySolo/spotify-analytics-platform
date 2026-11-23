package com.wrapitup.wrap_it_up.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuthSuccessHandler implements AuthenticationSuccessHandler {

    private final OAuth2AuthorizedClientService clients;
    private final SpotifyConnectionRepository repository;
    private final TokenCryptoService crypto;

    public OAuthSuccessHandler(
            OAuth2AuthorizedClientService clients,
            SpotifyConnectionRepository repository,
            TokenCryptoService crypto) {
        this.clients = clients;
        this.repository = repository;
        this.crypto = crypto;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {
        OAuth2AuthenticationToken login =
                (OAuth2AuthenticationToken) authentication;
        OAuth2AuthorizedClient client =
                clients.loadAuthorizedClient("spotify", login.getName());
        OAuth2User user = login.getPrincipal();

        String userId = user.getAttribute("id");
        if (userId == null) {
            throw new IllegalStateException("Spotify profile did not include an id");
        }

        String refreshToken = client.getRefreshToken() == null
                ? null
                : crypto.encrypt(client.getRefreshToken().getTokenValue());
        String accessToken = crypto.encrypt(client.getAccessToken().getTokenValue());

        SpotifyConnection connection = repository.findById(userId).orElse(null);
        if (connection == null) {
            repository.save(new SpotifyConnection(
                    userId,
                    user.getAttribute("display_name"),
                    accessToken,
                    refreshToken,
                    client.getAccessToken().getExpiresAt()));
        } else {
            connection.updateTokens(
                    accessToken,
                    refreshToken,
                    client.getAccessToken().getExpiresAt());
            repository.save(connection);
        }

        response.sendRedirect("/wrap?autogenerate=1");
    }
}
