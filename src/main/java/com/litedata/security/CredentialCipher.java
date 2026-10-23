package com.litedata.security;

import com.litedata.config.PlatformProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class CredentialCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();
    public CredentialCipher(PlatformProperties properties) {
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(properties.encryptionKey()); }
        catch (Exception e) { throw new IllegalArgumentException("PLATFORM_ENCRYPTION_KEY 必须是 Base64 编码的32字节密钥"); }
        if (bytes.length != 32) throw new IllegalArgumentException("加密密钥必须为32字节");
        key = new SecretKeySpec(bytes, "AES");
    }
    public String encrypt(String value) {
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            return Base64.getEncoder().encodeToString(nonce) + "." +
                    Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("凭据加密失败", e); }
    }
    public String decrypt(String encrypted) {
        try {
            String[] parts = encrypted.split("\\.");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("凭据解密失败，请检查密钥", e); }
    }
}

