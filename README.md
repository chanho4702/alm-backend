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

JDK 24와 `common-proto` 패키지가 필요하다. 정식 `0.6.0` 패키지를 사용할 때는
`GITHUB_TOKEN`에 GitHub Packages `read:packages` 권한을 설정한다.

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

현재 checkout의 `0.6.0`이 아직 발행되지 않았거나 proto 변경을 먼저 검증할 때는 Maven Local을 사용한다.

```powershell
cd ..\platform-backend
.\gradlew.bat :common-proto:publishToMavenLocal '-PprotoVersion=0.6.0-SNAPSHOT'

cd ..\alm-backend
.\gradlew.bat test '-PuseMavenLocal' '-PcommonProtoVersion=0.6.0-SNAPSHOT'
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
| `DELETE` | `/api/alm/projects/{projectId}` | ADMIN | 프로젝트·하위 이슈 삭제 |
| `GET` | `/api/alm/projects/{projectId}/issues` | VIEW | 이슈 목록 |
| `POST` | `/api/alm/projects/{projectId}/issues` | EDIT | 이슈 생성 |
| `GET` | `/api/alm/issues/{issueId}` | VIEW | 이슈 조회 |
| `PUT` | `/api/alm/issues/{issueId}` | EDIT | 이슈 수정 — `details.resolution`(V6), `details.fixVersionId`(같은 프로젝트·보관 아님; V7) |
| `POST` | `/api/alm/issues/{issueId}/move` | EDIT | 보드 컬럼 이동·순서 변경 |
| `POST` | `/api/alm/issues/{issueId}/rank` | EDIT | 백로그/스프린트 랭크 이동 |
| `DELETE` | `/api/alm/issues/{issueId}` | EDIT | 이슈 삭제 |
| `GET` | `/api/alm/projects/{projectId}/versions` | VIEW | 버전 목록 |
| `POST` | `/api/alm/projects/{projectId}/versions` | EDIT | 버전 생성(이름은 프로젝트 안에서 유일, 중복 409) |
| `PUT` | `/api/alm/versions/{versionId}` | EDIT | 버전 수정(`expectedVersion`, 날짜 역전 400) |
| `POST` | `/api/alm/versions/{versionId}/release` | EDIT | 릴리스 — `doneStatuses`·`moveUnresolvedToVersionId`로 미완료 이관(선택) |
| `POST` | `/api/alm/versions/{versionId}/archive` | EDIT | 보관 |
| `DELETE` | `/api/alm/versions/{versionId}` | EDIT | 삭제(달린 이슈의 `fixVersionId`를 비운다) |
| `POST` | `/api/alm/issues/{issueId}/attachments` | EDIT | 첨부 올리기(multipart `file`, 최대 `ALM_MAX_ATTACHMENT_MB`=20) |
| `GET` | `/api/alm/issues/{issueId}/attachments` | VIEW | 첨부 목록 |
| `GET` | `/api/alm/attachments/{id}` | VIEW | 내려받기(attachment 처분, nosniff) |
| `GET` | `/api/alm/attachments/{id}/inline` | VIEW | 인라인(래스터 이미지만) |
| `DELETE` | `/api/alm/attachments/{id}` | EDIT | 삭제(오브젝트는 커밋 뒤 정리) |
| `GET` | `/api/alm/projects/{projectId}/changes` | VIEW | 변경 이력(리포트 원천) — `field`·`sprintId`·`since` 필터 |
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
돌아가며, **무엇을 완료로 볼지는 요청이 알려준다**:

```json
POST /api/alm/sprints/{sprintId}/complete
{ "doneStatuses": ["done", "released"] }
```

상태 카테고리를 정하는 워크플로 스킴이 아직 프론트 소유이기 때문이다. 목록이 비어 있으면 그
스프린트의 모든 이슈가 백로그로 돌아간다. 스킴이 서버로 넘어오면 이 필드는 선택값이 된다.

순서는 두 종류의 그룹으로 관리한다 — 보드 컬럼(프로젝트+스프린트+상태)과 랭크 그룹
(프로젝트+스프린트, 상태 무관)이다. `move`는 컬럼 안에서, `rank`는 랭크 그룹 안에서 `beforeId`
앞에 놓고 그룹 전체를 1..n으로 다시 매긴다. 떠난 그룹도 함께 조밀해진다. `beforeId`가 대상 그룹에
없으면 오류가 아니라 맨 뒤다 — 드래그 도중 다른 사용자가 그 이슈를 옮겼을 수 있고, 화면은 이동 후
항상 재조회한다. 두 연산 모두 프로젝트 행을 잠그고 한 트랜잭션에서 끝내며, 순서는 사용자가 편집
폼에서 보고 있던 값이 아니므로 `version`을 올리지 않는다(드래그가 남의 저장을 409로 만들지 않는다).

계층은 에픽→일반 이슈(작업·스토리·버그)→하위 작업의 2단계만 허용한다. 부모는 같은
프로젝트에 있어야 하며, 부모 삭제 시 자식의 `parentId`는 해제된다. `order`는 응답에만
포함되는 서버 관리 값이며 생성 시 프로젝트 내 다음 번호로 발급한다. 재정렬은 별도 API로
제공하기 전까지 일반 수정 요청으로 바꿀 수 없다.

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
  | 400 | 요청 검증 실패 | 요청 본문(`@RequestBody`·`MultipartFile`)이 있는 것 | 47 |
  | 401 | 인증 실패 — 토큰 없음·만료·무효 | 전부 | 122 |
  | 403 | 권한 없음 | 전부 | 122 |
  | 404 | 대상 없음 | 경로 변수가 있는 것 | 92 |
  | 409 | 버전 충돌 — expectedVersion 불일치 | `expectedVersion`을 받는 PUT | 4 |
  | 503 | 권한 서비스(org) 불능 | org gRPC로 권한을 판정하는 것 | 100 |

  **400은 본문을 받는 엔드포인트를 기준으로 붙인 것이지, 400이 날 수 있는 모든 경로를 빠짐없이 적은 목록이 아니다.**
  읽는 사람이 본문을 잘못 보내 실제로 400을 만들 수 있는 자리를 표시한 것으로 읽어야 한다 — 예를 들어
  잘못된 쿼리 파라미터 타입도 400이 되지만 문서에는 적지 않는다.

  503은 `GrpcPermissionClient`가 org 불능(UNAVAILABLE·DEADLINE_EXCEEDED)에서 던지는
  `ServiceUnavailableException`이다. alm은 프로젝트 권한이든 전역 관리자 판정이든 같은 클라이언트를 타므로
  503이 기본이고, org를 부르지 않는 22개(대시보드·개인 설정·배너 읽기·내 알림·레지스트리 읽기)만
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
  개인 설정의 주소 스냅샷으로 폴백하고, 그것도 없으면 그 한 통을 생략한다.
- 이벤트에는 이슈 본문을 싣지 않는다. 정본 트랜잭션 커밋 후 발행하며, 발행 실패가 정본을
  롤백하지는 않는다. 검색 색인은 관리자 재색인으로 복구한다.
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
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` | `localhost` / `6379` / `0` | 이벤트 스트림 |
| `EVENTS_ENABLED` / `EVENTS_STREAM` | `true` / `platform:events:v1` | 이벤트 발행 설정 |
| `EUREKA_URI` | `http://localhost:8761/eureka` | 로컬 서비스 등록 |
| `ALM_TRASH_RETENTION_DAYS` | `60` | 휴지통 보존 기간 — 이 기간이 지난 프로젝트를 자동으로 영구 삭제 |
| `ALM_TRASH_PURGE_CRON` / `ALM_TRASH_PURGE_ENABLED` | `0 0 3 * * *` / `true` | 자동 비우기 시각·스위치(인스턴스가 여럿이면 한 곳에서만) |
| `ALM_MAIL_HOST` | (비어 있음) | SMTP 호스트. 비면 이메일 알림 채널이 꺼진다(알림함만 남음) |
| `ALM_MAIL_PORT` / `ALM_MAIL_USERNAME` / `ALM_MAIL_PASSWORD` | `587` / (비어 있음) / (비어 있음) | SMTP 접속 |
| `ALM_MAIL_SMTP_AUTH` / `ALM_MAIL_STARTTLS` | `true` / `true` | SMTP 인증·STARTTLS |
| `ALM_MAIL_FROM` | `alm@localhost` | 알림 메일의 보낸 사람 |
| `ALM_PUBLIC_URL` | `http://localhost/alm` | 메일 본문 이슈 링크의 기본 주소 |

## 테스트와 배포

```powershell
.\gradlew.bat test      # 컨트롤러·gRPC·Flyway 스키마 검증
.\gradlew.bat bootJar   # build/libs/app.jar
docker build -t alm-backend .
```

`FlywaySchemaValidationTest`는 Testcontainers PostgreSQL을 사용하므로 Docker가 필요하다.
`Dockerfile`은 런타임 전용이며 먼저 `bootJar`를 실행해야 한다. `docker` 프로필에서는 Eureka
등록을 끄고 stdout에 ECS JSON 로그를 출력한다.

## 디렉터리 구조

```text
src/main/java/com/platform/almbackend/
├─ project/      프로젝트 REST·서비스·DTO
├─ issue/        이슈 REST·서비스·DTO
├─ permission/   org-service gRPC 권한 어댑터
├─ grpc/         search-service용 AlmContentService
├─ event/        커밋 이후 Redis Streams 발행
├─ domain/       Project·Issue 엔티티와 값 타입
├─ repository/   JPA 저장소
├─ security/     JWT audience 검증
└─ common/       예외·공통 응답 처리
```
