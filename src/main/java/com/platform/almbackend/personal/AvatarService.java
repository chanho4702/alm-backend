package com.platform.almbackend.personal;

import com.platform.almbackend.attachment.AttachmentMediaTypes;
import com.platform.almbackend.attachment.AttachmentStorage;
import com.platform.almbackend.domain.UserPreference;
import com.platform.almbackend.repository.UserPreferenceRepository;
import com.platform.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 사용자 아바타. 바이트는 첨부와 같은 저장소(MinIO/로컬 파일)에 두고, 개인 설정 행에는 키만 남긴다 —
 * issue_attachment 행은 만들지 않는다(아바타는 이슈에 딸린 파일이 아니고 권한 규칙도 다르다).
 *
 * 형식은 클라이언트가 보낸 Content-Type이 아니라 매직 바이트로 판별한다(첨부와 같은 정책):
 * 스크립트를 실을 수 있는 SVG/HTML이 이름만 바꿔 아바타로 들어오면 그대로 인라인 표시된다.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AvatarService {
    /** 프로필 사진 한 장에 필요한 크기 — 넘으면 400 */
    public static final long MAX_BYTES = 2L * 1024 * 1024;

    /** 판별된 타입 → 키 확장자. 읽을 때 이 확장자로 Content-Type을 되돌린다 */
    private static final Map<String, String> ALLOWED = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp");

    private final UserPreferenceRepository preferences;
    private final PreferenceService preferenceService;
    private final AttachmentStorage storage;

    /** 아바타 한 건의 주소와 버전 — 목록·업로드 응답이 같은 형태다 */
    public record AvatarView(long userId, String avatarUrl, Instant updatedAt) {
        static AvatarView of(UserPreference p) {
            return new AvatarView(p.getUserId(), PreferenceService.avatarUrl(p), p.getAvatarUpdatedAt());
        }
    }

    public record AvatarImage(Resource resource, String contentType) {}

    public AvatarView upload(long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("빈 파일은 올릴 수 없습니다");
        if (file.getSize() > MAX_BYTES) {
            throw new IllegalArgumentException("아바타는 2MB 이하 이미지여야 합니다");
        }
        String contentType;
        try (InputStream probe = file.getInputStream()) {
            contentType = AttachmentMediaTypes.detect(probe);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림 읽기 실패", e);
        }
        String extension = ALLOWED.get(contentType);
        if (extension == null) {
            throw new IllegalArgumentException("아바타는 PNG·JPG·WebP 이미지만 올릴 수 있습니다");
        }

        String key = "avatars/" + userId + "/" + UUID.randomUUID() + "." + extension;
        try (InputStream input = file.getInputStream()) {
            storage.store(input, file.getSize(), contentType, key);
        } catch (IOException e) {
            throw new UncheckedIOException("업로드 스트림 읽기 실패", e);
        }
        // 메타가 롤백되면 바이트만 남는다 — 롤백 시 방금 올린 오브젝트를 되돌린다
        deleteAfter(false, key);

        UserPreference stored = preferenceService.ensureRow(userId);
        String previous = stored.attachAvatar(key, Instant.now().truncatedTo(ChronoUnit.MICROS));
        // 이전 사진은 커밋이 확정된 뒤에 지운다 — 롤백된 교체가 쓰던 사진을 날리지 않게
        if (previous != null && !previous.equals(key)) deleteAfter(true, previous);
        return AvatarView.of(stored);
    }

    public void remove(long userId) {
        preferences.findById(userId).ifPresent(stored -> {
            String previous = stored.clearAvatar();
            if (previous != null) deleteAfter(true, previous);
        });
    }

    @Transactional(readOnly = true)
    public AvatarImage image(long userId) {
        UserPreference stored = preferences.findById(userId)
                .filter(UserPreference::hasAvatar)
                .orElseThrow(() -> new NotFoundException("아바타가 없습니다"));
        String key = stored.getAvatarKey();
        return new AvatarImage(storage.open(storage.defaultBucket(), key), contentTypeOf(key));
    }

    /** 아바타가 있는 사용자만 — 목록 화면이 한 번 받아 사용자별로 URL을 붙인다 */
    @Transactional(readOnly = true)
    public List<AvatarView> all() {
        return preferences.findByAvatarKeyIsNotNullOrderByUserIdAsc().stream().map(AvatarView::of).toList();
    }

    /** 확장자로 되돌린다 — 업로드 때 매직 바이트로 판별한 타입만 키에 실렸다 */
    private static String contentTypeOf(String key) {
        for (Map.Entry<String, String> entry : ALLOWED.entrySet()) {
            if (key.endsWith("." + entry.getValue())) return entry.getKey();
        }
        return AttachmentMediaTypes.OCTET_STREAM;
    }

    private void deleteAfter(boolean onCommit, String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            if (onCommit) removeQuietly(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if ((status == STATUS_COMMITTED) == onCommit) removeQuietly(key);
            }
        });
    }

    private void removeQuietly(String key) {
        if (!storage.delete(storage.defaultBucket(), key)) {
            // 고아 오브젝트를 거두는 정리 잡은 없다 — 저장소에 바이트가 남고 키만 로그로 남는다.
            // 손으로 지우려면 이 경고의 key를 쓴다.
            log.warn("아바타 오브젝트 삭제 실패(고아로 남음) key={}", key);
        }
    }
}
