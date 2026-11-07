package com.platform.almbackend.dev.webhook;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 웹훅 시크릿 암복호화(AES-256-GCM) — org-service {@code MailCrypto}(SMTP 비밀번호)와 같은 규칙이다.
 *
 * <p>키는 {@code ALM_SETTINGS_ENC_KEY}(32바이트 hex = 64자). <b>키가 없으면 시크릿을 만들지 않는다</b> — 평문 폴백을 두면
 * "설정은 됐는데 사실 평문"인 상태가 조용히 운영으로 나간다. 발급 시도는 400으로 거부하고, 나머지 개발 기록 기능(PAT 수집)은
 * 그대로 동작한다. HMAC 검증에 원문이 필요해 해시로는 둘 수 없다.
 *
 * <p>저장 형식은 base64({@code iv 12바이트 || 암호문+태그 16바이트}). IV는 매번 새로 뽑는다(GCM IV 재사용 금지).
 */
@Component
public class WebhookSecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey key; // 미설정이면 null
    private final SecureRandom random = new SecureRandom();

    public WebhookSecretCipher(@Value("${platform.alm.settings-enc-key:}") String hexKey) {
        if (hexKey == null || hexKey.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = HexFormat.of().parseHex(hexKey.trim());
        } catch (IllegalArgumentException e) {
            // 기동을 세운다: 잘못된 키로 뜨면 저장된 시크릿을 못 읽는 채로 "정상"처럼 보인다
            throw new IllegalStateException("ALM_SETTINGS_ENC_KEY는 32바이트 hex(64자)여야 합니다");
        }
        if (raw.length != KEY_BYTES) throw new IllegalStateException("ALM_SETTINGS_ENC_KEY는 32바이트 hex(64자)여야 합니다");
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plain) {
        if (key == null) throw new IllegalStateException("암호화 키가 없습니다");
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 시크릿을 암호화할 수 없습니다", e);
        }
    }

    /** @throws IllegalStateException 키가 없거나 바뀌었을 때 */
    public String decrypt(String stored) {
        if (key == null) throw new IllegalStateException("웹훅 시크릿을 읽을 수 없습니다 — ALM_SETTINGS_ENC_KEY가 없습니다");
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= IV_BYTES) throw new IllegalArgumentException("암호문이 너무 짧습니다");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("웹훅 시크릿을 읽을 수 없습니다 — ALM_SETTINGS_ENC_KEY가 바뀌었습니다");
        }
    }
}
