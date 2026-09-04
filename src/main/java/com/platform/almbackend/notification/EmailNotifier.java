package com.platform.almbackend.notification;

import com.platform.almbackend.domain.Issue;
import com.platform.almbackend.domain.Notification;
import com.platform.almbackend.personal.PreferenceService;
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

import java.util.Optional;
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
 */
@Component
@Slf4j
public class EmailNotifier {

    private final ObjectProvider<JavaMailSender> senders;
    private final ObjectProvider<PreferenceService> preferences;
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
                         @Value("${spring.mail.host:}") String host,
                         @Value("${platform.alm.mail.from:alm@localhost}") String from,
                         @Value("${platform.alm.mail.public-url:http://localhost/alm}") String publicUrl) {
        this.senders = senders;
        this.preferences = preferences;
        this.host = host == null ? "" : host.trim();
        this.from = from;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /** host가 비어 있어도 Boot는 빈 host의 발송기를 만들어 두므로 빈 존재만으로 판단하지 않는다. */
    public boolean configured() {
        return !host.isEmpty() && senders.getIfAvailable() != null;
    }

    /**
     * 알림함에 새 행이 생긴 직후 호출한다. 인앱 알림을 보낼지(개인 설정의 종류별 on/off)는 이미
     * {@link NotificationService}가 판단했다 — 여기서는 이메일 채널 스위치와 주소만 본다.
     */
    public void notify(Notification saved, Issue issue) {
        if (!configured()) return;
        Optional<String> to = preferences.getObject().emailRecipient(saved.getUserId());
        if (to.isEmpty()) return;
        sendAfterCommit(compose(to.get(), saved.getType(), issue, actorName()));
    }

    /** 커밋 뒤 별도 스레드로 보낸다. 트랜잭션 밖이면 바로. */
    public void sendAfterCommit(SimpleMailMessage message) {
        JavaMailSender sender = senders.getIfAvailable();
        if (sender == null) return;
        Runnable send = () -> executor.execute(() -> {
            try {
                sender.send(message);
            } catch (Exception e) {
                String to = message.getTo() == null ? "" : String.join(",", message.getTo());
                log.warn("알림 메일 발송 실패: to={} subject={}", to, message.getSubject(), e);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { send.run(); }
            });
        } else {
            send.run();
        }
    }

    SimpleMailMessage compose(String to, Notification.Type type, Issue issue, String actor) {
        String label = issue.getKey() + " " + issue.getTitle();
        String subject = switch (type) {
            case ASSIGNED -> actor + "님이 '" + label + "' 이슈를 나에게 배정했습니다";
            case STATUS_CHANGED -> "'" + label + "' 이슈의 상태가 바뀌었습니다";
            case COMMENTED -> "'" + label + "' 이슈에 새 코멘트가 달렸습니다";
            case MENTIONED -> actor + "님이 '" + label + "'에서 나를 멘션했습니다";
        };
        StringBuilder body = new StringBuilder();
        body.append(subject).append("\n\n");
        body.append("이슈 열기: ").append(issueLink(issue)).append("\n\n");
        body.append("이 메일은 ALM 개인 설정의 이메일 알림에 따라 보내졌습니다. 받지 않으려면: ")
                .append(publicUrl).append("/settings/notifications\n");

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("[ALM] " + subject);
        message.setText(body.toString());
        return message;
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
