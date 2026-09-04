package com.platform.almbackend.attachment;

import com.platform.common.error.ServiceUnavailableException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
/**
 * 개발·테스트용 로컬 파일 저장소. 키는 루트 아래 상대 경로이고, 정규화 후에도 루트 안이어야 한다 —
 * 접두가 있는 키(avatars/12/uuid.png)를 허용하되 {@code ..}로 밖을 가리키지 못하게 한다.
 */
public class LocalFileStorage implements AttachmentStorage {
    private final Path root;

    public LocalFileStorage(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("첨부 디렉터리를 만들 수 없습니다: " + root, e);
        }
    }

    @Override
    public StoredObject store(InputStream input, long contentLength, String contentType, String key) {
        Path file = resolveSafely(key);
        try {
            Path parent = file.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ServiceUnavailableException("첨부 저장 실패");
        }
        return new StoredObject(null, key);
    }

    @Override
    public Resource open(String bucket, String key) {
        Path file = resolveSafely(key);
        if (!Files.exists(file)) {
            throw new ServiceUnavailableException("첨부 본문이 저장소에 없습니다");
        }
        return new FileSystemResource(file);
    }

    @Override
    public boolean delete(String bucket, String key) {
        try {
            return Files.deleteIfExists(resolveSafely(key));
        } catch (IOException e) {
            return false;
        }
    }

    /** 키를 루트 아래로만 푼다 — 공통 검증에 더해 정규화 후 루트 밖이면 거부한다 */
    private Path resolveSafely(String key) {
        AttachmentStorage.requireSafeKey(key);
        Path base = root.toAbsolutePath().normalize();
        Path file = base.resolve(key).normalize();
        if (!file.startsWith(base)) {
            throw new IllegalArgumentException("잘못된 첨부 키입니다");
        }
        return file;
    }
}
