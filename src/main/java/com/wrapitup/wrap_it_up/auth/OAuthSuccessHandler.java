package com.wrapitup.wrap_it_up.auth;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.*;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.*;
import java.io.IOException; import java.time.Instant;

@Component
public class OAuthSuccessHandler implements AuthenticationSuccessHandler {
    private final OAuth2AuthorizedClientService clients; private final SpotifyConnectionRepository repo; private final TokenCryptoService crypto;
    public OAuthSuccessHandler(OAuth2AuthorizedClientService c,SpotifyConnectionRepository r,TokenCryptoService x){clients=c;repo=r;crypto=x;}
    public void onAuthenticationSuccess(HttpServletRequest req,HttpServletResponse res,Authentication auth)throws IOException{OAuth2AuthenticationToken token=(OAuth2AuthenticationToken)auth;OAuth2AuthorizedClient c=clients.loadAuthorizedClient("spotify",token.getName());OAuth2User user=token.getPrincipal();String id=user.getAttribute("id");if(id==null)throw new IllegalStateException("Spotify profile did not include an id");String refresh=c.getRefreshToken()==null?null:crypto.encrypt(c.getRefreshToken().getTokenValue());SpotifyConnection existing=repo.findById(id).orElse(null);if(existing==null)repo.save(new SpotifyConnection(id,user.getAttribute("display_name"),crypto.encrypt(c.getAccessToken().getTokenValue()),refresh,c.getAccessToken().getExpiresAt()));else{existing.updateTokens(crypto.encrypt(c.getAccessToken().getTokenValue()),refresh,c.getAccessToken().getExpiresAt());repo.save(existing);}res.sendRedirect("/");}
}
