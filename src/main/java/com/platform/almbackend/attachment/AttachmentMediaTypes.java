package com.platform.almbackend.attachment;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

/**
 * 클라이언트가 보낸 Content-Type은 믿지 않고 매직 바이트로 판별한다(wiki-backend와 같은 정책).
 * 인라인 표시는 스크립트를 실을 수 없는 래스터 이미지만 허용한다 — SVG/HTML은 실행 벡터다.
 *
 * 아바타(personal)도 같은 판별을 쓴다 — 프로필 사진이라는 이름으로 SVG가 들어오면 안 된다.
 */
public final class AttachmentMediaTypes {
    public static final String OCTET_STREAM = "application/octet-stream";
    private static final Set<String> SAFE_INLINE = Set.of("image/png", "image/jpeg", "image/gif", "image/webp");

    private AttachmentMediaTypes() {}

    public static String detect(InputStream input) throws IOException {
        byte[] header = input.readNBytes(16);
        if (startsWith(header, new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) return "image/png";
        if (startsWith(header, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "image/jpeg";
        if (startsWith(header, ascii("GIF87a")) || startsWith(header, ascii("GIF89a"))) return "image/gif";
        if (header.length >= 12
                && Arrays.equals(Arrays.copyOfRange(header, 0, 4), ascii("RIFF"))
                && Arrays.equals(Arrays.copyOfRange(header, 8, 12), ascii("WEBP"))) return "image/webp";
        if (startsWith(header, ascii("%PDF-"))) return "application/pdf";
        return OCTET_STREAM;
    }

    static boolean isSafeInline(String contentType) {
        return SAFE_INLINE.contains(contentType);
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) return false;
        }
        return true;
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
