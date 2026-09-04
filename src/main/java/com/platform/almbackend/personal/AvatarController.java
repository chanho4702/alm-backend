package com.platform.almbackend.personal;

import com.platform.almbackend.personal.AvatarService.AvatarView;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;

/**
 * 사용자 아바타. 올리고 지우는 것은 본인만이고(경로에 사용자 id가 없다), 보는 것은 로그인한 누구나다 —
 * 같은 조직의 담당자 셀·코멘트에 얼굴이 떠야 하기 때문이다.
 */
@RestController
@RequiredArgsConstructor
public class AvatarController {
    private final AvatarService avatars;

    @PutMapping("/api/alm/me/avatar")
    public AvatarView upload(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal Jwt jwt) {
        return avatars.upload(userId(jwt), file);
    }

    @DeleteMapping("/api/alm/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Jwt jwt) {
        avatars.remove(userId(jwt));
    }

    /**
     * 원본 타입 그대로 인라인. 짧게(5분) 사적 캐시를 허용하고 URL의 ?v=가 갱신을 밀어낸다 —
     * 목록 한 화면에 같은 사진이 수십 번 뜨는데 매번 받아올 이유가 없다.
     */
    @GetMapping("/api/alm/users/{userId}/avatar")
    public ResponseEntity<Resource> image(@PathVariable long userId) {
        AvatarService.AvatarImage image = avatars.image(userId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.resource());
    }

    /** 아바타가 있는 사용자만 — 목록 화면이 한 번 받아 사용자별 URL을 붙인다 */
    @GetMapping("/api/alm/users/avatars")
    public List<AvatarView> all() {
        return avatars.all();
    }
}
