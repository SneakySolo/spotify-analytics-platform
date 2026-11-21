package com.wrapitup.wrap_it_up.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class TokenCryptoService {
    private final byte[] key; private final SecureRandom random = new SecureRandom();
    public TokenCryptoService(@Value("${encryption.key:}") String encodedKey) {
        if(encodedKey==null || encodedKey.isBlank()) key=null;
        else { try { key=Base64.getDecoder().decode(encodedKey); } catch(IllegalArgumentException e){ throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must be base64",e); } if(key.length!=32) throw new IllegalStateException("TOKEN_ENCRYPTION_KEY must decode to 32 bytes"); }
    }
    public String encrypt(String plaintext) { try { requireKey(); byte[] nonce=new byte[12];random.nextBytes(nonce);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));byte[] encrypted=c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));return Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(encrypted); } catch(Exception e){throw new IllegalStateException("Could not encrypt token",e);} }
    public String decrypt(String stored) { try { requireKey();String[] p=stored.split(":",2);Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(p[0])));return new String(c.doFinal(Base64.getDecoder().decode(p[1])),StandardCharsets.UTF_8); } catch(Exception e){throw new IllegalStateException("Could not decrypt token",e);} }
    private void requireKey(){if(key==null)throw new IllegalStateException("TOKEN_ENCRYPTION_KEY is required");}
}
