package com.platform.almbackend.dev.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.InvalidKeyException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 웹훅 인증 — 비교는 전부 상수 시간({@link MessageDigest#isEqual})이다. 앞에서부터 같은 글자 수로 시크릿·서명을 한 글자씩
 * 맞춰 가는 타이밍 공격을 막는다.
 *
 * <ul>
 *   <li>GitHub: {@code X-Hub-Signature-256: sha256=<hex(HMAC-SHA256(secret, 원문 본문 바이트))>}. 파싱한 JSON이 아니라
 *       <b>받은 바이트 그대로</b> 계산한다 — 다시 직렬화하면 공백·순서가 달라 맞지 않는다. 옛 {@code X-Hub-Signature}(SHA-1)는 받지 않는다.</li>
 *   <li>GitLab: {@code X-Gitlab-Token}이 시크릿 원문이다(서명 없음). 길이까지 숨기려고 양쪽을 SHA-256으로 줄인 뒤 비교한다.</li>
 * </ul>
 */
public final class WebhookSignatures {

    private static final String PREFIX = "sha256=";

    private WebhookSignatures() {}

    public static boolean verifyGitHub(String secret, byte[] body, String header) {
        if (header == null || secret == null) return false;
        String value = header.trim();
        if (!value.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) return false;
        String provided = value.substring(PREFIX.length()).toLowerCase(Locale.ROOT);
        String expected = HexFormat.of().formatHex(hmacSha256(secret, body));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), provided.getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean verifyGitLab(String secret, String header) {
        if (header == null || secret == null) return false;
        return MessageDigest.isEqual(sha256(secret), sha256(header));
    }

    /** 테스트·문서용 — GitHub가 붙이는 헤더 값 그대로 */
    public static String gitHubHeader(String secret, byte[] body) {
        return PREFIX + HexFormat.of().formatHex(hmacSha256(secret, body));
    }

    private static byte[] hmacSha256(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256을 쓸 수 없습니다", e);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다", e);
        }
    }
}
