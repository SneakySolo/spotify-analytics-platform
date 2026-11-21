package com.wrapitup.wrap_it_up.web;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
public class MeController {
    @GetMapping("/api/me")
    public Map<String,String> me(Authentication authentication) { return Map.of("spotifyUserId", authentication.getName()); }
}
