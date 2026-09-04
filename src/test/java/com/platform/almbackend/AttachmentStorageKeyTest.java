package com.platform.almbackend;

import com.platform.almbackend.attachment.AttachmentStorage;
import com.platform.almbackend.attachment.LocalFileStorage;
import com.platform.almbackend.attachment.S3AttachmentStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 키 검증은 인터페이스의 계약이다 — 저장소 구현을 바꿔 껴도 같은 키가 같은 판정을 받아야 한다.
 * S3는 {@code ..}를 경로로 해석하지 않아 탈출이 성립하지 않지만, 여기서 함께 막지 않으면
 * 로컬 파일 저장소로 갈아탄 순간 같은 키가 갑자기 위험해진다.
 */
class AttachmentStorageKeyTest {

    private static InputStream bytes() {
        return new ByteArrayInputStream(new byte[]{1, 2, 3});
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secrets", "avatars/../../etc/passwd", "/absolute/key", "", "  "})
    void S3도_저장소_밖을_가리키는_키를_거부한다(String key) {
        S3Client client = mock(S3Client.class);
        AttachmentStorage storage = new S3AttachmentStorage(client, "alm-attachments");

        assertThatThrownBy(() -> storage.store(bytes(), 3, "image/png", key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("잘못된 첨부 키입니다");
        // 검증에 걸린 요청은 저장소까지 가지 않는다
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secrets", "avatars/../../etc/passwd", "/absolute/key"})
    void 로컬_파일_저장소도_같은_키를_거부한다(String key, @org.junit.jupiter.api.io.TempDir Path root) {
        AttachmentStorage storage = new LocalFileStorage(root);

        assertThatThrownBy(() -> storage.store(bytes(), 3, "image/png", key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("잘못된 첨부 키입니다");
    }

    @Test
    void 접두가_있는_평범한_키는_통과한다() {
        assertThat(AttachmentStorage.requireSafeKey("avatars/7/1f2e3d.png")).isEqualTo("avatars/7/1f2e3d.png");
    }
}
