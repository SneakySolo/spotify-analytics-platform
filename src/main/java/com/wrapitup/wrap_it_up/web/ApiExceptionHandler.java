package com.wrapitup.wrap_it_up.web;

import com.wrapitup.wrap_it_up.auth.SpotifyTokenService.ReauthRequiredException;
import com.wrapitup.wrap_it_up.spotify.SpotifyClient.SpotifyException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ReauthRequiredException.class)
    ResponseEntity<?> reauth(ReauthRequiredException ex){return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("code","REAUTH_REQUIRED","message",ex.getMessage()));}
    @ExceptionHandler(SpotifyException.class)
    ResponseEntity<?> spotify(SpotifyException ex){return ResponseEntity.status(ex.retryable()?HttpStatus.SERVICE_UNAVAILABLE:HttpStatus.BAD_GATEWAY).body(Map.of("code","SPOTIFY_ERROR","message",ex.getMessage()));}
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> generic(Exception ex){return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("code","WRAP_ERROR","message","We could not generate your Wrap right now. Please try again."));}
}
