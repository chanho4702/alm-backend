# alm-backend

[![CI](https://github.com/chanho4702/alm-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/chanho4702/alm-backend/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-24-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-almdb-4169E1?logo=postgresql&logoColor=white)
![Redis Streams](https://img.shields.io/badge/Redis-Streams-DC382D?logo=redis&logoColor=white)

MSA_TEMPLATE의 **ALM 프로젝트·이슈 정본 서비스**다. PostgreSQL에 데이터를 저장하고,
org-service의 grant로 요청을 인가한다(프로젝트 권한과 전역 관리자 모두 — Keycloak 역할이 아니다). 변경 이벤트는 Redis Streams에 발행하며,
search-service는 내부 gRPC로 이슈 원문을 가져가 `alm-issue` 인덱스를 만든다.

전체 플랫폼 구성은 [infra-settings](https://github.com/chanho4702/infra-settings), 컨테이너
배포는 [infra README](https://github.com/chanho4702/infra-settings/blob/main/infra/README.md)를 참고한다.

지라 코어 대비 기능 갭 분석과 이 서비스에 필요한 계약 확장(스프린트 목표·기간, 완료 시 이관
대상, 상태 변경 이력·리포트 집계)은 alm-front `docs/roadmap/2026-08-28-jira-parity-requirements.md`에
정리돼 있다.

## 한눈에 보기

| 항목 | 내용 |
|---|---|
| 런타임 | Java 24 · Spring Boot 4.0.6 · Gradle |
| REST | `:9120` / dev `:19120` · `/api/alm/**` |
| 내부 gRPC | `:9121` / dev `:19121` · `AlmContentService` |
| 데이터 | PostgreSQL `almdb` · Flyway · 첨부 바이트는 MinIO(S3 호환, `ALM_S3_*`) — 끄면 로컬 파일 |
| 인증·인가 | auth-server RS256 JWT 검증 + org-service grant(`PROJECT` · 전역은 `GLOBAL`/`ADMIN`) |
| 이벤트 | Redis Streams `platform:events:v1` |

## 빠른 시작

JDK 24와 `common-proto`·`common-starter` 패키지(같은 태그 버전, 기본값은 `build.gradle`의
`commonProtoVersion` = `0.17.0`)가 필요하다. 발행본을 받으려면 `GITHUB_TOKEN`에 GitHub Packages
`read:packages` 권한을 설정한다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
$env:GITHUB_TOKEN = (gh auth token)

.\gradlew.bat test
.\gradlew.bat bootJar
.\gradlew.bat bootRun   # http://localhost:9120
```

실행에는 PostgreSQL `almdb`, Redis, auth-server JWKS, org-service gRPC가 필요하다.
백킹 서비스는 루트에서 다음과 같이 올릴 수 있다.

```powershell
docker compose -f ..\infra\keycloak\docker-compose.yml up -d postgres redis keycloak auth-server org-service
```

proto 변경을 발행 전에 먼저 검증할 때만 Maven Local을 사용한다(공유 아티팩트 버전은 하나다 — 브랜치 전용
proto를 만들지 않고, 변경은 platform-backend main에 올려 태그로 발행한다).

```powershell
cd ..\platform-backend
..gradlew.bat :common-proto:publishToMavenLocal :common-starter:publishToMavenLocal '-PprotoVersion=0.18.0-SNAPSHOT'

cd ..\alm-backend
.\gradlew.bat test '-PuseMavenLocal' '-PcommonProtoVersion=0.18.0-SNAPSHOT'
```

dev 오프셋 프로필은 `--args='--spring.profiles.active=dev'`를 붙인다. REST와 gRPC가 각각
`:19120`, `:19121`로 이동하고 Redis DB 1, dev auth/org 포트를 사용한다.

## API

모든 REST 엔드포인트는 Bearer JWT가 필요하며, 외부에서는 gateway-server를 통해 접근한다.

| 메서드 | 경로 | 필요 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/alm/projects` | 접근 가능한 프로젝트 | 프로젝트 목록 |
| `POST` | `/api/alm/projects` | 인증 | 프로젝트 생성, 생성자에게 ADMIN grant 부여 시도 |
| `GET` | `/api/alm/projects/{projectId}` | VIEW | 프로젝트 조회 |
| `PUT` | `/api/alm/projects/{projectId}` | ADMIN | 프로젝트 수정 |
| `DELETE` | `/api/alm/projects/{projectId}` | ADMIN | 휴지통으로 이동 — 그 안의 이슈는 조회·검색·AQL·쓰기 어디서도 닿지 않는다(404/결과 제외) |
| `GET` | `/api/alm/projects/statuses` | 접근 가능한 프로젝트 | 프로젝트별 해석된 상태 목록을 한 번에(`{ "<projectId>": [status…] }`, order 순) |
| `GET` | `/api/alm/projects/{projectId}/issues` | VIEW | 이슈 목록 |
| `POST` | `/api/alm/projects/{projectId}/issues` | EDIT | 이슈 생성 |
| `GET` | `/api/alm/issues/{issueId}` | VIEW | 이슈 조회 |
| `PUT` | `/api/alm/issues/{issueId}` | EDIT | 이슈 수정 — `details.resolution`(V6), `details.fixVersionId`(같은 프로젝트·보관 아님; V7). **전체 교체**다 — `status`·`sprintId`는 보드 이동·랭크와 version을 공유하지 않으므로 오래된 스냅샷으로 PUT하면 그 사이의 이동을 되돌릴 수 있다 |
| `POST` | `/api/alm/issues/{issueId}/clone` | 원본 VIEW + EDIT | 복제(201) — 제목 "복제: " 접두·타입·본문·우선순위·라벨·컴포넌트·상위만 복사, 처음 상태·스프린트 없음, 원본과 `clones` 링크 |
| `DELETE` | `/api/alm/sprints/{sprintId}` | ADMIN | 예정 스프린트 삭제(204) — 이슈는 원래 순서로 백로그 맨 위. 진행 중·완료는 409 |
| `GET` | `/api/alm/filters/shared` | 인증 | 남이 공유한 저장 필터(조직 전체, V28). 결과는 실행하는 사람 권한으로 걸러진다 |
| `GET` | `/api/alm/filters/{id}` | 인증 | 저장 필터 단건 — 내 것이거나 공유된 것(비공개 남의 것 404). 남의 공유 필터 수정·삭제는 403 |
| `POST` | `/api/alm/issues/bulk` | 행마다 EDIT | 일괄 편집 — 보낸 필드만, 행별 트랜잭션·행별 결과(`CONFLICT`면 `currentVersion`). 최대 200건. 아래 "일괄 편집" 절 |
| `GET` | `/api/alm/issues/search` | 접근 가능한 프로젝트 | 조건 검색·페이징(최대 200). 응답 `truncated`=뒤에 더 있음. 검색어의 `%`·`_`는 글자 그대로 |
| `GET` | `/api/alm/issues/aggregate` | 접근 가능한 프로젝트 | 조건 집계(GROUP BY) — 진행률·통계는 목록 대신 이것으로 센다 |
| `POST` | `/api/alm/issues/query` | 접근 가능한 프로젝트 | **AQL** 검색 — 아래 "AQL" 절 |
| `POST` | `/api/alm/issues/query/validate` | 인증 | AQL 문법 검사(에디터 실시간) |
| `GET` | `/api/alm/issues/query/fields` | 접근 가능한 프로젝트 | AQL 자동완성 사전(필드·별칭·연산자·값 후보) |
| `GET` | `/api/alm/me/filters` | 인증(본인) | 저장 필터 목록(이름 순) — 아래 "저장 필터" 절 |
| `POST` | `/api/alm/me/filters` | 인증(본인) | 저장 필터 생성(이름 중복 409, `kind=aql`이면 문법 검사) |
| `PUT` | `/api/alm/me/filters/{id}` | 인증(본인) | 저장 필터 수정(보낸 항목만) |
| `DELETE` | `/api/alm/me/filters/{id}` | 인증(본인) | 저장 필터 삭제 |
| `POST` | `/api/alm/issues/{issueId}/move` | EDIT | 보드 컬럼 이동·순서 변경 — 전환 규칙(조건·검증·후처리)과 해결 규칙을 탄다. 아래 "워크플로 엔진" 절 |
| `GET` | `/api/alm/issues/{issueId}/transitions` | VIEW | 가용 전환 — 조건까지 평가, 못 하는 것도 이유와 함께(`available`·`unavailableReason`·`screenFields`) |
| `POST` | `/api/alm/issues/{issueId}/transitions` | EDIT | 전환 실행 — 전환 화면 값(해결·담당자·수정 버전·마감일)과 코멘트를 한 요청으로. 조건·검증 거부는 400 |
| `POST` | `/api/alm/issues/{issueId}/rank` | EDIT | 백로그/스프린트 랭크 이동 |
| `DELETE` | `/api/alm/issues/{issueId}` | EDIT | 이슈 삭제 |
| `GET` | `/api/alm/projects/{projectId}/versions` | VIEW | 버전 목록 |
| `POST` | `/api/alm/projects/{projectId}/versions` | EDIT | 버전 생성(이름은 프로젝트 안에서 유일, 중복 409) |
| `PUT` | `/api/alm/versions/{versionId}` | EDIT | 버전 수정(`expectedVersion`, 날짜 역전 400) |
| `POST` | `/api/alm/versions/{versionId}/release` | EDIT | 릴리스 — `moveUnresolvedToVersionId`로 미완료 이관(선택). 완료 판정은 서버 워크플로(complete 분류)이고 `doneStatuses`는 무시한다(deprecated) |
| `POST` | `/api/alm/versions/{versionId}/archive` | EDIT | 보관 |
| `DELETE` | `/api/alm/versions/{versionId}` | EDIT | 삭제(달린 이슈의 `fixVersionId`를 비운다) |
| `POST` | `/api/alm/issues/{issueId}/attachments` | EDIT | 첨부 올리기(multipart `file`, 최대 `ALM_MAX_ATTACHMENT_MB`=20) |
| `GET` | `/api/alm/issues/{issueId}/attachments` | VIEW | 첨부 목록 |
| `GET` | `/api/alm/attachments/{id}` | VIEW | 내려받기(attachment 처분, nosniff) |
| `GET` | `/api/alm/attachments/{id}/inline` | VIEW | 인라인(래스터 이미지만) |
| `DELETE` | `/api/alm/attachments/{id}` | EDIT | 삭제(오브젝트는 커밋 뒤 정리) |
| `GET` | `/api/alm/projects/{projectId}/changes` | VIEW | 변경 이력(리포트 원천) — `field`·`sprintId`·`since`(포함)·`until`(배타)·`limit`(기본·최대 20000). 넘으면 최신 쪽만 남기고 `X-Result-Truncated: true` |
| `GET` | `/api/alm/issues/{issueId}/links` | VIEW | 이슈 링크 — 반대편 프로젝트를 볼 수 없으면 `restricted: true`이고 `other`는 키·타입·상태만(제목 "볼 수 없는 이슈") |
| `POST` | `/api/alm/issues/{issueId}/links` | 원본 EDIT + 대상 VIEW | 이슈 연결 |
| `DELETE` | `/api/alm/comments/{id}`, `/api/alm/worklogs/{id}` | 작성자(지금 VIEW) 또는 프로젝트 ADMIN | 댓글·워크로그 삭제. 댓글 수정은 작성자(지금 VIEW)만 |
| `POST` | `/api/alm/projects/{projectId}/issues/import` | EDIT | CSV 가져오기 — 최대 5000건, 본문 20MB(`platform.alm.import.max-body-size`, 넘으면 413) |
| `PUT` | `/api/alm/issues/{issueId}/flag` | EDIT | 플래그(막힘) 켜기·끄기 — version 불변. 아래 "계획 필드·플래그" 절 |
| `GET` | `/api/alm/projects/{projectId}/estimation/summary`, `/reports/velocity` | VIEW | 추정 합계·속도(포인트·시간·건수) |
| `GET`·`POST`·`PUT`·`DELETE` | `/api/alm/views/**`, `/api/alm/me/views`, `/api/alm/projects/{projectId}/views` | 범위별 | 저장된 보기(V31) — 아래 "저장된 보기" 절 |
| `GET`·`PUT`·`POST` | `/api/alm/projects/{projectId}/triage/**`, `/api/alm/issues/{issueId}/triage/**` | VIEW·EDIT·ADMIN | 트리아지(V35) — 아래 "트리아지" 절 |
| `GET`·`POST`·`PUT`·`DELETE` | `/api/alm/projects/{projectId}/automation/**`, `/api/alm/automation/rules/**` | ADMIN | 자동화 규칙(V36·V37) — 아래 "자동화 규칙" 절 |
| `GET` | `/api/alm/projects/{projectId}/sprints` | VIEW | 스프린트 목록 |
| `POST` | `/api/alm/projects/{projectId}/sprints` | EDIT | 스프린트 생성(`Sprint N` 자동 명명) |
| `GET` | `/api/alm/sprints/{sprintId}` | VIEW | 스프린트 단건 |
| `PUT` | `/api/alm/sprints/{sprintId}` | EDIT | 계획 메타 수정(이름·목표·예정 기간, `expectedVersion`) |
| `POST` | `/api/alm/sprints/{sprintId}/start` | EDIT | 스프린트 시작(프로젝트당 1개) |
| `POST` | `/api/alm/sprints/{sprintId}/complete` | EDIT | 스프린트 완료, 미완료 이슈는 지정 스프린트(`moveUnfinishedToSprintId`) 또는 백로그로 |

### 상태 아이콘 (V20)

상태를 색만으로 구분하면 색각 이상·흑백 인쇄에서 정보가 사라진다. `status_def.icon`이 프론트
아이콘 맵의 lucide 키를 담고, 화면은 아이콘 모양과 상태 이름을 늘 함께 쓴다.

- `GET/POST/PUT /api/alm/settings/statuses`의 `icon`은 **저장된 원본**이다 — 빈 문자열은 "미지정"이라는
  유효한 값이고 레지스트리 편집기가 그대로 보여야 한다. `PUT`에서 `icon`을 아예 보내지 않으면 안 바꾸고,
  빈 문자열을 보내면 미지정으로 되돌린다.
- 워크플로 본문(`GET /api/alm/projects/{id}/settings`, `GET/PUT /api/alm/settings/schemes/{id}`)의
  `body.statuses[].icon`은 **해석된 값**이다 — 미지정이면 카테고리 의미별 기본
  (`new`=`circle`, `active`=`refresh-cw`, `complete`=`circle-check`)으로 폴백해 내려간다.
  `kind`·`color`와 마찬가지로 읽기 전용 파생값이라 저장되지 않는다.
- 기본 3종 시드: `todo`=`circle`, `inprogress`=`loader-circle`, `done`=`circle-check`.

### 아바타 — org-service로 이관 (V21)

프로필 사진은 더 이상 여기 없다. 아바타는 ALM만의 것이 아니라 위키·보드가 함께 보는 값이고
사용자 디렉터리(`GET /api/org/members`)도 org-service에 있어서, 2026-09-05에 정본을
org-service `member_profile`(V7)로 옮겼다. `user_preference`의 `avatar_key`·`avatar_updated_at`은
**V21에서 제거**했고 `GET /api/alm/me/preferences` 응답에도 `avatarUrl`이 없다.

| 옮기기 전 (ALM) | 옮긴 뒤 (org-service) |
|---|---|
| `PUT/DELETE /api/alm/me/avatar` | `PUT/DELETE /api/org/me/avatar` |
| `GET /api/alm/users/{userId}/avatar` | `GET /api/org/members/{memberId}/avatar` |
| `GET /api/alm/users/avatars` | 없음 — `GET /api/org/members`가 항목마다 `avatarUrl`을 싣는다 |
| `GET /api/alm/me/preferences`의 `avatarUrl` | `GET /api/org/me`의 `avatarUrl` |

기존 오브젝트(`alm-attachments` 버킷의 `avatars/...`)는 **옮기지 않았다** — 개발 단계라 재업로드가
싸고, 남은 바이트는 버킷에서 손으로 지운다.

`AttachmentStorage`의 키 지정 저장(`store(..., key)`)·`requireSafeKey`·`defaultBucket()`은 남겨 두었다.
첨부 자체가 쓰지는 않지만 저장소 계약의 일부이고 `AttachmentStorageKeyTest`가 지키고 있다.

프로젝트 키와 이슈 키는 생성 후 바뀌지 않는다. 프로젝트·이슈 수정 요청에는
`expectedVersion`이 필요하며, 현재 버전과 다르면 `409 Conflict`를 반환한다.

이슈는 부모·마감일·예상 시간·라벨·정렬 순서를 저장한다. 생성/수정 요청에서는 확장값을
`details`로 묶는다. `PUT`에서 `details`를 생략하면 V1 클라이언트로 간주해 기존 확장값을
보존하며, 객체를 보내면 nullable 필드를 `null`로 명시적으로 해제할 수 있다.

```json
{
  "title": "로그인 오류",
  "description": "OIDC callback 실패",
  "type": "BUG",
  "status": "todo",
  "priority": "HIGH",
  "assigneeId": 2,
  "details": {
    "parentId": null,
    "dueDate": "2026-08-20",
    "estimateHours": 3.5,
    "labels": ["security", "backend"]
  },
  "expectedVersion": 2
}
```

스프린트는 프로젝트 안에서 `Sprint N`으로 자동 명명하며(이름을 보내면 그 값을 쓴다),
`PLANNED → ACTIVE → DONE`으로만 움직인다. 진행 중인 스프린트는 프로젝트당 하나뿐이고, 이 규칙은
애플리케이션 검사와 함께 부분 unique 인덱스가 최종 판정한다. 완료 시 미완료 이슈는 백로그 맨 뒤로
돌아가며(또는 `moveUnfinishedToSprintId`), **무엇을 완료로 볼지는 서버가 정한다** — 프로젝트 워크플로에서
상태 분류 `kind=complete`인 상태다(V11부터 스킴이 서버에 있다). 요청의 `doneStatuses`는 옛 클라이언트 호환으로
받기만 하고 쓰지 않는다(2026-10-18 감사 A-18 — 낡은 목록이 완료 이슈를 다음 스프린트로 옮겨 번다운을 오염시켰다).

순서는 두 종류의 그룹으로 관리한다 — 보드 컬럼(프로젝트+스프린트+상태)과 랭크 그룹
(프로젝트+스프린트, 상태 무관)이다. `move`는 컬럼 안에서, `rank`는 랭크 그룹 안에서 `beforeId`
앞에 놓고 그룹 전체를 1..n으로 다시 매긴다. 떠난 그룹도 함께 조밀해진다. `beforeId`가 대상 그룹에
없으면 오류가 아니라 맨 뒤다 — 드래그 도중 다른 사용자가 그 이슈를 옮겼을 수 있고, 화면은 이동 후
항상 재조회한다. 두 연산 모두 프로젝트 행을 잠그고 한 트랜잭션에서 끝내며, 순서는 사용자가 편집
폼에서 보고 있던 값이 아니므로 `version`을 올리지 않는다(드래그가 남의 저장을 409로 만들지 않는다).

계층 깊이는 제한하지 않는다(확정 결정 — 하위의 하위 허용). 부모는 같은 프로젝트에 있어야 하고, 자기 자신이나
자기 자손을 부모로 지정하면 400("상위 항목이 순환합니다" — 재귀 CTE 한 번으로 검사)이다. 부모 삭제 시 자식의
`parentId`는 해제된다. `order`는 응답에만
포함되는 서버 관리 값이며 생성 시 프로젝트 내 다음 번호로 발급한다. 재정렬은 별도 API로
제공하기 전까지 일반 수정 요청으로 바꿀 수 없다.

## 일괄 편집 — `POST /api/alm/issues/bulk`

이슈 N개(최대 200)에 같은 필드 변경을 한 요청으로 적용한다. 행마다 자기 트랜잭션에서 단건 `PUT`과 같은 경로
(권한·보관 가드·전이 규칙·스프린트/버전 검증·변경 이력·알림·이벤트)를 타므로 일부만 성공할 수 있고, 응답은 항상
200 + 행별 결과다(요청 자체가 잘못되면 400: 빈 `items`, 중복 이슈, 바꿀 내용 없음).

```json
{
  "items": [{ "issueId": 101, "expectedVersion": 3 }, { "issueId": 102 }],
  "changes": { "status": "inprogress", "assigneeId": null, "addLabels": ["urgent"], "removeLabels": ["old"] }
}
```

- `changes`는 **키를 보낸 필드만** 바꾼다: `status`·`priority`(null이면 그대로), `assigneeId`·`sprintId`·`fixVersionId`
  (null이면 해제 — 미지정·백로그·없음), `addLabels`·`removeLabels`(뺀 뒤 더한다).
- `expectedVersion`이 서버 버전과 다르면 그 행만 `CONFLICT`. 생략하면 행을 처리하는 순간 읽은 버전으로 저장한다
  (읽은 뒤 잠그기 전에 누가 고쳤으면 역시 `CONFLICT` — 덮어쓰지 않는다).

```json
{
  "updated": 1, "failed": 1,
  "results": [
    { "issueId": 101, "key": "ALM-7", "ok": true, "code": "OK", "error": null, "currentVersion": null, "issue": { "...": "IssueResponse" } },
    { "issueId": 102, "key": "ALM-8", "ok": false, "code": "CONFLICT", "error": "다른 사용자가 먼저 이슈를 수정했습니다. 현재 5, 요청 4", "currentVersion": 5, "issue": null }
  ]
}
```

`code`: `OK`·`CONFLICT`·`FORBIDDEN`·`NOT_FOUND`·`INVALID`(규칙 위반·없는 상태 등)·`UNAVAILABLE`(권한 서비스 불능)·`ERROR`.
볼 수 없는 프로젝트의 이슈·없는 이슈는 `key`가 null이다.

## 워크플로 엔진 — 해결 규칙·전환·조건·검증·후처리 (2026-10-24)

상태가 바뀌는 **모든 경로**(`PUT /issues/{id}`, `POST /issues/{id}/move`, `POST /issues/bulk`, `POST /issues/{id}/transitions`,
스킴 변경에 따른 이관)가 같은 규칙을 탄다 — `workflow/WorkflowEngine`(전환 고르기·조건·검증·후처리)과
`issue/ResolutionRule`(해결). 규칙은 스킴/커스텀 본문(JSON) 안에 있어 **마이그레이션이 없다**(V29 백필만).

### 해결(resolution) 규칙 — 서버가 정본

| 상황 | 결과 |
|---|---|
| 완료 의미(kind=complete) 상태로 **들어감** | 해결 = 기본값(`DONE`, 전환 후처리 `setResolution`이 있으면 그 값), 해결일 = 지금 |
| 완료에서 **나감** | 해결·해결일 비움 |
| 완료 안에서 해결을 다른 값으로 지정 | 그 값. 해결일은 처음 해결한 시각 그대로 |
| 완료 안에서 해결을 `null`로 지정 | 기본값(`DONE`) — 완료 이슈는 해결이 비지 않는다 |
| 완료가 아닌 상태에 해결을 지정 | **400** `완료된 이슈에만 해결을 설정할 수 있습니다` |
| 완료 상태로 **생성** | 해결 = `DONE` |

"지정"의 판정: `PUT`은 화면이 들고 있던 값을 그대로 다시 보내므로(REST 어댑터가 현재 값을 실어 보낸다)
**`details.resolution`이 서버의 현재 값과 다를 때만** 지정으로 본다. 그래서 완료 → 할 일로 되돌리며 옛 `DONE`을
그대로 실어 보내도 거부하지 않고 비운다. 보드 이동·일괄 편집은 해결을 지정하지 않는다. 프론트 목업
`applyResolutionRule`(`mock/rules.ts`)과 같은 규칙이고, REST 어댑터 주석("기본값·해제 규칙은 프론트 몫",
`store/api/issues.ts`)은 이제 틀리다 — 프론트는 값을 보내기만 하면 된다.

### V29 해결 백필

`V29__resolution_backfill.sql` — 완료 의미 상태(`status_def → status_category.kind = 'complete'`, 레지스트리에 없는
옛 id는 `done`만)인데 해결이 빈 이슈(보관 포함)를 `DONE`으로 채운다. 해결일은 **현재 상태로 들어온 마지막 상태
변경 시각**(`issue_change_log`, `field='STATUS' AND to_value = 현재 상태`), 이력이 없으면 `updated_at`. `updated_at`은
건드리지 않는다. 반대 방향(완료가 아닌데 해결이 남은 이슈)은 바꾸지 않는다 — 그런 이슈는 다음 상태 변경 때 규칙이 비운다.

운영 반영 전 영향 건수(같은 술어):

```sql
SELECT count(*) FROM issue i
WHERE i.resolution IS NULL
  AND (EXISTS (SELECT 1 FROM status_def d JOIN status_category c ON c.id = d.category_id
               WHERE d.id = i.status AND c.kind = 'complete')
       OR (i.status = 'done' AND NOT EXISTS (SELECT 1 FROM status_def d WHERE d.id = i.status)));
```

### 가용 전환 — `GET /api/alm/issues/{issueId}/transitions` (VIEW)

지금 상태에서 갈 수 있는 전환. 조건 때문에 못 하는 전환도 **숨기지 않고** `available: false` + 이유를 준다.
편집 권한이 없거나 보관된 프로젝트면 전부 `available: false`(이유는 403 문구). 자기 자신으로 가는 전환은 뺀다.
전환 목록이 빈 워크플로(= 모든 상태 ↔ 모든 상태, 지라 기본 워크플로와 같은 뜻)는 다른 모든 상태로 가는 암묵 전환
(`id = "implicit:<상태 id>"`, `isGlobal: true`)을 준다. 지라 `getTransitions`의 `isGlobal`·`isAvailable`·`hasScreen`과 같은 뜻.

```json
{
  "issueId": 1024, "status": "inprogress", "version": 3, "typeWorkflow": false,
  "transitions": [
    { "id": "t-done", "name": "끝내기", "to": "done", "toName": "완료", "toKind": "complete", "toIcon": "check-circle",
      "isGlobal": false, "available": false, "unavailableReason": "담당자만 이 전환을 할 수 있습니다",
      "hasScreen": true, "screenFields": ["resolution", "comment", "dueDate"],
      "requiredFields": ["dueDate"], "missingFields": ["dueDate"] },
    { "id": "t-reset", "name": "처음으로", "to": "todo", "toName": "할 일", "toKind": "new", "toIcon": "circle",
      "isGlobal": true, "available": true, "unavailableReason": null,
      "hasScreen": false, "screenFields": [], "requiredFields": [], "missingFields": [] }
  ]
}
```

- `name`: 전환 이름, 비어 있으면 대상 상태 이름. `requiredFields`: 전환 검증 필드, `missingFields`: 그중 지금 빈 것
  (화면 칸으로 채우지 않으면 실행이 400). `typeWorkflow`: 이슈 타입별 워크플로가 적용됐는가.

### 전환 실행 — `POST /api/alm/issues/{issueId}/transitions` (EDIT)

```json
{
  "transitionId": "t-done",
  "expectedVersion": 3,
  "fields": { "resolution": "WONT_DO", "dueDate": "2026-11-01" },
  "comment": "범위에서 뺍니다"
}
```

- 응답은 `IssueResponse`(저장 후). 한 트랜잭션: 조건 → 화면 값 반영 → 검증 → 상태 + 해결 규칙 → 후처리 → 코멘트.
- `fields`는 **그 전환의 `screenFields`에 있는 칸만**(키를 보낸 것만 바뀐다): `resolution`(null이면 기본값),
  `assigneeId`·`fixVersionId`·`dueDate`(null이면 해제). 화면에 없는 칸 → 400 `이 전환 화면에 없는 칸입니다: 담당자`,
  화면에 `comment`가 없는데 코멘트 → 400.
- `expectedVersion`은 선택 — 주면 어긋날 때 409. 버전은 편집 폼이 보는 값(담당자·수정 버전·마감일)이 바뀐 때만 오른다
  (상태·해결만 바뀐 전환은 보드 이동처럼 그대로).
- 거부: 없는 전환 400 `없는 전환입니다: x`, 지금 상태에서 못 하는 전환 400 `지금 상태(할 일)에서는 할 수 없는 전환입니다: 끝내기`,
  조건·검증 미충족 400(아래 문구), 프로젝트 권한 없음 403, org 불능 503.
- **A4 결정(권장안 채택)**: 완료로 옮길 때 창은 **전환 화면이 설정된 전환에서만** 띄운다. 화면 없는 전환·보드 이동은 창 없이
  해결 `DONE`이 자동으로 붙는다(지라 기본 `hasScreen: false`와 같다).

### 전환 규칙 — 스킴/커스텀 본문 `transitions[]`

```json
{ "id": "t-done", "name": "끝내기", "from": ["inprogress"], "to": "done",
  "conditions":    [{ "type": "assignee" }, { "type": "role", "role": "ADMIN" }, { "type": "subtasksDone" }],
  "validators":    ["dueDate", "fixVersion"],
  "screenFields":  ["resolution", "comment", "assignee", "fixVersion", "dueDate"],
  "postFunctions": [{ "type": "assignToActor" }, { "type": "setResolution", "value": "WONT_DO" }] }
```

| 항목 | 값 | 미충족·거부 문구(400) |
|---|---|---|
| 조건 `assignee` | 실행자 = 담당자 | `담당자만 이 전환을 할 수 있습니다` |
| 조건 `role` | `ADMIN`·`EDIT` — org-service `PROJECT` 판정(gRPC, 불능이면 503) | `프로젝트 관리자만 이 전환을 할 수 있습니다` |
| 조건 `subtasksDone` | 하위 이슈(보관 제외)가 모두 완료 의미 | `완료되지 않은 하위 이슈가 N개 있습니다` |
| 검증 | `description`·`assignee`·`labels`·`components`·`parent`·`sprint`·`dueDate`·`fixVersion`·`estimate` | `마감일은 필수입니다`(생성 필수 검사와 같은 문구) |
| 화면 칸 | `resolution`(완료로 가는 전환만)·`comment`·`assignee`·`fixVersion`·`dueDate` | — |
| 후처리 | `assignToActor`(담당자 = 실행자)·`clearAssignee`·`setResolution`(완료로 가는 전환만, 기본 해결 값) | — |

- 조건·검증 거부는 **400**이다(403은 권한 없음에만 — 2026-10-24 사용자 결정, 기획 §7-9).
- 전환 id 없이 상태만 오는 경로(PUT·move·bulk)는 같은 대상으로 가는 전환 중 **조건을 충족하는 첫 번째**를 쓰고,
  하나도 못 하면 첫 후보의 이유로 거부한다. 정의되지 않은 이동은 예전 문구 `할 일에서 완료로 옮길 수 없습니다`.
- 저장 검증(스킴/커스텀 저장 400): 대상·출발 상태가 워크플로에 있어야 하고, 위 어휘만, 같은 전환 id 중복 금지,
  `assignToActor`+`clearAssignee` 동시 금지. 해결 해제는 내장 규칙이라 후처리로 두지 않는다.

### 이슈 타입별 워크플로 — 본문 `workflowsByType` (C5)

```json
"workflowsByType": {
  "bug": { "statuses": ["todo", "review", "done"], "transitions": [] }
}
```

- 타입 키가 없으면 프로젝트 기본 워크플로(폴백). 키는 레지스트리 타입 id 그대로(`fieldsByType`과 같은 규칙).
- `statuses`는 프로젝트 `statuses`의 **부분집합**(비면 전부) — 상태 정의는 프로젝트 목록 하나뿐이라 보드 열·완료 판정·
  리포트는 지금처럼 그 목록을 본다. 부분집합도 의미(할 일/진행 중/완료)마다 상태가 하나 이상 있어야 한다.
- `transitions`: `null`(생략)이면 프로젝트 전환 중 이 상태들 사이의 것만, `[]`이면 모두 허용, 목록이면 그것.
- 생성: 기본 상태는 그 타입 워크플로의 첫 '할 일' 상태. 그 워크플로에 없는 상태를 보내면 400
  `이 이슈 타입의 워크플로에 없는 상태입니다: 진행 중`.
- **타입 변경 매핑**: 새 타입 워크플로에 지금 상태가 없을 때 — 화면이 대상 상태를 골라 `status`로 보내면 그 값(전환 규칙은
  적용하지 않는다, 이관이다), 지금 상태를 그대로 보내면 서버가 **같은 카테고리의 첫 상태 → 같은 의미의 첫 상태 → 첫 상태**로
  옮긴다(설정 변경 이관과 같은 규칙). 해결 규칙은 함께 적용된다.
- 스킴/커스텀 저장으로 타입 워크플로가 바뀌면 그 타입의 이슈 중 새 워크플로에 없는 상태의 이슈를 같은 규칙으로 이관한다.

### 보드 열 매핑 — `columns_json` (C4)

`GET/PUT /api/alm/boards/{id}` 응답에 세 필드가 더해졌다(기존 `columns`는 그대로):

```json
{
  "columnMode": "mapped",
  "columns": [{ "id": "col-1a2b3c4d", "status": "inprogress", "name": "진행", "statuses": ["inprogress", "review"], "wipLimit": 3 }],
  "resolvedColumns": [{ "id": "col-1a2b3c4d", "name": "진행", "statuses": ["inprogress", "review"], "wipLimit": 3 },
                      { "id": "col-9f8e7d6c", "name": "끝", "statuses": ["done"], "wipLimit": null }],
  "unmappedStatuses": ["todo"]
}
```

- `columnMode: "status"`(구 형식): 열 = 상태 1:1, `columns`는 예전처럼 상태별 이름·WIP 덮어쓰기만. `resolvedColumns`는 워크플로
  상태 전부를 order 순으로(id = 상태 id), `unmappedStatuses`는 빈 목록.
- `columnMode: "mapped"`: `PUT`의 `columns`에 **`statuses[]`가 하나라도 있으면** 매핑 형식이고 모든 열에 있어야 한다.
  열 하나에 여러 상태, 상태는 한 열에만(`상태 하나는 한 열에만 넣을 수 있습니다: done`), 없는 상태 400. 열 `id`는 비우면 서버가
  붙인다. 어느 열에도 없는 상태는 `unmappedStatuses`이고 **그 상태의 이슈는 `GET /boards/{id}/issues`에 나오지 않는다**.
  워크플로에서 빠진 상태는 읽을 때 열에서 뺀다. 구 형식으로 되돌리려면 `statuses` 없는 열 목록을 보낸다.
- 열에 놓을 때의 대상 상태(기획 C4 "가용 전환 중 첫 번째")는 화면이 고른다 — 열의 `statuses` 중 `GET .../transitions`에서
  `available`인 첫 번째로 `move`를 부른다.

### 도메인 이벤트(서비스 내부)

`event/domain/` — `IssueCreatedEvent`·`IssueUpdatedEvent`(`changedFields`)·`IssueTransitionedEvent`(`fromStatus`·`toStatus`·
`fromKind`·`toKind`·`transitionId`·`actorId`·`at`)를 Spring `ApplicationEventPublisher`로 변경 트랜잭션 **안**에서 낸다.
커밋된 것에만 반응하려면 `@TransactionalEventListener(phase = AFTER_COMMIT)`(DB를 다시 쓰면 `REQUIRES_NEW`). 자동화 규칙
엔진이 여기에 붙는다. 서비스 간 이벤트는 그대로 아웃박스(V26) → Redis Streams다. 스킴 변경 이관은 사용자의 전환이 아니라
발행하지 않는다.

## AQL — 조건을 조합하는 이슈 질의어

JQL의 구조(필드 연산자 값 · AND/OR/NOT · 괄호 · IN · `~` · 상대 날짜 · ORDER BY)를 따르되 **한국어 필드
별칭**을 받는다. 실행은 DB(JPA Criteria)로 하고 OpenSearch를 쓰지 않는다. 파서는 손으로 짠 재귀 하강이며
(`search/aql/`), **프론트 `store/aql/`가 같은 문법·같은 AST를 만든다** — 아래 AST JSON이 그 계약이다.

### 문법

```
query   := clause? ("ORDER BY" order ("," order)*)?
clause  := term (("AND" | "OR") term)*          -- AND가 OR보다 강하게 묶인다
term    := "NOT" term | "(" clause ")" | cond
cond    := field op value
         | field ("IN" | "NOT IN") "(" value ("," value)* ")"
         | field ("IS" | "IS NOT") "EMPTY"
op      := "=" | "!=" | "~" | "!~" | "<" | "<=" | ">" | ">="
value   := string | number | ident | function
order   := field ("ASC" | "DESC")?
```

- 키워드와 필드명·별칭은 **대소문자를 가리지 않는다**. 방향을 안 쓰면 `ASC`다.
- 공백·특수문자가 있는 값은 따옴표로 감싼다(`"진행 중"`). 낱말 전체가 수일 때만 숫자로 읽으므로
  `2026-09-06`·`-7d`는 낱말이다.
- 빈 질의는 전체 + 기본 정렬(`updated DESC`)이다. 정렬은 언제나 `id ASC`로 마무리해 페이지가 흔들리지 않는다.
- 상대 날짜는 `-7d` `+2w` `-1M` `+1y`(일/주/월/년), 함수는 `now()` `startOfDay(±n)` `endOfDay(±n)`
  `startOfWeek(±n)` `endOfWeek(±n)` `startOfMonth(±n)` `endOfMonth(±n)` `startOfYear(±n)` `endOfYear(±n)`.
  경계는 **Asia/Seoul** 기준이고 주의 시작은 월요일이다.

### 필드

| 필드 | 별칭 | 타입 | 값 |
|---|---|---|---|
| `project` | 프로젝트 | enum | 키(`ALM`) 또는 이름 |
| `key` | 키 | text | `ALM-12`. `~`로 부분 일치 |
| `type` | 타입, 유형 | enum | 레지스트리 id 또는 이름(작업/스토리/버그/에픽/하위 작업) |
| `status` | 상태 | enum | 상태 id 또는 이름 |
| `statusCategory` | 상태분류 | enum | `new`/`active`/`complete` 또는 할 일/진행 중/완료 |
| `priority` | 우선순위 | enum(순서) | id 또는 이름. `priority >= high`는 "high 이상으로 중요" |
| `assignee` | 담당자, 담당 | user | 이름·이메일·local-part·숫자 id·`currentUser()`·`EMPTY` |
| `reporter` | 보고자 | user | 위와 같음(`EMPTY`는 없다 — 보고자는 항상 있다) |
| `labels` | 라벨 | multi | `labels IN ("a","b")`, `labels = a`, `IS EMPTY` |
| `component` | 컴포넌트 | multi | 이름 또는 id |
| `sprint` | 스프린트 | enum | 이름·id, `EMPTY`(백로그), `openSprints()` |
| `fixVersion` | 수정버전, 버전 | enum | 이름 또는 id, `EMPTY` |
| `resolution` | 해결 | enum | `DONE`/`WONT_DO`/`DUPLICATE`/`CANNOT_REPRODUCE`(완료·하지않음·중복·재현불가), `EMPTY`(미해결) |
| `parent` | 상위, 상위항목 | key | `ALM-3` 또는 id, `EMPTY` |
| `created` / `updated` / `due` | 생성일 / 수정일 / 마감일 | date | 절대·상대·함수 |
| `resolved` | 해결일 | date | 절대·상대·함수, `EMPTY`(미해결). 해결 사유가 **처음** 붙은 시각(V23) |
| `estimate` | 예상시간 | number | 시간(h) |
| `text` | 텍스트, 내용 | text | `~`만 — 제목+설명 |
| `summary` | 요약, 제목 | text | `~` 포함, `=` 정확(대소문자 무시) |
| `archived` | 보관 | bool | 기본 false. `archived = true`로 보관함 검색 |

정렬 가능: `created` `updated` `due` `resolved` `priority` `key` `status` `summary` `assignee` `estimate`.
`key`는 문자열이 아니라 프로젝트+이슈 번호로 센다(`ALM-9`가 `ALM-10`보다 앞).

### 항상 걸리는 세 가지

1. **접근 범위** — 볼 수 있는 프로젝트 조건을 언제나 AND로 더한다. 못 보는 프로젝트는 이름조차 풀리지
   않는다(`프로젝트를 찾을 수 없습니다`).
2. **보관 제외 기본** — `archived`를 한 번도 쓰지 않은 질의는 보관된 이슈를 뺀다. 쓰면 그 조건이 대신한다.
3. **부정 연산자는 빈 값을 제외** — `!=`·`NOT IN`·`!~`는 JQL 그대로다. `assignee != 2`에 담당자
   미지정 이슈는 **안 들어간다**. 넣으려면 `assignee != 2 OR assignee IS EMPTY`로 명시한다.
   집합 여집합인 `NOT (…)`은 다르다 — `NOT labels = backend`는 라벨 없는 이슈를 **포함**한다.
   JQL도 필드 연산자와 `NOT` 연산자를 이렇게 가르고, 여기도 그대로 따른다.

### 이름을 id로 푸는 규칙

- 상태·타입·우선순위는 설정 레지스트리, 프로젝트는 키/이름, 컴포넌트·스프린트·버전은 이름이다.
  이름이 **여럿에 맞으면 전부**를 IN으로 넓힌다(컴포넌트·스프린트·버전 이름은 프로젝트 안에서만 유일하다).
- **사람 이름은 프로젝트 조건과 무관하게** 푼다. 부분 일치는 하지 않는다 — 정확한 표시 이름, 이메일,
  이메일 local-part, 숫자 id, `currentUser()`(JWT `sub`)만 본다. 같은 이름이 둘이면 IN으로 넓힌다.
- org에는 **표시 이름으로 찾는 창구가 없고**(`LookupMembers`는 이메일과 local-part만 본다) 전원을 훑는
  창구도 없다. 그래서 표시 이름은 이슈에 실제로 등장하는 담당자·보고자 id를 `GetMembers`로 읽어 그 안에서
  맞춘다. 이슈에 한 번도 안 나온 사람은 어차피 검색 결과에도 없다.
- 못 찾으면 400이다(빈 결과가 아니다). org를 못 읽었으면 **503**이다 — "그런 사람 없다"로 바꿔 말하지 않는다.

### 오류 계약

문법·해석 오류는 **400**이고 공통 `{"error"}`에 두 값을 더한다.

```json
{ "error": "필드를 모릅니다: statuss", "position": 0, "expected": [] }
```

- `position`은 0부터 세는 입력 오프셋이다 — 프론트 에디터가 그 자리에 밑줄을 긋는다. **틀린 것을 가리킨다**:
  모르는 필드는 필드 자리(`statuss = done` → 0), 못 쓰는 연산자는 연산자 자리(`priority ~ high` → 9),
  값 형식 오류는 값 자리(`due > yesterday` → 6).
- `expected`는 그 자리에 올 수 있었던 것이고, 모르면 빈 배열이다.
- 대표 문구: `연산자를 모릅니다: ==` · `값이 필요합니다` · `괄호를 닫아야 합니다` ·
  `따옴표를 닫아야 합니다` · `필드를 모릅니다: …` · `'~'는 텍스트 필드에만 쓸 수 있습니다 (priority)` ·
  `'>'는 날짜·숫자 필드에만 쓸 수 있습니다 (status)` · `날짜 형식이 아닙니다: yesterday` ·
  `상태를 모릅니다: …` · `사용자를 찾을 수 없습니다: …` · `정렬할 수 없는 필드입니다: project`.

### AST JSON — 프론트와의 계약

`POST /api/alm/issues/query/validate`가 `ast`로 돌려주는 모양이다. 프론트 `store/aql/parser.ts`가
같은 입력에 **글자까지 같은 JSON**을 만들어야 한다(서버 쪽 기준은 `AqlParserTest`).

두 가지가 규칙이다.

1. **필드는 쓴 그대로 담는다.** 별칭(`상태`→`status`)·소문자 정규화는 해석 단계가 한다 — 파서는 필드 표를
   몰라도 된다.
2. **같은 종류의 이항 연산자는 평탄화한다.** `a AND b AND c`는 자식 셋인 `and` 하나이고, 자식이 하나면
   감싸지 않는다.

노드는 `and`/`or`(`children`) · `not`(`child`) · `compare`(`field`,`operator`,`value`) ·
`in`(`field`,`negated`,`values`) · `empty`(`field`,`negated`)이고, 값은
`{"type":"string"|"ident"|"number","value":…}` 또는 `{"type":"function","name":…,"args":[…]}`다.

벡터 1 — `status = "진행 중" AND assignee = currentUser()`

```json
{
  "where": {
    "kind": "and",
    "children": [
      {"kind": "compare", "field": "status", "operator": "=", "value": {"type": "string", "value": "진행 중"}},
      {"kind": "compare", "field": "assignee", "operator": "=", "value": {"type": "function", "name": "currentUser", "args": []}}
    ]
  },
  "orderBy": []
}
```

벡터 2 — `project = ALM AND (priority >= high OR due <= +3d) ORDER BY due ASC, priority DESC`

```json
{
  "where": {
    "kind": "and",
    "children": [
      {"kind": "compare", "field": "project", "operator": "=", "value": {"type": "ident", "value": "ALM"}},
      {
        "kind": "or",
        "children": [
          {"kind": "compare", "field": "priority", "operator": ">=", "value": {"type": "ident", "value": "high"}},
          {"kind": "compare", "field": "due", "operator": "<=", "value": {"type": "ident", "value": "+3d"}}
        ]
      }
    ]
  },
  "orderBy": [
    {"field": "due", "direction": "asc"},
    {"field": "priority", "direction": "desc"}
  ]
}
```

`in`·`empty`·`not`이 섞인 나머지 벡터(§6 3~7번)의 AST는 `AqlParserTest`에 문자열 그대로 박혀 있다.

### 요청·응답

```jsonc
// POST /api/alm/issues/query
{ "aql": "project = ALM AND status != 완료 ORDER BY due ASC", "page": 0, "size": 50 }
// → 기존 검색과 같은 이슈 shape + echoedAql (size는 최대 200)
{ "items": [ /* IssueResponse */ ], "page": 0, "size": 50, "total": 12,
  "echoedAql": "project = ALM AND status != 완료 ORDER BY due ASC" }

// POST /api/alm/issues/query/validate  → 문법·필드·연산자만 본다(값이 실재하는지는 실행 때 확인)
{ "aql": "상태 = 완료 ORDER BY due" }
{ "ok": true, "fields": ["status", "due"], "ast": { /* 위 shape */ } }
{ "ok": false, "error": "괄호를 닫아야 합니다", "position": 14, "expected": [")"] }

// GET /api/alm/issues/query/fields  → 자동완성 사전
{ "fields": [ { "name": "status", "aliases": ["상태"], "kind": "ENUM",
                "operators": ["=", "!=", "IN", "NOT IN"], "sortable": true, "emptyAllowed": false,
                "values": [ { "id": "inprogress", "name": "진행 중" } ] } ],
  "functions": [ { "name": "currentUser", "signature": "currentUser()",
                   "fields": ["assignee", "reporter"], "description": "지금 로그인한 사람" } ],
  "keywords": ["AND", "OR", "NOT", "IN", "NOT IN", "IS", "IS NOT", "EMPTY", "ORDER BY", "ASC", "DESC"] }
```

사용자 후보는 사전에 넣지 않는다 — 프론트가 `/api/org/members`로 따로 받는다.

### 질의 상한

되돌아오지 않는 재귀와 무한정 큰 질의를 파서 앞에서 막는다 — 안 막으면 `((((…`가 `StackOverflowError`로
터져 500이 된다.

| 상한 | 값 | 걸리면 |
|---|---|---|
| AQL 문자열 | 4000자 | 400 `AQL은 4000자 이하여야 합니다`(요청 검증이라 `position` 없음) |
| 중첩 깊이(괄호·`NOT`) | 50단계 | 400 `너무 깊게 중첩됐습니다 (최대 50단계)` |
| 절 개수 | 200개 | 400 `조건이 너무 많습니다 (최대 200개)` |
| 페이지 크기 | 200 | 넘기면 200으로 깎는다 |

`~` 검색에서 사용자가 친 `%`와 `_`는 **글자 그대로**다. LIKE 와일드카드로 새지 않게 이스케이프하므로
`text ~ "%"`가 전체를 매치하지 않고 `_`가 아무 한 글자로 번지지 않는다.

### 한계

- 해결일(`resolved`)은 **해결 사유가 처음 붙은 시각**이다. 사유를 다른 사유로 바꾸는 것(완료 → 중복)은
  다시 해결한 것이 아니라 시각을 건드리지 않고, 해결을 풀면 비워진다. V23 이전부터 해결돼 있던 이슈는
  마지막 수정 시각으로 백필했으므로 그만큼 근사치다.
- 비어 있을 수 있는 정렬 키(`resolved`·`due`·`assignee`·`estimate`)에서 빈 값이 앞에 오는지 뒤에 오는지는
  DB가 정한다 — AQL이 따로 고정하지 않는다.
- `validate`는 값을 해석하지 않는다. `status = 없는상태`는 검증을 통과하고 실행에서 400이 난다.
- 표시 이름 해석의 후보는 이슈에 등장하는 사용자 1000명까지다.
- 보관함 검색을 위해 `issue` 테이블을 읽기 전용 엔티티(`AqlIssueRow`)로 한 번 더 매핑한다.
  `Issue`에는 `@SQLRestriction("archived_at is null")`이 걸려 있어 Criteria가 보관된 행을 못 본다.

## 저장 필터

사이드바에 꽂아 두는 내 검색. 스마트 검색 문자열이거나 AQL이며 무엇을 저장했는지는 `kind`가 말한다.
정본은 서버(`saved_filter`, V24)이고 소유자 본인만 고친다. `shared`(V28, F-12)면 조직 전체가 보고 실행한다(결과는 보는 사람 권한).

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/alm/me/filters` | 내 필터 목록(이름 순) |
| `POST` | `/api/alm/me/filters` | 생성 — `{name, kind, query}` → 201 |
| `PUT` | `/api/alm/me/filters/{id}` | 수정 — `{name?, kind?, query?}`, 보낸 항목만 바뀐다 |
| `DELETE` | `/api/alm/me/filters/{id}` | 삭제 → 204 |

응답은 `{id, name, kind, query, createdAt, updatedAt}`이다(`ownerId`는 내보내지 않는다).

- **소유 격리**: 남의 필터를 지목하면 403이 아니라 **404**다 — 있다는 사실 자체를 알리지 않는다.
- **이름**: 1~60자, 한 사람 안에서 유일하다. 겹치면 409 `{"error": "같은 이름의 필터가 있습니다"}`.
  DB 유니크(`owner_id`, `name`)가 같은 문장으로 뒤를 받친다.
- **종류**: `smart` 또는 `aql`. 그 밖이면 400 `필터 종류는 smart 또는 aql입니다`.
- **질의**: 1~4000자. `kind`가 `aql`이면 저장할 때 문법을 검사해 틀리면 400
  `{error, position, expected}`로 거절한다(검색과 같은 오류 계약) — 못 여는 필터를 꽂아 두고 누를 때마다
  400을 보느니 저장을 막는다. 값 해석(그런 상태가 실재하는가)은 검색과 마찬가지로 실행할 때 한다.
  `kind`만 `aql`로 바꿔도 이미 저장돼 있던 문자열이 검사 대상이다.

## 계획 필드·플래그 (V30, 웨이브 D2·D3)

이슈 응답(`IssueResponse`)에 네 필드가 붙는다(뒤에 추가, 하위 호환).

| 필드 | 타입 | 뜻 |
|---|---|---|
| `startDate` | `"yyyy-MM-dd"` 또는 null | 시작일. 마감일보다 늦으면 400 `시작일은 마감일보다 늦을 수 없습니다`(DB CHECK가 뒤를 받친다) |
| `storyPoints` | number 또는 null | 스토리 포인트 0~9999, 소수 첫째 자리(0.5 가능) |
| `flagged` | boolean | 플래그(막힘) |
| `triageState` | `"PENDING"`·`"SNOOZED"` 또는 null | 트리아지 대기열(V35). 보류 시각이 지났으면 PENDING |

- **쓰는 길 — `planning`**: 생성·수정 요청에 `planning: {startDate?, storyPoints?}`. `details`("보내면 통째로 교체")와 달리
  **키를 보낸 것만** 바뀐다(생략=그대로, null=비우기) — 이 필드를 모르는 옛 클라이언트의 저장이 값을 지우지 않는다.
  필드 구성(스킴 `fields`)에 `startDate`·`storyPoints`가 추가돼 15종이다(필수로 걸면 생성 때 `시작일은 필수입니다`).
- **플래그 — `PUT /api/alm/issues/{id}/flag`** `{flagged: true, comment?: "이유"}` → 이슈. EDIT. **version을 올리지 않는다**
  (보드 이동과 같은 규칙 — 보드에서 플래그를 켰다고 열어 둔 편집 폼이 409가 되지 않는다. PUT 본문에 없는 값이라 오래된
  폼 저장이 되돌리지도 않는다). 이유를 주면 `플래그 추가: …` 코멘트가 남는다. 일괄 편집 `changes.flagged`도 된다.
- **AQL**: `startDate`(별칭 `시작일`, DATE), `storyPoints`(`스토리포인트`·`포인트`, NUMBER), `flagged`(`플래그`·`막힘`, BOOL),
  `triage`(`트리아지`, BOOL — 대기열 이슈). 시작일·포인트는 `IS EMPTY`·정렬 가능. 자동완성 사전(`/query/fields`)에 그대로 실린다
  — 프론트 목업 평가기도 같은 필드를 더한다.

```json
POST /api/alm/projects/7/issues
{ "title": "로그인 개선", "details": { "dueDate": "2026-11-10" },
  "planning": { "startDate": "2026-11-01", "storyPoints": 3 } }
```

### 추정 합계·속도

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/alm/projects/{projectId}/estimation/summary` | VIEW | 진행 중·예정 스프린트(번호 순) + 백로그의 합계. 트리아지 대기열은 빠진다 |
| `GET` | `/api/alm/projects/{projectId}/reports/velocity?unit=points&limit=7` | VIEW | 최근 완료 스프린트의 속도(`unit` = points 기본·hours·count, `limit` 최대 20) |

```json
{ "projectId": 7, "groups": [
  { "sprintId": 12, "name": "Sprint 3", "state": "ACTIVE", "issueCount": 8, "completedCount": 3,
    "storyPoints": 21, "completedStoryPoints": 8, "unestimatedCount": 2, "estimateHours": 30.5, "completedEstimateHours": 10 },
  { "sprintId": null, "name": "백로그", "state": null, "issueCount": 14 } ] }

{ "projectId": 7, "unit": "points", "average": 17.5,
  "sprints": [ { "sprintId": 11, "name": "Sprint 2", "completedAt": "2026-10-20T09:00:00Z", "committed": 24, "completed": 18 } ] }
```

- 완료 판정은 서버 워크플로의 완료 의미(complete)다. `committed` = 완료 시점 범위(완료분 + 스프린트 완료 처리로 그 순간 이월된
  이슈 — 이력 시각이 `completedAt`과 같은 것). 값은 **지금의 추정치**로 센다(추정 변경 이력은 남기지 않는다). 번다운은 지금처럼
  프론트가 변경 이력으로 그리고, 포인트 기준은 이슈 응답의 `storyPoints`로 고른다.

## 저장된 보기 (V31, 웨이브 D1)

필터(스마트·AQL) + 표시 옵션을 이름 붙여 저장한다. 공유 모델은 저장 필터(F-12)와 같은 결 — **질의와 표시 옵션만** 공유하고
결과는 여는 사람이 자기 권한으로 다시 실행한다. 다른 점은 공유 범위가 조직 전체가 아니라 **프로젝트**(Linear 팀 보기)라는 것.

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/alm/me/views?projectId=` | 인증 | 내 개인 보기(프로젝트 지정 시 그 프로젝트 것만) |
| `GET` | `/api/alm/projects/{projectId}/views` | VIEW | 프로젝트 공유 보기(먼저) + 그 프로젝트의 내 개인 보기, 각각 이름 순 |
| `GET` | `/api/alm/views/{id}` | 내 것 또는 프로젝트 VIEW | 단건. 그 밖은 404 |
| `POST` | `/api/alm/views` | personal: 인증(프로젝트 지정 시 VIEW) / project: EDIT | 만들기 → 201 |
| `PUT` | `/api/alm/views/{id}` | 만든 사람(공유면 지금 EDIT) 또는 프로젝트 ADMIN | 보낸 항목만, `expectedVersion` 필수(어긋나면 409). `clearFilter: true`로 필터 지우기 |
| `DELETE` | `/api/alm/views/{id}` | 같음 | 204 |

```json
{ "id": 31, "name": "막힌 이슈", "scope": "project", "projectId": 7, "ownerId": 42, "mine": true, "canEdit": true,
  "layout": "board", "filter": { "kind": "aql", "query": "flagged = true" },
  "display": { "groupBy": "assignee", "subGroupBy": "none", "orderBy": "storyPoints", "orderDirection": "desc",
               "properties": ["key", "status", "assignee", "storyPoints"], "showSubIssues": true, "showEmptyGroups": false },
  "version": 1, "createdAt": "2026-10-24T09:00:00Z", "updatedAt": "2026-10-24T09:00:00Z" }
```

- `scope`: `personal`(만든 사람만, 남이 지목하면 404) · `project`(그 프로젝트 VIEW 모두). 프로젝트는 만든 뒤 바꾸지 않는다.
  볼 수 있는 남의 공유 보기를 고치면 403, 공유 보기를 비공개로 돌리는 것은 만든 사람만.
- 표시 옵션은 닫힌 어휘다 — `groupBy`/`subGroupBy`: none·status·assignee·priority·sprint·epic·label·type·component·fixVersion
  (하위 그룹은 그룹과 달라야 한다), `orderBy`: manual·priority·status·updated·created·due·startDate·storyPoints·estimate·title·key,
  `properties`: key·type·status·priority·assignee·reporter·labels·components·sprint·fixVersion·due·startDate·storyPoints·estimate·
  flagged·parent·created·updated. 모르는 값은 400, 비운 항목은 기본값으로 채워 **항상 전부 있는 모양**으로 응답한다.
- `layout`: list·board. 필터 규칙은 저장 필터와 같다(`kind` smart·aql, aql은 저장 때 문법 검사 → 400 `{error, position, expected}`).

## 트리아지 (V35)

Linear식 대기열 — 일반 워크플로 **밖**에서 새로 들어온 이슈를 받는다. 새 상태 분류를 만들지 않는다(kind 3개 고정) — 대기열은
`issue.triage_state`이고 그동안 상태는 워크플로 첫 '할 일'이다.

**들어오는 길(정의)**: ① 트리아지를 켠 프로젝트에서 **편집 권한이 없는 열람자**가 만든 이슈(`EXTERNAL`) — 트리아지가 꺼져 있으면
예전처럼 403, ② 편집자가 생성 요청에 `triage: true`를 주거나 기존 이슈를 `POST /issues/{id}/triage`로 보낸 이슈(`MANUAL`).
대기열 이슈는 스프린트에 들어가지 않고 **보드·백로그(`/projects/{id}/issues`)·추정 합계에서 빠진다**. 검색·AQL에는 나온다(`triage = true`).
API·가져오기(CSV)로 만든 이슈는 일반 생성이다(편집 권한 기준). 이메일 수신 경로는 아직 없다.

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| `GET` | `/api/alm/projects/{projectId}/triage/settings` | VIEW | `{projectId, enabled, pending, snoozed}` |
| `PUT` | `/api/alm/projects/{projectId}/triage/settings` | ADMIN | `{enabled}` — 꺼도 남은 대기열은 처리할 수 있다 |
| `GET` | `/api/alm/projects/{projectId}/triage?state=pending` | VIEW | 대기열(들어온 순서), 이슈 배열. `state` = pending(기본)·snoozed·all |
| `POST` | `/api/alm/issues/{issueId}/triage` | EDIT | 대기열로 보내기(완료된 이슈는 400, 스프린트에서 빠진다) |
| `POST` | `/api/alm/issues/{issueId}/triage/accept` | EDIT | `{status?, assigneeId?, priority?, comment?}` — 지정 상태(기본 첫 '할 일')로 백로그 끝. 완료 의미 상태로는 400 |
| `POST` | `/api/alm/issues/{issueId}/triage/duplicate` | EDIT(+원본 VIEW) | `{duplicateOfIssueId, comment?}` — "중복" 링크 + 해결 DUPLICATE + 첫 완료 상태 |
| `POST` | `/api/alm/issues/{issueId}/triage/decline` | EDIT | `{comment?}` — 해결 WONT_DO("하지 않음") + 첫 완료 상태 |
| `POST` | `/api/alm/issues/{issueId}/triage/snooze` | EDIT | `{until}` — 미래, 최대 1년. 지나면 다시 PENDING |

대기열에 없는 이슈를 처리하면 400 `트리아지 대기열에 없는 이슈입니다`. 권한 서비스 불능은 대기열로 새지 않고 503이다.

## 자동화 규칙 (V36·V37)

프로젝트 단위 "트리거 → 조건(AQL) → 액션"(지라 Automation 패턴). 관리·이력 조회는 **프로젝트 ADMIN**만.

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET`·`POST` | `/api/alm/projects/{projectId}/automation/rules` | 목록(만든 순) / 만들기 → 201 (프로젝트당 100개) |
| `GET`·`PUT`·`DELETE` | `/api/alm/automation/rules/{ruleId}` | 단건 / 통째로 고치기(`expectedVersion`, 어긋나면 409) / 지우기(이력도) |
| `GET` | `/api/alm/automation/rules/{ruleId}/runs?limit=50` | 규칙의 최근 실행(최신 순, 최대 200) |
| `GET` | `/api/alm/projects/{projectId}/automation/runs?limit=50` | 프로젝트 감사 로그 |

```json
{ "name": "끝나면 막힘 해제", "enabled": true, "allowRuleTrigger": false,
  "trigger": { "type": "ISSUE_TRANSITIONED", "fromStatuses": [], "toStatuses": ["done"] },
  "condition": "flagged = true",
  "actions": [ { "type": "SET_FIELD", "field": "flagged", "value": false },
               { "type": "COMMENT", "body": "완료되어 플래그를 내렸습니다" },
               { "type": "NOTIFY", "recipients": ["assignee"], "userIds": [7], "message": "완료 처리됐습니다" } ] }
```

응답은 위 항목 + `id, projectId, disabledReason, actorId, createdBy, nextRunAt, lastRunAt, consecutiveFailures, version, createdAt, updatedAt`.
실행 이력 한 줄: `{id, ruleId, projectId, issueId, issueKey, triggerType, outcome, depth, actionsRun, message, startedAt, finishedAt}` —
`outcome`은 SUCCESS·FAILED·LOOP_BLOCKED·RATE_LIMITED. 조건에 안 맞아 건너뛴 실행은 남기지 않는다. 보존 30일.

- **트리거**: `ISSUE_CREATED` · `ISSUE_TRANSITIONED`(`fromStatuses`/`toStatuses`, 비우면 아무 상태) · `FIELD_CHANGED`(`fields` 하나 이상 —
  title·description·type·status·priority·assignee·parent·sprint·dueDate·startDate·storyPoints·estimate·resolution·fixVersion·labels·
  components·flagged·triage) · `SCHEDULED`(`cron`, 서울 시각, 5필드 또는 6필드, **5분보다 자주 불가**, 조건 AQL 필수 = 대상 검색, 회차당 100건).
- **액션**(최대 10개): `SET_FIELD`(priority·dueDate·startDate·storyPoints·flagged) · `ASSIGN`(`assignee`: reporter·unassigned·user+`userId`) ·
  `COMMENT`(`body`) · `TRANSITION`(`toStatus` — 워크플로 엔진의 전환 실행을 그대로 탄다: 실행 주체로 평가한 가용 전환 중 도착 상태가 같은 첫 전환, 조건·검증·해결 규칙·후처리 포함. 갈 수 없으면 그 이유로 FAILED) · `LABELS`(`add`/`remove`) · `NOTIFY`(`recipients` assignee·reporter·watchers + `userIds`,
  `message` 200자 이하 → 인앱 알림 `type: AUTOMATION`, `detail`=문장, 메일 없음). 모르는 종류·필드·상태·우선순위는 저장 때 400.
- **실행 주체**: 마지막으로 저장한 사람(`actorId`). 액션은 **사람과 같은 서비스 경로**(권한·워크플로 전환 규칙·검증·이력·알림·이벤트)를
  그 사람 권한으로 탄다 — 권한을 잃으면 실행이 FAILED로 남는다(fail-closed). 조건 AQL도 그 사람이 볼 수 있는 범위에서만 맞는다.
- **안전장치**: ① 규칙이 만든 변경은 기본적으로 다른 규칙을 깨우지 않는다(`allowRuleTrigger: true`인 규칙만) ② 연쇄 깊이 3단계
  (`platform.alm.automation.max-depth`) ③ 같은 연쇄 안 같은 (규칙, 이슈)는 한 번(핑퐁 차단) — ②③은 LOOP_BLOCKED로 남는다
  ④ 규칙당 분당 30회(`rate-per-minute`) — 넘으면 RATE_LIMITED + 자동 비활성 ⑤ 연속 5회 실패(`max-failures`) — 자동 비활성(권한 서비스
  불능 503은 규칙 잘못이 아니라 세지 않는다) ⑥ 한 실행의 액션은 한 트랜잭션(하나라도 실패하면 전부 되돌림) ⑦ 실행은 커밋 뒤
  별도 스레드 풀(2개, 대기열 500 — 가득 차면 FAILED로 남김)이라 사람의 요청을 기다리게 하지 않는다. 자동으로 꺼진 규칙은 `disabledReason`이 있고,
  켜서 저장하면 지워진다. 예약 회차는 조건부 UPDATE로 한 노드만 돈다.
- 트리거는 같은 프로세스 안의 도메인 이벤트(`event/domain`: `IssueCreatedEvent`·`IssueUpdatedEvent(changedFields)`·`IssueTransitionedEvent`,
  커밋 뒤 `@TransactionalEventListener`)에 붙는다. Redis Streams 이벤트(아웃박스)와 별개다.

## OpenAPI

`GET /v3/api-docs`가 이 서비스의 OpenAPI 3.1 스펙(JSON)을 낸다. springdoc(`springdoc-openapi-starter-webmvc-api`)이
컨트롤러 주석에서 뽑아내며, Swagger UI는 싣지 않는다 — 사람이 읽는 문서는 myFront 생성기가 이 JSON을
받아 `/docs/`의 "API 레퍼런스" 트리로 만든다.

- **토큰 없이 읽힌다.** `SecurityFilterChain`에서 `/v3/api-docs/**`만 permitAll이다. 게이트웨이와 nginx가
  `/v3`를 라우팅하지 않으므로 클러스터 안에서만 보인다.
- **주석 규약.** 컨트롤러에 `@Tag(name, description)`(name은 영문 리소스명, description은 한국어 한 줄),
  엔드포인트마다 `@Operation(summary)`, 뜻이 드러나지 않는 파라미터에 `@Parameter(description)`,
  주요 DTO 필드에 `@Schema(description, example)`. 한 컨트롤러가 여러 리소스를 담는 협업·설정은
  메서드마다 태그를 나눠 붙이고, 그 설명은 `config/OpenApiConfig`가 한 번에 준다.
- **공통 오류.** `OperationCustomizer`가 규칙대로 붙인다 — 세 서비스(wiki·alm·org)가 같은 규칙을 쓴다.
  스키마는 `{"error": "메시지"}`(`PlatformError`)로 common-starter의 오류 계약과 같다.

  설명 문구는 세 서비스가 글자 그대로 공유한다(2026-09-05 확정) — `OpenApiConfig.ERROR_DESCRIPTIONS`에
  모아 두었고 `OpenApiDocsTest`가 상수와 실제 스펙 양쪽을 검사한다.

  | 상태 | 설명 문구 | 붙는 오퍼레이션 | 개수 |
  |------|----------|----------------|------|
  | 400 | 요청 검증 실패 | 요청 본문(`@RequestBody`·`MultipartFile`)이 있는 것 | 48 |
  | 401 | 인증 실패 — 토큰 없음·만료·무효 | 전부 | 124 |
  | 403 | 권한 없음 | 전부 | 124 |
  | 404 | 대상 없음 | 경로 변수가 있는 것 | 94 |
  | 409 | 버전 충돌 — expectedVersion 불일치 | `expectedVersion`을 받는 PUT | 4 |
  | 503 | 권한 서비스(org) 불능 | org gRPC로 권한을 판정하는 것 | 102 |

  **400은 본문을 받는 엔드포인트를 기준으로 붙인 것이지, 400이 날 수 있는 모든 경로를 빠짐없이 적은 목록이 아니다.**
  읽는 사람이 본문을 잘못 보내 실제로 400을 만들 수 있는 자리를 표시한 것으로 읽어야 한다 — 예를 들어
  잘못된 쿼리 파라미터 타입도 400이 되지만 문서에는 적지 않는다.

  503은 `GrpcPermissionClient`가 org 불능(UNAVAILABLE·DEADLINE_EXCEEDED)에서 던지는
  `ServiceUnavailableException`이다. alm은 프로젝트 권한이든 전역 관리자 판정이든 같은 클라이언트를 타므로
  503이 기본이고, org를 부르지 않는 26개(대시보드·개인 설정·배너 읽기·내 알림·저장 필터·레지스트리 읽기)만
  `@NoOrgDependency`로 표시해 뺀다. 이 표식은 문서 전용이며 보안 통제가 아니다.

  springdoc은 기본값으로 예외 핸들러가 다루는 상태를 모든 오퍼레이션에 복사하므로(GET에도 404·409가 달린다)
  `springdoc.override-with-generic-response: false`로 끈다.
- **게이트.** `OpenApiDocsTest`가 스펙 200·태그와 요약 누락 0건·내부 전용 경로 부재·전역 `bearerAuth`를 검증한다.

## 전역 관리자 판정 (2026-09-05)

**판정 주체는 org-service 하나다.** 전역 관리자는 org의 `GLOBAL`/`ADMIN` grant이며, gRPC
`CheckPermission(GLOBAL, ADMIN)`으로 묻는다. 그 전에는 Keycloak realm 역할 `ADMIN`(JWT `roles`)으로
따로 판정했다 — 같은 사람에 대해 wiki와 ALM이 서로 다른 답을 낼 수 있는 구조였다. **JWT 역할은 이제
인가에 쓰지 않는다.** 판정 규칙과 grant 모델은 `platform-backend/README.md`의 "org-service > 권한 모델"
절이 정본이다.

**최초 관리자는 `PLATFORM_BOOTSTRAP_ADMIN_ID` 시드**(org-service `BootstrapAdminSeeder`)로 생긴다.
ALM에는 부트스트랩 경로가 없다.

> ⚠️ **로컬에서 관리 화면이 전부 403이면 대개 이것이다.** compose는
> `PLATFORM_BOOTSTRAP_ADMIN_ID:-1`로 1을 주입해 사용자 1이 재기동마다 GLOBAL ADMIN으로 복구되지만,
> `gradlew :org-service:bootRun`으로 직접 띄우면 값이 비어 있어 **아무도 시딩되지 않는다.** dev-offset
> 클러스터로 개발할 때는 org-service 실행에 이 값을 지정하거나, `POST /api/org/grants`로 직접 넣는다.

### 응답 계약

| 상황 | 상태 | 본문 |
|---|---|---|
| grant 없음 · 사유 불명 | `403` | `{"error":"전역 관리자만 할 수 있습니다"}` |
| `denied_reason=PENDING` | `403` | `{"error":"승인 대기 중인 계정입니다"}` |
| `denied_reason=SUSPENDED` | `403` | `{"error":"정지된 계정입니다"}` |
| `denied_reason=DEACTIVATED` | `403` | `{"error":"비활성된 계정입니다"}` |
| org-service 불능(`UNAVAILABLE`·`DEADLINE_EXCEEDED`) | `503` | `{"error":"권한 서비스에 연결할 수 없습니다"}` |

`denied_reason`(common-proto 0.16.0)은 **거부 사유이지 장애 신호가 아니다** — 모르는 값은 일반 거부로
다룬다(값은 앞으로 늘 수 있다). 장애는 gRPC 상태 코드로만 판단하며 `503`이다. 프론트가 이 `503`을
"권한 없음"으로 그리면 org가 죽은 동안 관리자에게 "당신은 관리자가 아니다"라고 거짓말하게 된다.
프로젝트 권한(`VIEW`/`EDIT`/`ADMIN`)도 같은 구분을 따른다: 계정 상태로 막힌 것이면 위 문구를,
권한만 모자라면 `ADMIN 권한이 필요합니다 (project N)`를 준다.

판정은 30초 캐시된다(`GrpcPermissionClient`). grant 회수뿐 아니라 **계정 정지·비활성도 그만큼 늦게**
반영된다.

### 계정 상태 격리

권한 판정은 프로젝트를 건드리는 경로만 지킨다 — 레지스트리·스킴 읽기처럼 grant를 묻지 않는 전역 읽기는
org REST가 막아 둔 승인 대기 계정에게도 열려 있었다(2026-09-05 실측). `AccountStatusInterceptor`가
`/api/alm/**` 전체에서 요청자의 org 계정 상태를 확인하고 `PENDING`·`SUSPENDED`·`DEACTIVATED`면 위 표와
같은 문구로 403을 낸다. 상태는 `GetMembers([me])`로 읽고 30초 캐시한다(그만큼 반영이 늦다).
**org에 아직 그 사람이 없으면 통과**시킨다 — member 행은 첫 org 호출 때 생기고 ALM이 먼저 불릴 수 있어서,
없는 것을 막으면 정상 사용자가 미러링 순서에 따라 무작위로 차단된다(org gRPC의 규칙과 같다).
org 불능은 `503`이고, 그 밖의 조회 실패는 warn만 남기고 통과시킨다(org 버그를 "계정 정지"로 말하지 않는다 —
권한이 필요한 경로는 그때도 fail-closed로 닫힌다). actuator 헬스체크는 이 게이트를 타지 않는다.

### 전역 관리자 전용 엔드포인트 (26개)

읽기는 로그인이면 되고 아래 쓰기만 막힌다 — 레지스트리·스킴은 모든 프로젝트가 함께 보는 값이다.
(애너테이션은 25개다: `AdminController`는 클래스 단위로 걸려 두 엔드포인트를 덮는다.)

| 메서드 | 경로 | 설명 |
|---|---|---|
| `GET` | `/api/alm/admin/audit` | 감사 로그 |
| `GET` | `/api/alm/admin/stats` | 시스템 현황 |
| `PUT` | `/api/alm/admin/banner` | 공지 배너 저장(읽기 `GET /api/alm/banner`는 인증만) |
| `POST`/`PUT`/`DELETE` | `/api/alm/settings/categories[/{id}]` | 상태 카테고리 — `POST /{id}/move` 포함 |
| `POST`/`PUT`/`DELETE` | `/api/alm/settings/statuses[/{id}]` | 상태 |
| `POST`/`PUT`/`DELETE` | `/api/alm/settings/link-types[/{id}]` | 링크 타입 — `POST /{id}/move` 포함 |
| `POST`/`PUT`/`DELETE` | `/api/alm/settings/priorities[/{id}]` | 우선순위 — `POST /{id}/move` 포함 |
| `POST`/`PUT`/`DELETE` | `/api/alm/settings/issue-types[/{id}]` | 이슈 타입 — `POST /{id}/move` 포함 |
| `POST`/`PUT`/`DELETE` | `/api/alm/settings/schemes[/{id}]` | 설정 스킴 — `POST /{id}/default` 포함 |

프로젝트 설정 쓰기(`PUT /api/alm/projects/{id}/settings/*`)는 전역 관리자가 아니라 **그 프로젝트의
ADMIN**이다.

## 서비스 경계

```text
gateway-server ──REST/JWT──▶ alm-backend ──JPA──▶ PostgreSQL
                                  │
                                  ├─gRPC──▶ org-service (권한 판정 · 사용자 디렉터리)
                                  ├─XADD──▶ Redis Streams (커밋 이후 이벤트)
                                  └◀─gRPC── search-service (이슈 원문 조달)
```

- 권한 판정은 `VIEW < EDIT < ADMIN`이며, org-service 장애를 권한 없음으로 오인하지 않는다.
  `UNAVAILABLE`·`DEADLINE_EXCEEDED`는 REST `503`으로 응답한다. 프로젝트 권한과 전역 관리자
  (`GLOBAL`/`ADMIN`) 둘 다 org-service가 판정한다 — "전역 관리자 판정" 절을 볼 것.
- 알림 메일 주소도 org-service가 원장이다(`GetMembers`). 조회는 **커밋 뒤 발송 스레드**에서 하고
  수신자가 여럿이면 한 번에 묻는다 — 쓰기 트랜잭션이 남의 서비스를 기다리지 않는다. 못 읽으면
  개인 설정의 주소 스냅샷으로 폴백하고, 그것도 없으면 그 한 통을 생략한다. 비활성·정지된 계정에는
  스냅샷 주소가 남아 있어도 보내지 않는다.
- **메일 발송도 org-service가 한다**(2026-09-07 플랫폼 메일 설계). ALM은 SMTP를 모르고
  `POST /internal/org/mail`(헤더 `X-Internal-Token`, 본문 `{to, subject, text, html?, source:"alm"}`)로
  넘긴다. 202 `{accepted, disabled}`가 정상 응답이고, `disabled: true`(관리자가 메일을 끔)는 실패가
  아니라 "나가지 않음"으로 따로 구분한다. 받는 사람은 한 요청에 100명까지이며 넘기면 400이라
  소비자가 100건씩 나눠 부른다. 메일 서버 설정·자격증명·재시도·발송 로그·보낸 사람 주소는
  전부 org의 메일 설정이 정본이고, 관리자가 화면에서 끄면 세 서비스가 함께 꺼진다. 채널이 살아
  있는지는 `GET /internal/org/mail/status`(60초 캐시)가 알려 주며 개인 설정 응답의 `mailConfigured`가
  그 값이다. 허브가 답하지 않아도 이슈 저장과 알림함은 그대로다.
- 이벤트에는 이슈 본문을 싣지 않는다. 변경과 같은 트랜잭션에서 아웃박스(`event_outbox`, V26)에
  한 줄 적고, 커밋 뒤 워커가 id 순으로 XADD한다(at-least-once, 같은 eventId로 재시도). 요청 경로는
  Redis를 기다리지 않고, Redis 장애 중 커밋된 이벤트도 지수 백오프로 재시도된다. 최대 시도를 넘긴 행은
  `last_error`와 함께 남고(오류 로그), 그때의 색인은 관리자 재색인으로 복구한다.
- 첨부 한도는 `ALM_MAX_ATTACHMENT_MB` 하나다. 요청 한도는 multipart 경계·헤더 몫 1MB를 더해 파생되고,
  초과는 413 `{"error": "첨부 파일은 20MB까지 올릴 수 있습니다"}`다.
- gRPC `AlmContentService`는 search-service 전용이다. 컨테이너 배포에서는 호스트에 포트를
  공개하지 않는다.
- 없는 이슈의 gRPC 응답은 `NOT_FOUND`, 참조가 깨진 고아 이슈는 `FAILED_PRECONDITION`이다.

## 환경 변수

| 변수 | 기본값 | 용도 |
|---|---|---|
| `ALM_DB_URL` | `jdbc:postgresql://localhost:5433/almdb` | PostgreSQL 연결 |
| `ALM_DB_USERNAME` / `ALM_DB_PASSWORD` | `keycloak` / `keycloak` | DB 자격증명 |
| `AUTH_JWKS_URI` | `http://localhost:9000/.well-known/jwks.json` | JWT 공개키 |
| `PLATFORM_ISSUER` / `PLATFORM_AUDIENCE` | `http://localhost:9000` / `platform-api` | JWT 검증 계약 |
| `ORG_GRPC_HOST` / `ORG_GRPC_PORT` | `localhost` / `9131` | 권한 판정(프로젝트·전역 관리자)과 사용자 디렉터리 |
| `ALM_GRPC_ENABLED` / `ALM_GRPC_PORT` | `true` / `9121` | 콘텐츠 조달 gRPC |
| `PLATFORM_GRPC_TOKEN` | (빈 값) | 서비스 간 gRPC 토큰(P-01). org 호출 메타데이터 `x-internal-token`에 싣고, 콘텐츠 gRPC 서버는 이 값으로 검사한다. 플랫폼 공용 1개 |
| `PLATFORM_GRPC_AUTH_MODE` | `monitor` | 콘텐츠 gRPC 서버의 토큰 검사: `off`·`monitor`(통과+경고)·`enforce`(거부) |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` | `localhost` / `6379` / `0` | 이벤트 스트림 |
| `EVENTS_ENABLED` / `EVENTS_STREAM` | `true` / `platform:events:v1` | 이벤트 발행 설정 |
| `EVENTS_OUTBOX_POLL_DELAY` / `EVENTS_OUTBOX_BATCH_SIZE` | `1s` / `100` | 아웃박스 워커 주기·배치 |
| `EVENTS_OUTBOX_MAX_ATTEMPTS` / `EVENTS_OUTBOX_BASE_BACKOFF` / `EVENTS_OUTBOX_MAX_BACKOFF` | `20` / `1s` / `5m` | 발행 재시도(지수 백오프) |
| `EVENTS_OUTBOX_RETENTION` | `7d` | 발행된 아웃박스 행 보존 기간 |
| `EUREKA_URI` | `http://localhost:8761/eureka` | 로컬 서비스 등록 |
| `ALM_TRASH_RETENTION_DAYS` | `60` | 휴지통 보존 기간 — 이 기간이 지난 프로젝트를 자동으로 영구 삭제 |
| `ALM_TRASH_PURGE_CRON` / `ALM_TRASH_PURGE_ENABLED` | `0 0 3 * * *` / `true` | 자동 비우기 시각·스위치(인스턴스가 여럿이면 한 곳에서만) |
| `ORG_INTERNAL_URI` | `http://localhost:9130` | 플랫폼 메일 허브(org-service)의 내부 API 주소 |
| `ORG_INTERNAL_TOKEN` | (비어 있음) | 내부 API 토큰(`X-Internal-Token`). 비면 이메일 알림 채널이 꺼진다(알림함만 남음) |
| `ALM_PUBLIC_URL` | `http://localhost/alm` | 메일 본문 이슈 링크의 기본 주소 |
| `platform.alm.automation.enabled` | `true` | 자동화 리스너 스위치(설치 단위로 통째로 끈다) |
| `platform.alm.automation.max-depth` / `rate-per-minute` / `max-failures` | `3` / `30` / `5` | 자동화 안전장치(연쇄 깊이·규칙당 분당 실행·연속 실패) |
| `platform.alm.automation.threads` / `queue` / `async` | `2` / `500` / `true` | 자동화 실행 풀(테스트는 async=false) |
| `platform.alm.automation.schedule-polling-enabled` / `schedule-poll-delay` / `schedule-batch` | `true` / `60s` / `100` | 예약 규칙 폴러(인스턴스가 여럿이어도 회차는 한 노드만) |

## 테스트와 배포

```powershell
.\gradlew.bat test      # 컨트롤러·gRPC·Flyway 스키마 검증
.\gradlew.bat bootJar   # build/libs/app.jar
docker build -t alm-backend .
```

`schema/` 아래 테스트(`FlywaySchemaValidationTest`, 마이그레이션 백필 검증, `PostgresWritePathSmokeTest`)는
Testcontainers PostgreSQL 16 + Flyway(`ddl-auto=validate`)로 돌므로 Docker가 필요하다. 나머지 컨트롤러 테스트는
빠른 H2 + Hibernate DDL이라 PG 전용 동작(시각 null 파라미터 타입, 부분 유니크 인덱스, 재귀 CTE, 네이티브 방언)은
`PostgresWritePathSmokeTest`가 맡는다 — PG에서만 다른 경로를 고치면 거기에 한 줄을 더한다. CI의 `./gradlew build`가
둘 다 돌린다.
`Dockerfile`은 런타임 전용이며 먼저 `bootJar`를 실행해야 한다. `docker` 프로필에서는 Eureka
등록을 끄고 stdout에 ECS JSON 로그를 출력한다.

## 디렉터리 구조

```text
src/main/java/com/platform/almbackend/
├─ project/      프로젝트 REST·서비스·DTO
├─ issue/        이슈 REST·서비스·DTO — IssueService(CRUD)·IssueRankService(이동·랭크)·IssueImportService(CSV)·
│                IssueBulkService(일괄 편집)·IssueSearchService(검색·집계)·IssueLocks/IssueRankGroups(잠금·순서열)
├─ collab/       댓글·워크로그(CollaborationService)·이슈/웹 링크(IssueLinkService)·활동 기록(IssueActivityService)
├─ settings/     레지스트리(RegistryController)·스킴(SchemeController)·프로젝트 설정(ProjectSettingsController)
├─ search/aql/   AQL 렉서·파서·해석·Specification
├─ view/         저장된 보기(V31) — 필터 + 표시 옵션
├─ estimation/   추정 합계·속도 리포트
├─ triage/       트리아지 REST·생성 관문(TriageAdmission) — 처리는 issue/IssueTriageService
├─ automation/   자동화 규칙·실행기·예약 폴러(V36·V37)
├─ permission/   org-service gRPC 권한 어댑터
├─ grpc/         search-service용 AlmContentService
├─ event/        이벤트 아웃박스 + 커밋 이후 Redis Streams 발행 워커
├─ domain/       Project·Issue 엔티티와 값 타입
├─ repository/   JPA 저장소
├─ security/     JWT audience 검증
└─ common/       예외·공통 응답 처리
```
