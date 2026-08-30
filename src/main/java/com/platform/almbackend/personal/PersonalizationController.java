package com.platform.almbackend.personal;

import com.platform.almbackend.personal.PreferenceService.PreferenceBody;
import com.platform.almbackend.personal.ProjectShortcutService.ShortcutRequest;
import com.platform.almbackend.personal.ProjectShortcutService.ShortcutResponse;
import com.platform.almbackend.personal.SystemSettingService.Banner;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.platform.almbackend.issue.IssueController.userId;

/** 개인 설정 · 프로젝트 바로 가기 · 공지 배너 */
@RestController
@RequiredArgsConstructor
public class PersonalizationController {
    private final PreferenceService preferences;
    private final ProjectShortcutService shortcuts;
    private final SystemSettingService systemSettings;

    @GetMapping("/api/alm/me/preferences")
    public PreferenceBody myPreferences(@AuthenticationPrincipal Jwt jwt) {
        return preferences.get(userId(jwt));
    }

    @PutMapping("/api/alm/me/preferences")
    public PreferenceBody savePreferences(@RequestBody PreferenceBody body, @AuthenticationPrincipal Jwt jwt) {
        return preferences.save(userId(jwt), body);
    }

    @GetMapping("/api/alm/projects/{projectId}/shortcuts")
    public List<ShortcutResponse> listShortcuts(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return shortcuts.list(userId(jwt), projectId);
    }

    @PostMapping("/api/alm/projects/{projectId}/shortcuts")
    @ResponseStatus(HttpStatus.CREATED)
    public ShortcutResponse createShortcut(@PathVariable long projectId, @RequestBody ShortcutRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return shortcuts.create(userId(jwt), projectId, request);
    }

    @PutMapping("/api/alm/shortcuts/{id}")
    public ShortcutResponse updateShortcut(@PathVariable long id, @RequestBody ShortcutRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return shortcuts.update(userId(jwt), id, request);
    }

    @DeleteMapping("/api/alm/shortcuts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteShortcut(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        shortcuts.delete(userId(jwt), id);
    }

    /** 로그인한 누구나 본다 — 셸이 상단에 띄운다 */
    @GetMapping("/api/alm/banner")
    public Banner banner() {
        return systemSettings.banner();
    }

    @PutMapping("/api/alm/admin/banner")
    @PreAuthorize("hasRole('ADMIN')")
    public Banner saveBanner(@RequestBody Banner banner) {
        return systemSettings.saveBanner(banner);
    }
}
