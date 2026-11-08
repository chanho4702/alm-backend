package com.platform.almbackend.issue;

import com.platform.common.error.BadRequestException;
import com.platform.common.error.ConflictException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

/**
 * 이슈 생성 {@code Idempotency-Key} — "생성 버튼 두 번 → 이슈 2개"(N10)의 서버 쪽 재발 방지.
 *
 * <p>키 {@code alm:idempotency:{userId}:{key}}. 흐름: {@code SET NX} 로 "pending" 예약 → 생성 → 값을 이슈 id로 바꿔 24시간 보관 →
 * 같은 사용자·같은 키로 다시 오면 처음 이슈를 그대로 돌려준다. 아직 "pending"이면(동시 연타) 409.
 * 생성이 실패하면 예약을 지워 같은 키로 다시 보낼 수 있게 한다.
 *
 * <ul>
 *   <li>사용자마다 키 공간이 따로다 — 남의 키를 맞혀 남의 이슈를 받아 갈 수 없다(재응답도 읽기 권한을 다시 본다).</li>
 *   <li>키는 같은데 본문이 다르면 그래도 처음 이슈를 돌려준다(Stripe식). 본문 지문 대조는 복잡도 대비 가치가 낮아 하지 않는다.</li>
 *   <li>"pending" 예약은 짧게(60초) 둔다 — 생성 도중 프로세스가 죽어도 그 키가 24시간 409로 막히지 않게. 완료되면 24시간으로 늘린다.</li>
 *   <li><b>Redis 불능이면 멱등 없이 그대로 생성한다</b>(WARN 한 줄). 이슈 생성의 가용성을 Redis에 묶지 않는다.</li>
 * </ul>
 */
@Slf4j
@Component
public class IssueCreateIdempotency {
    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotency-Replayed";

    static final String PENDING = "pending";
    static final Duration PENDING_TTL = Duration.ofSeconds(60);
    static final Duration DONE_TTL = Duration.ofHours(24);
    private static final Pattern KEY = Pattern.compile("[\\x21-\\x7E]{1,128}");

    private final StringRedisTemplate redis;

    public IssueCreateIdempotency(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 생성 결과와 재응답 여부 — 재응답이면 컨트롤러가 200 + {@value #REPLAYED_HEADER} 헤더로 돌려준다. */
    public record Result<T>(T value, boolean replayed) {}

    /**
     * @param key     요청 헤더 값. {@code null}이면 멱등 없이 그냥 만든다(헤더 없는 예전 동작)
     * @param creator 실제 생성 — 자기 트랜잭션 안에서 커밋까지 끝내야 한다(이 메서드는 트랜잭션 밖에서 부른다)
     * @param idOf    생성 결과에서 보관할 id
     * @param replay  보관된 id로 처음 결과를 다시 읽는다(권한 확인 포함)
     */
    public <T> Result<T> run(long userId, String key, Supplier<T> creator, ToLongFunction<T> idOf, LongFunction<T> replay) {
        if (key == null) return new Result<>(creator.get(), false);
        if (!KEY.matcher(key).matches()) {
            throw new BadRequestException("Idempotency-Key는 1~128자 ASCII(공백 제외)여야 합니다");
        }
        String redisKey = "alm:idempotency:" + userId + ":" + key;

        Boolean reserved;
        try {
            reserved = redis.opsForValue().setIfAbsent(redisKey, PENDING, PENDING_TTL);
        } catch (RuntimeException e) {
            log.warn("Idempotency-Key 예약 실패 — Redis 불능, 멱등 없이 이슈를 만든다: {}", e.toString());
            return new Result<>(creator.get(), false);
        }

        if (!Boolean.TRUE.equals(reserved)) {
            String stored = read(redisKey);
            if (stored == null) {
                // 사이에 만료·삭제됐거나 Redis가 막 죽었다 — 멱등 없이 진행
                return new Result<>(creator.get(), false);
            }
            if (PENDING.equals(stored)) throw new ConflictException("같은 요청이 처리 중입니다");
            try {
                return new Result<>(replay.apply(Long.parseLong(stored)), true);
            } catch (NumberFormatException e) {
                log.warn("Idempotency-Key 값이 이슈 id가 아님({}) — 멱등 없이 이슈를 만든다", stored);
                return new Result<>(creator.get(), false);
            }
        }

        T created;
        try {
            created = creator.get();
        } catch (RuntimeException | Error e) {
            release(redisKey);
            throw e;
        }
        try {
            redis.opsForValue().set(redisKey, String.valueOf(idOf.applyAsLong(created)), DONE_TTL);
        } catch (RuntimeException e) {
            // 이슈는 이미 커밋됐다. 완료 기록만 놓쳤으니 예약을 지워 60초 동안 409로 막지 않는다(재시도는 중복이 될 수 있음)
            log.warn("Idempotency-Key 완료 기록 실패 — Redis 불능: {}", e.toString());
            release(redisKey);
        }
        return new Result<>(created, false);
    }

    private String read(String redisKey) {
        try {
            return redis.opsForValue().get(redisKey);
        } catch (RuntimeException e) {
            log.warn("Idempotency-Key 조회 실패 — Redis 불능, 멱등 없이 이슈를 만든다: {}", e.toString());
            return null;
        }
    }

    private void release(String redisKey) {
        try {
            redis.delete(redisKey);
        } catch (RuntimeException e) {
            log.warn("Idempotency-Key 예약 삭제 실패 — {} 뒤 만료된다: {}", PENDING_TTL, e.toString());
        }
    }
}
