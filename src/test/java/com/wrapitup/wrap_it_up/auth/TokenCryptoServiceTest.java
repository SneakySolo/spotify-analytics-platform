package com.wrapitup.wrap_it_up.auth;
import org.junit.jupiter.api.Test;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
class TokenCryptoServiceTest {
    @Test void roundTripUsesUniqueNonce(){String key=Base64.getEncoder().encodeToString(new byte[32]);var c=new TokenCryptoService(key);String a=c.encrypt("secret"),b=c.encrypt("secret");assertNotEquals(a,b);assertEquals("secret",c.decrypt(a));assertEquals("secret",c.decrypt(b));}
}
