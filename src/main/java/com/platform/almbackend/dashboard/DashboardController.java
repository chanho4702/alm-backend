package com.platform.almbackend.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.platform.common.error.ForbiddenException;
import com.platform.common.error.NotFoundException;
import com.platform.almbackend.domain.Dashboard;
import com.platform.almbackend.repository.DashboardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static com.platform.almbackend.issue.IssueController.userId;

/**
 * 대시보드(지라 Dashboards) — 내 것 + 공유된 것. 가젯 배치는 JSON 배열 그대로 저장하고 프론트가 해석한다.
 * 서버는 배열 형태·개수 상한만 검사한다.
 */
@RestController
@RequiredArgsConstructor
@Transactional
public class DashboardController {
    private static final int MAX_GADGETS = 24;

    private final DashboardRepository dashboards;
    private final ObjectMapper json;

    public record DashboardResponse(long id, long ownerId, String name, boolean shared, List<Map<String, Object>> gadgets,
                                    Instant createdAt, Instant updatedAt) {}
    public record DashboardRequest(String name, Boolean shared, List<Map<String, Object>> gadgets) {}

    @GetMapping("/api/alm/dashboards")
    @Transactional(readOnly = true)
    public List<DashboardResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return dashboards.findByOwnerIdOrSharedTrueOrderByCreatedAtAscIdAsc(userId(jwt)).stream().map(this::response).toList();
    }

    @GetMapping("/api/alm/dashboards/{id}")
    @Transactional(readOnly = true)
    public DashboardResponse get(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        Dashboard dashboard = require(id);
        if (!dashboard.isShared() && dashboard.getOwnerId() != userId(jwt)) throw new NotFoundException("대시보드를 찾을 수 없습니다");
        return response(dashboard);
    }

    @PostMapping("/api/alm/dashboards")
    @ResponseStatus(HttpStatus.CREATED)
    public DashboardResponse create(@RequestBody DashboardRequest request, @AuthenticationPrincipal Jwt jwt) {
        Dashboard saved = dashboards.save(Dashboard.of(userId(jwt), requireName(request.name()),
                Boolean.TRUE.equals(request.shared()), serialize(request.gadgets()), now()));
        return response(saved);
    }

    @PutMapping("/api/alm/dashboards/{id}")
    public DashboardResponse update(@PathVariable long id, @RequestBody DashboardRequest request, @AuthenticationPrincipal Jwt jwt) {
        Dashboard dashboard = requireOwned(id, userId(jwt));
        Instant at = now();
        if (request.name() != null) dashboard.rename(requireName(request.name()), at);
        if (request.shared() != null) dashboard.share(request.shared(), at);
        if (request.gadgets() != null) dashboard.replaceGadgets(serialize(request.gadgets()), at);
        return response(dashboard);
    }

    @DeleteMapping("/api/alm/dashboards/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        dashboards.delete(requireOwned(id, userId(jwt)));
    }

    private Dashboard require(long id) {
        return dashboards.findById(id).orElseThrow(() -> new NotFoundException("대시보드를 찾을 수 없습니다"));
    }

    private Dashboard requireOwned(long id, long userId) {
        Dashboard dashboard = require(id);
        if (dashboard.getOwnerId() != userId) throw new ForbiddenException("본인 대시보드만 수정할 수 있습니다");
        return dashboard;
    }

    private DashboardResponse response(Dashboard d) {
        List<Map<String, Object>> gadgets;
        try {
            gadgets = json.readValue(d.getGadgetsJson(), new TypeReference<List<Map<String, Object>>>() {});
        } catch (JsonProcessingException e) {
            gadgets = List.of();
        }
        return new DashboardResponse(d.getId(), d.getOwnerId(), d.getName(), d.isShared(), gadgets, d.getCreatedAt(), d.getUpdatedAt());
    }

    private String serialize(List<Map<String, Object>> gadgets) {
        if (gadgets == null) return "[]";
        if (gadgets.size() > MAX_GADGETS) throw new IllegalArgumentException("가젯은 최대 " + MAX_GADGETS + "개까지 놓을 수 있습니다");
        try {
            return json.writeValueAsString(gadgets);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("가젯 배치를 저장할 수 없습니다");
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("대시보드 이름을 입력하세요");
        if (name.trim().length() > 120) throw new IllegalArgumentException("대시보드 이름은 120자 이하여야 합니다");
        return name.trim();
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
