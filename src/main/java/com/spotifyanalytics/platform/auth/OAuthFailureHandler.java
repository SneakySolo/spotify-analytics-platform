package com.spotifyanalytics.platform.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
public class OAuthFailureHandler implements AuthenticationFailureHandler {
    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException, ServletException {
        String errorCode = "login_failed";
        if (exception instanceof OAuth2AuthenticationException oauthException
                && oauthException.getError() != null
                && oauthException.getError().getErrorCode() != null) {
            errorCode = oauthException.getError().getErrorCode();
        }
        response.sendRedirect("/wrap?oauth_error="
                + URLEncoder.encode(errorCode, StandardCharsets.UTF_8));
    }
}
