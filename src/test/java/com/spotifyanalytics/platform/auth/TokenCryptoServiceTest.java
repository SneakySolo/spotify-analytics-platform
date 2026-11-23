package com.spotifyanalytics.platform.auth;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TokenCryptoServiceTest {

    @Test
    void roundTripUsesUniqueNonce() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        TokenCryptoService crypto = new TokenCryptoService(key);

        String first = crypto.encrypt("secret");
        String second = crypto.encrypt("secret");

        assertNotEquals(first, second);
        assertEquals("secret", crypto.decrypt(first));
        assertEquals("secret", crypto.decrypt(second));
    }
}
