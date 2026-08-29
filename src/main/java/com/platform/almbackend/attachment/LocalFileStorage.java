package com.platform.almbackend.attachment;

import com.platform.almbackend.common.ServiceUnavailableException;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** 개발·테스트용 로컬 파일 저장소. 키는 UUID라 디렉터리 밖을 가리킬 수 없다. */
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
    public StoredObject store(InputStream input, long contentLength, String contentType) {
        String key = UUID.randomUUID().toString();
        try {
            Files.copy(input, root.resolve(key), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ServiceUnavailableException("첨부 저장 실패");
        }
        return new StoredObject(null, key);
    }

    @Override
    public Resource open(String bucket, String key) {
        Path file = root.resolve(requireSafeKey(key));
        if (!Files.exists(file)) {
            throw new ServiceUnavailableException("첨부 본문이 저장소에 없습니다");
        }
        return new FileSystemResource(file);
    }

    @Override
    public boolean delete(String bucket, String key) {
        try {
            return Files.deleteIfExists(root.resolve(requireSafeKey(key)));
        } catch (IOException e) {
            return false;
        }
    }

    private static String requireSafeKey(String key) {
        if (key == null || key.isBlank() || key.contains("/") || key.contains("\\") || key.contains("..")) {
            throw new IllegalArgumentException("잘못된 첨부 키입니다");
        }
        return key;
    }
}
