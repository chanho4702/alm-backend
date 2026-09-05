package com.platform.almbackend.personal;

import com.platform.almbackend.personal.PreferenceService.PreferenceUpdate;
import com.platform.almbackend.personal.PreferenceService.PreferenceView;
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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.platform.almbackend.config.NoOrgDependency;

/** 개인 설정 · 프로젝트 바로 가기 · 공지 배너 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Personalization", description = "개인 설정·프로젝트 바로 가기·공지 배너")
public class PersonalizationController {
    private final PreferenceService preferences;
    private final ProjectShortcutService shortcuts;
    private final SystemSettingService systemSettings;

    @NoOrgDependency
    @Operation(summary = "내 개인 설정을 조회한다")
    @GetMapping("/api/alm/me/preferences")
    public PreferenceView myPreferences(@AuthenticationPrincipal Jwt jwt) {
        return preferences.view(userId(jwt), jwt.getClaimAsString("email"));
    }

    @NoOrgDependency
    @Operation(summary = "내 개인 설정을 저장한다")
    @PutMapping("/api/alm/me/preferences")
    public PreferenceView savePreferences(@RequestBody PreferenceUpdate body, @AuthenticationPrincipal Jwt jwt) {
        return preferences.save(userId(jwt), jwt.getClaimAsString("email"), body);
    }

    @Operation(summary = "프로젝트 바로 가기를 조회한다")
    @GetMapping("/api/alm/projects/{projectId}/shortcuts")
    public List<ShortcutResponse> listShortcuts(@PathVariable long projectId, @AuthenticationPrincipal Jwt jwt) {
        return shortcuts.list(userId(jwt), projectId);
    }

    @Operation(summary = "프로젝트에 바로 가기를 만든다")
    @PostMapping("/api/alm/projects/{projectId}/shortcuts")
    @ResponseStatus(HttpStatus.CREATED)
    public ShortcutResponse createShortcut(@PathVariable long projectId, @RequestBody ShortcutRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return shortcuts.create(userId(jwt), projectId, request);
    }

    @Operation(summary = "바로 가기를 수정한다")
    @PutMapping("/api/alm/shortcuts/{id}")
    public ShortcutResponse updateShortcut(@PathVariable long id, @RequestBody ShortcutRequest request,
                                           @AuthenticationPrincipal Jwt jwt) {
        return shortcuts.update(userId(jwt), id, request);
    }

    @Operation(summary = "바로 가기를 삭제한다")
    @DeleteMapping("/api/alm/shortcuts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteShortcut(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        shortcuts.delete(userId(jwt), id);
    }

    /** 로그인한 누구나 본다 — 셸이 상단에 띄운다 */
    @NoOrgDependency
    @Operation(summary = "상단 공지 배너를 조회한다")
    @GetMapping("/api/alm/banner")
    public Banner banner() {
        return systemSettings.banner();
    }

    @Operation(summary = "상단 공지 배너를 저장한다 — 전역 관리자 전용")
    @PutMapping("/api/alm/admin/banner")
    @PreAuthorize("@globalAdmin.check(authentication)")
    public Banner saveBanner(@RequestBody Banner banner) {
        return systemSettings.saveBanner(banner);
    }
}
