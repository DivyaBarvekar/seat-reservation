package com.divya.seatReservation.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

@Service
public class TokenService {

    private static final Duration TTL = Duration.ofHours(24);
    private final byte[] secret;

    public TokenService(@Value("${app.auth.secret}") String secret) {
        if (secret.length() < 32) {
            throw new IllegalStateException("app.auth.secret must be at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Token format: base64(userId).expiryEpochSeconds.base64(hmac) */
    public String issue(String userId) {
        long exp = Instant.now().plus(TTL).getEpochSecond();
        String payload = b64(userId.getBytes(StandardCharsets.UTF_8)) + "." + exp;
        return payload + "." + b64(hmac(payload));
    }

    /** Returns the userId if the token is genuine and not expired; empty otherwise. */
    public Optional<String> verify(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return Optional.empty();

            String payload = parts[0] + "." + parts[1];
            byte[] given = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(hmac(payload), given)) return Optional.empty();

            long exp = Long.parseLong(parts[1]);
            if (Instant.now().getEpochSecond() > exp) return Optional.empty();

            return Optional.of(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) { // bad base64 or bad number
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); // new instance per call: Mac is not thread-safe
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}