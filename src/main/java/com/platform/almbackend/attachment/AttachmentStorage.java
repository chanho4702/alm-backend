package com.platform.almbackend.attachment;

import org.springframework.core.io.Resource;

import java.io.InputStream;
import java.util.UUID;

/**
 * 첨부 바이트 저장소. 운영은 S3 호환(MinIO), 개발·테스트는 로컬 파일 — 서비스는 구현을 모른다.
 * 키는 저장소가 발급한다(UUID). 삭제 실패는 예외가 아니라 false — 메타 삭제를 되돌리지 않는다.
 */
public interface AttachmentStorage {
    /** @return 저장된 오브젝트의 (bucket, key). 로컬 저장소는 bucket이 null이다 */
    default StoredObject store(InputStream input, long contentLength, String contentType) {
        return store(input, contentLength, contentType, UUID.randomUUID().toString());
    }

    /**
     * 키를 호출자가 정하는 저장 — 자체 메타 테이블 없이 키만 들고 있는 곳(아바타)이 쓴다.
     * 키에 {@code /}를 넣어 접두를 만들 수 있다. 모든 구현은 {@link #requireSafeKey}로 키를 검증한다.
     */
    StoredObject store(InputStream input, long contentLength, String contentType, String key);

    /**
     * 저장소 밖을 가리키는 키를 거른다 — 구현이 파일 경로를 쓰든 오브젝트 키를 쓰든 같은 계약이다.
     *
     * S3는 {@code ..}를 경로로 해석하지 않아 탈출이 성립하지 않지만, 여기서 함께 막아야
     * 저장소를 바꿔 끼웠을 때 같은 키가 로컬 파일 저장소에서 갑자기 위험해지는 일이 없다.
     */
    static String requireSafeKey(String key) {
        if (key == null || key.isBlank() || key.startsWith("/")
                || key.contains("\\") || key.contains("..")) {
            throw new IllegalArgumentException("잘못된 첨부 키입니다");
        }
        return key;
    }

    /**
     * 이 저장소가 오브젝트를 넣는 bucket. 메타 행에 bucket을 따로 저장하지 않는 곳이
     * {@link #open}에 넘긴다. 로컬 파일 저장소는 bucket 개념이 없어 null이다.
     */
    default String defaultBucket() { return null; }

    Resource open(String bucket, String key);

    boolean delete(String bucket, String key);

    record StoredObject(String bucket, String key) {}
}
