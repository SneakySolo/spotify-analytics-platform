package com.wrapitup.wrap_it_up.web;

import com.wrapitup.wrap_it_up.auth.SpotifyTokenService.ReauthRequiredException;
import com.wrapitup.wrap_it_up.spotify.SpotifyClient.SpotifyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ReauthRequiredException.class)
    ResponseEntity<?> reauth(ReauthRequiredException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "code", "REAUTH_REQUIRED",
                        "message", exception.getMessage()));
    }

    @ExceptionHandler(SpotifyException.class)
    ResponseEntity<?> spotify(SpotifyException exception) {
        HttpStatus status = exception.retryable()
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status)
                .body(Map.of(
                        "code", "SPOTIFY_ERROR",
                        "message", exception.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> generic(Exception exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "code", "WRAP_ERROR",
                        "message", "We could not generate your Wrap right now. Please try again."));
    }
}
