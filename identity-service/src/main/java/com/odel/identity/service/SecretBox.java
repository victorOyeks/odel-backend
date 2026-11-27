package com.odel.identity.service;

import com.odel.common.*;
import com.odel.common.tenancy.*;
import com.odel.common.security.*;
import com.odel.common.audit.*;
import com.odel.common.files.*;

import com.odel.common.OdelProperties;
import com.odel.common.Tokens;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** AES-GCM for two-step secrets at rest. The key comes from ODEL_MFA_KEY, never the database. */
@Component
public class SecretBox {
    private final SecretKeySpec key;

    public SecretBox(OdelProperties props) {
        byte[] raw = Base64.getDecoder().decode(props.mfa().encryptionKey());
        if (raw.length != 32) throw new IllegalStateException("odel.mfa.encryption-key must be 32 bytes, base64");
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[12];
            Tokens.secureRandom().nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] enc = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(12 + enc.length).put(iv).put(enc).array());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(String box) {
        try {
            byte[] all = Base64.getDecoder().decode(box);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
