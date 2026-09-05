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
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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
 * MinIO나 OpenSearch처럼 **선택 옵션**이다: {@code ALM_MAIL_HOST}가 비면 발송기가 없고 알림함만 남는다.
 * 개인 설정 응답의 {@code mailConfigured}가 그 사실을 먼저 알린다 — 스위치를 켰는데 아무것도 오지 않는
 * 것이 최악의 경험이다.
 *
 * 발송은 **커밋 뒤, 다른 스레드**에서 한다. 저장 트랜잭션 안에서 SMTP를 기다리면 이슈를 고친 사람의
 * 저장이 메일 서버 속도에 묶이고, 롤백된 저장의 메일이 먼저 나가 버린다. 실패는 warn 로그로만 남긴다 —
 * 메일은 알림함의 사본이지 원본이 아니다.
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

    private final ObjectProvider<JavaMailSender> senders;
    private final ObjectProvider<PreferenceService> preferences;
    private final ObjectProvider<MemberDirectory> directory;
    private final ObjectProvider<SchemeQueries> schemes;
    private final String host;
    private final String from;
    private final String publicUrl;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "alm-mail");
        t.setDaemon(true);
        return t;
    });

    public EmailNotifier(ObjectProvider<JavaMailSender> senders,
                         ObjectProvider<PreferenceService> preferences,
                         ObjectProvider<MemberDirectory> directory,
                         ObjectProvider<SchemeQueries> schemes,
                         @Value("${spring.mail.host:}") String host,
                         @Value("${platform.alm.mail.from:alm@localhost}") String from,
                         @Value("${platform.alm.mail.public-url:http://localhost/alm}") String publicUrl) {
        this.senders = senders;
        this.preferences = preferences;
        this.directory = directory;
        this.schemes = schemes;
        this.host = host == null ? "" : host.trim();
        this.from = from;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /** host가 비어 있어도 Boot는 빈 host의 발송기를 만들어 두므로 빈 존재만으로 판단하지 않는다. */
    public boolean configured() {
        return !host.isEmpty() && senders.getIfAvailable() != null;
    }

    /** 커밋 뒤에 보낼 한 통 — 주소는 아직 모른다(디렉터리 조회가 커밋 뒤에 일어난다) */
    private record Pending(long userId, String snapshotEmail, SimpleMailMessage message) {}

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
        if (!configured()) return;
        // 스위치와 스냅샷 주소는 지금 읽는다 — 이미 열려 있는 트랜잭션의 DB 조회이고, 메일 스레드에서
        // 다시 커넥션을 잡을 이유가 없다. 밖으로 미루는 것은 남의 서비스를 부르는 일뿐이다.
        PreferenceService.MailTarget target = preferences.getObject().mailTarget(saved.getUserId());
        if (!target.enabled()) return;
        // 본문도 여기서 만든다: 상태 이름을 읽는 SchemeQueries와 Issue 엔티티가 이 트랜잭션 것이다.
        SimpleMailMessage message = compose(saved.getType(), issue, actorName(), previousStatusId);
        enqueue(new Pending(saved.getUserId(), target.snapshotEmail(), message));
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
     * 메일 스레드에서 주소를 정하고 보낸다. 여기서만 org-service를 부른다 — 트랜잭션은 이미 끝났고,
     * 수신자가 여럿이어도 왕복은 한 번이다.
     */
    private void dispatch(List<Pending> batch) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null || batch.isEmpty()) return;
        executor.execute(() -> {
            Set<Long> ids = new LinkedHashSet<>();
            for (Pending pending : batch) ids.add(pending.userId());
            Map<Long, DirectoryMember> found = directory.getObject().members(ids);
            for (Pending pending : batch) {
                Optional<String> to = recipient(pending, found.get(pending.userId()));
                if (to.isEmpty()) continue;
                pending.message().setTo(to.get());
                send(sender, pending.message());
            }
        });
    }

    /**
     * 보낼 주소 — 정본은 org-service 디렉터리다(2026-09-05). 개인 설정에 남은 주소는 로그인 때 찍힌
     * 스냅샷이라 org에서 이메일을 바꾸면 낡는다.
     *
     * <p>디렉터리가 답을 못 주면(org 불능 등) 스냅샷으로 폴백하고, 그것도 없으면 보내지 않는다 —
     * 주소를 모르는 것은 조용히 넘어갈 일이지 이슈 저장을 막을 일이 아니다. 다만 디렉터리가
     * "이 계정은 비활성됐다"고 답하면 폴백하지 않는다: 떠난 사람에게 계속 보내지 않는다.
     */
    private Optional<String> recipient(Pending pending, DirectoryMember member) {
        if (member != null) {
            if (member.deactivated()) {
                log.debug("비활성 계정이라 알림 메일을 보내지 않는다: user={}", pending.userId());
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

    private void send(JavaMailSender sender, SimpleMailMessage message) {
        try {
            sender.send(message);
        } catch (Exception e) {
            String to = message.getTo() == null ? "" : String.join(",", message.getTo());
            log.warn("알림 메일 발송 실패: to={} subject={}", to, message.getSubject(), e);
        }
    }

    /** 수신 주소를 빼고 만든다 — 주소는 커밋 뒤 디렉터리를 읽어 {@link #dispatch} 가 채운다 */
    SimpleMailMessage compose(Notification.Type type, Issue issue, String actor) {
        return compose(type, issue, actor, null);
    }

    SimpleMailMessage compose(Notification.Type type, Issue issue, String actor, String previousStatusId) {
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

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        String prefix = TYPE_EMOJI.getOrDefault(type, "");
        message.setSubject("[ALM] " + (prefix.isEmpty() ? "" : prefix + " ") + subject);
        message.setText(body.toString());
        return message;
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
