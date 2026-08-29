package com.platform.almbackend.attachment;

import org.springframework.core.io.Resource;

import java.io.InputStream;

/**
 * 첨부 바이트 저장소. 운영은 S3 호환(MinIO), 개발·테스트는 로컬 파일 — 서비스는 구현을 모른다.
 * 키는 저장소가 발급한다(UUID). 삭제 실패는 예외가 아니라 false — 메타 삭제를 되돌리지 않는다.
 */
public interface AttachmentStorage {
    /** @return 저장된 오브젝트의 (bucket, key). 로컬 저장소는 bucket이 null이다 */
    StoredObject store(InputStream input, long contentLength, String contentType);

    Resource open(String bucket, String key);

    boolean delete(String bucket, String key);

    record StoredObject(String bucket, String key) {}
}
