package com.platform.almbackend.notification;

import com.platform.almbackend.directory.DirectoryMember;
import com.platform.almbackend.directory.MemberDirectory;
import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.Notification;
import com.platform.almbackend.personal.PreferenceService;
import com.platform.almbackend.settings.SchemeQueries;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 이메일 알림 채널 — 알림함에 한 건이 새로 생길 때 같은 내용을 메일로도 보낸다(wiki-backend와 같은 방식).
 *
 * <p>발송 자체는 <b>org-service가 한다</b>(2026-09-07 플랫폼 메일 설계). ALM은 SMTP를 모르고
 * {@link OrgMailClient}로 "이 주소에 이 내용을" 넘길 뿐이다 — 메일 서버 설정·자격증명·재시도·발송 로그가
 * 한 곳에 모이고, 관리자가 화면에서 끄면 세 서비스가 함께 꺼진다. 보낸 사람 주소도 org 설정이 정하므로
 * 여기서 지정하지 않는다. 메일을 쓸 수 있는지는 {@code /internal/org/mail/status}가 알려 준다 —
 * 개인 설정 응답의 {@code mailConfigured}가 그 값이다(스위치를 켰는데 아무것도 오지 않는 것이 최악의
 * 경험이라 먼저 알린다). 그 조회는 <b>커밋 뒤 발송 스레드에서 배치당 한 번</b>만 한다 — 저장 경로에서
 * 부르면 디렉터리 조회를 커밋 뒤로 뺀 이유(2026-09-05)를 그대로 되밟는다.
 *
 * 발송은 **커밋 뒤, 다른 스레드**에서 한다. 저장 트랜잭션 안에서 남의 서비스를 기다리면 이슈를 고친
 * 사람의 저장이 메일 허브 속도에 묶이고, 롤백된 저장의 메일이 먼저 나가 버린다. 실패는 warn 로그로만
 * 남긴다 — 메일은 알림함의 사본이지 원본이 아니다.
 *
 * <p>수신 주소를 org-service에서 읽는 gRPC 호출도 같은 이유로 커밋 뒤에 한다(2026-09-05). 트랜잭션 안에서
 * 부르면 DB 커넥션을 쥔 채 남의 서비스를 기다리게 되고, 워처가 많은 이슈일수록 그 대기가 배로 늘어난다.
 * 그래서 한 트랜잭션에서 생긴 알림을 모아 두었다가 커밋 뒤 {@code GetMembers(ids[])} <b>한 번</b>으로
 * 주소를 받는다. 반대로 행위자 이름 같은 요청 스코프 값은 지금 이 자리에서 캡처한다 — 메일 스레드에는
 * SecurityContext가 없다.
 */
@Component
@Slf4j
public class EmailNotifier {

    /**
     * 메일 본문은 플레인 텍스트라 아이콘을 실을 수 없다 — 앱이 색 있는 lucide 아이콘으로 하는 구분을
     * 메일에서는 이모지가 대신한다(디자인시스템의 "글자 기호 금지"는 앱 UI 규칙이고 메일은 예외다).
     * 소스에는 이스케이프로 둔다 — 빌드 환경의 인코딩에 흔들리지 않게.
     */
    private static final Map<Notification.Type, String> TYPE_EMOJI = Map.of(
            Notification.Type.ASSIGNED, "\uD83D\uDCCC",       // 압정
            Notification.Type.STATUS_CHANGED, "\uD83D\uDD04", // 화살표 순환
            Notification.Type.COMMENTED, "\uD83D\uDCAC",      // 말풍선
            Notification.Type.MENTIONED, "\uD83D\uDCE3");     // 확성기

    /** 상태 카테고리 의미별 — 이름과 함께 쓰고 이모지만으로 뜻을 전하지 않는다 */
    private static final Map<String, String> KIND_EMOJI = Map.of(
            "new", "\u26AA",              // 흰 동그라미
            "active", "\uD83D\uDD35",  // 파란 동그라미
            "complete", "\u2705");        // 체크

    private static final String BLANK_LINE = "\n\n";
    /** 오른쪽 화살표 — 메일 클라이언트가 폰트를 갈아도 깨지지 않게 이스케이프로 둔다 */
    private static final String ARROW = "\u2192";

    private final OrgMailClient mail;
    private final ObjectProvider<PreferenceService> preferences;
    private final ObjectProvider<MemberDirectory> directory;
    private final ObjectProvider<SchemeQueries> schemes;
    private final String publicUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "alm-mail");
        t.setDaemon(true);
        return t;
    });

    public EmailNotifier(OrgMailClient mail,
                         ObjectProvider<PreferenceService> preferences,
                         ObjectProvider<MemberDirectory> directory,
                         ObjectProvider<SchemeQueries> schemes,
                         @Value("${platform.alm.mail.public-url:http://localhost/alm}") String publicUrl) {
        this.mail = mail;
        this.preferences = preferences;
        this.directory = directory;
        this.schemes = schemes;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /**
     * 플랫폼 메일이 켜져 있는가 — 판단은 org의 메일 설정이 하고 여기서는 캐시된 답을 읽을 뿐이다.
     *
     * <p>이 값은 개인 설정 화면({@code mailConfigured})이 쓴다. 허브에 물어보는 호출이므로
     * <b>알림 저장 경로에서는 부르지 않는다</b> — 그 자리에서는 {@link #notify}가 I/O 없는
     * {@link OrgMailClient#configured()}만 본다.
     */
    public boolean configured() {
        return mail.enabled();
    }

    /** 수신 주소를 뺀 메일 한 통 — 주소는 커밋 뒤 디렉터리를 읽어 정한다 */
    record MailContent(String subject, String text) {}

    /** 커밋 뒤에 보낼 한 통 — 주소는 아직 모른다(디렉터리 조회가 커밋 뒤에 일어난다) */
    private record Pending(long userId, String snapshotEmail, MailContent content) {}

    /** 한 트랜잭션이 만든 발송 대기 묶음을 담아 두는 자리 */
    private static final String PENDING_KEY = EmailNotifier.class.getName() + ".pending";

    /**
     * 알림함에 새 행이 생긴 직후 호출한다. 인앱 알림을 보낼지(개인 설정의 종류별 on/off)는 이미
     * {@link NotificationService}가 판단했다 — 여기서는 이메일 채널 스위치와 주소만 본다.
     */
    public void notify(Notification saved, Issue issue) {
        notify(saved, issue, null);
    }

    /** 상태 변경은 이전 상태까지 받아 본문에 "상태: 할 일 -> 완료" 한 줄을 싣는다 */
    public void notify(Notification saved, Issue issue, String previousStatusId) {
        // 여기서 허브에 "메일 켜져 있냐"를 물으면 안 된다 — 이 메서드는 이슈 갱신의 비관적 락 트랜잭션
        // 안에서, 수신자마다 한 번씩 불린다(NotificationService). 캐시가 비었거나 org가 무응답이면
        // 락을 쥔 채 수신자 수만큼 5초를 기다리게 된다. 여기서는 주소·토큰이 있는지만 보고(순수 메모리),
        // 실제 사용 여부는 커밋 뒤 dispatch()가 배치당 한 번 확인한다.
        if (!mail.configured()) return;
        // 스위치와 스냅샷 주소는 지금 읽는다 — 이미 열려 있는 트랜잭션의 DB 조회이고, 메일 스레드에서
        // 다시 커넥션을 잡을 이유가 없다. 밖으로 미루는 것은 남의 서비스를 부르는 일뿐이다.
        PreferenceService.MailTarget target = preferences.getObject().mailTarget(saved.getUserId());
        if (!target.enabled()) return;
        // 본문도 여기서 만든다: 상태 이름을 읽는 SchemeQueries와 Issue 엔티티가 이 트랜잭션 것이다.
        MailContent content = compose(saved.getType(), issue, actorName(), previousStatusId);
        enqueue(new Pending(saved.getUserId(), target.snapshotEmail(), content));
    }

    /**
     * 트랜잭션 안이면 묶음에 쌓고 커밋 뒤 한 번에 보낸다(디렉터리 조회 1회). 트랜잭션 밖이면 바로 보낸다 —
     * 그때는 롤백으로 되돌아갈 저장도, 묶을 형제 알림도 없다.
     */
    private void enqueue(Pending pending) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            dispatch(List.of(pending));
            return;
        }
        @SuppressWarnings("unchecked")
        List<Pending> batch = (List<Pending>) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (batch == null) {
            List<Pending> created = new ArrayList<>();
            TransactionSynchronizationManager.bindResource(PENDING_KEY, created);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { dispatch(List.copyOf(created)); }
                // 롤백이든 커밋이든 자리를 비운다 — 안 비우면 같은 스레드의 다음 요청에 섞인다
                @Override public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                }
            });
            batch = created;
        }
        batch.add(pending);
    }

    /**
     * 메일 스레드에서 주소를 정하고 허브에 넘긴다. 여기서만 org-service를 부른다 — 트랜잭션은 이미 끝났고,
     * 수신자가 여럿이어도 디렉터리 왕복은 한 번이다.
     */
    private void dispatch(List<Pending> batch) {
        if (batch.isEmpty()) return;
        executor.execute(() -> {
            // 사용 여부는 여기서 배치당 한 번만 본다 — 트랜잭션은 이미 끝났고, 꺼져 있으면 주소 조회까지
            // 통째로 건너뛴다(관리자가 끈 것은 정상 상태라 debug로만 남긴다).
            if (!mail.enabled()) {
                log.debug("플랫폼 메일이 꺼져 있어 알림 메일 {}통을 보내지 않는다", batch.size());
                return;
            }
            Set<Long> ids = new LinkedHashSet<>();
            for (Pending pending : batch) ids.add(pending.userId());
            Map<Long, DirectoryMember> found = directory.getObject().members(ids);
            for (Pending pending : batch) {
                Optional<String> to = recipient(pending, found.get(pending.userId()));
                if (to.isEmpty()) continue;
                send(to.get(), pending.content());
            }
        });
    }

    /**
     * 보낼 주소 — 정본은 org-service 디렉터리다(2026-09-05). 개인 설정에 남은 주소는 로그인 때 찍힌
     * 스냅샷이라 org에서 이메일을 바꾸면 낡는다.
     *
     * <p>디렉터리가 답을 못 주면(org 불능 등) 스냅샷으로 폴백하고, 그것도 없으면 보내지 않는다 —
     * 주소를 모르는 것은 조용히 넘어갈 일이지 이슈 저장을 막을 일이 아니다. 다만 디렉터리가
     * "이 계정은 막혀 있다"고 답하면 폴백하지 않는다({@link DirectoryMember#blockedFromMail()}):
     * 떠난 사람에게 계속 보내지 않고, 정지된 사람에게는 열지도 못하는 이슈의 제목을 흘리지 않는다.
     */
    private Optional<String> recipient(Pending pending, DirectoryMember member) {
        if (member != null) {
            if (member.blockedFromMail()) {
                log.debug("메일을 받지 않는 계정이라 알림 메일을 보내지 않는다: user={} status={}",
                        pending.userId(), member.status());
                return Optional.empty();
            }
            if (member.hasEmail()) return Optional.of(member.email());
        }
        String snapshot = pending.snapshotEmail();
        if (snapshot == null || snapshot.isBlank()) {
            log.warn("보낼 주소를 몰라 알림 메일을 생략한다: user={}", pending.userId());
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }

    /**
     * 허브에 한 통을 넘긴다. "관리자가 메일을 꺼 뒀다"와 "허브가 답하지 않았다"를 가른다 — 앞은 정상
     * 상태라 조용히 지나가고, 뒤만 사람이 볼 자리에 남긴다. 어느 쪽도 예외로 올리지 않는다.
     */
    private void send(String to, MailContent content) {
        OrgMailClient.SendResult result = mail.send(List.of(to), content.subject(), content.text());
        if (result.failed()) {
            log.warn("알림 메일 발송 실패: to={} subject={}", to, content.subject());
        } else if (result == OrgMailClient.SendResult.DISABLED) {
            log.debug("플랫폼 메일이 꺼져 있어 알림 메일이 나가지 않았다: to={}", to);
        }
    }

    /** 수신 주소를 빼고 만든다 — 주소는 커밋 뒤 디렉터리를 읽어 {@link #dispatch} 가 채운다 */
    MailContent compose(Notification.Type type, Issue issue, String actor) {
        return compose(type, issue, actor, null);
    }

    MailContent compose(Notification.Type type, Issue issue, String actor, String previousStatusId) {
        String label = issue.getKey() + " " + issue.getTitle();
        String subject = switch (type) {
            case ASSIGNED -> actor + "님이 '" + label + "' 이슈를 나에게 배정했습니다";
            case STATUS_CHANGED -> "'" + label + "' 이슈의 상태가 바뀌었습니다";
            case COMMENTED -> "'" + label + "' 이슈에 새 코멘트가 달렸습니다";
            case MENTIONED -> actor + "님이 '" + label + "'에서 나를 멘션했습니다";
        };
        StringBuilder body = new StringBuilder();
        body.append(subject).append("\n\n");
        if (type == Notification.Type.STATUS_CHANGED) {
            String line = statusLine(previousStatusId, issue.getStatus());
            if (!line.isEmpty()) body.append(line).append(BLANK_LINE);
        }
        body.append("이슈 열기: ").append(issueLink(issue)).append("\n\n");
        body.append("이 메일은 ALM 개인 설정의 이메일 알림에 따라 보내졌습니다. 받지 않으려면: ")
                .append(publicUrl).append("/settings/notifications\n");

        String prefix = TYPE_EMOJI.getOrDefault(type, "");
        return new MailContent(
                "[ALM] " + (prefix.isEmpty() ? "" : prefix + " ") + subject,
                body.toString());
    }

    /**
     * "상태: 할 일 -> (이모지) 완료". 상태 이름과 의미는 레지스트리에서 읽고, 모르면 그 부분만
     * 생략한다 — 메일 한 줄 때문에 발송 자체가 막히면 안 된다.
     */
    private String statusLine(String previousStatusId, String currentStatusId) {
        SchemeQueries registry = schemes.getIfAvailable();
        if (registry == null) return "";
        // 상태마다 한 번만 읽는다 — 이름과 의미를 따로 부르면 같은 행을 두 번 조회한다
        SchemeQueries.StatusLabel current = registry.statusLabel(currentStatusId).orElse(null);
        if (current == null) return "";
        String emoji = current.kind() == null ? "" : KIND_EMOJI.getOrDefault(current.kind(), "");
        String prefix = emoji.isEmpty() ? "" : emoji + " ";
        String from = registry.statusLabel(previousStatusId)
                .map(SchemeQueries.StatusLabel::name)
                .orElse("");
        return from.isEmpty()
                ? "상태: " + prefix + current.name()
                : "상태: " + from + " " + ARROW + " " + prefix + current.name();
    }

    private String issueLink(Issue issue) {
        return publicUrl + "/projects/" + issue.getProjectId() + "/issues?issue=" + issue.getKey();
    }

    /** 행위자 이름은 토큰의 name 클레임에서 — ALM은 사용자 디렉터리를 두지 않고 id만 남긴다 */
    private static String actorName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String name = jwt.getClaimAsString("name");
            if (name != null && !name.isBlank()) return name.trim();
        }
        return "누군가";
    }
}
