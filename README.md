# alm-backend

[![CI](https://github.com/chanho4702/alm-backend/actions/workflows/ci.yml/badge.svg)](https://github.com/chanho4702/alm-backend/actions/workflows/ci.yml)
![Java](https://img.shields.io/badge/Java-24-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-6DB33F?logo=springboot&logoColor=white)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-almdb-4169E1?logo=postgresql&logoColor=white)
![Redis Streams](https://img.shields.io/badge/Redis-Streams-DC382D?logo=redis&logoColor=white)

MSA_TEMPLATE의 **ALM 프로젝트·이슈 정본 서비스**다. PostgreSQL에 데이터를 저장하고,
org-service의 `PROJECT` grant로 요청을 인가한다. 변경 이벤트는 Redis Streams에 발행하며,
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
| 인증·인가 | auth-server RS256 JWT 검증 + org-service `PROJECT` grant |
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

## 서비스 경계

```text
gateway-server ──REST/JWT──▶ alm-backend ──JPA──▶ PostgreSQL
                                  │
                                  ├─gRPC──▶ org-service (PROJECT 권한)
                                  ├─XADD──▶ Redis Streams (커밋 이후 이벤트)
                                  └◀─gRPC── search-service (이슈 원문 조달)
```

- 권한 판정은 `VIEW < EDIT < ADMIN`이며, org-service 장애를 권한 없음으로 오인하지 않는다.
  `UNAVAILABLE`·`DEADLINE_EXCEEDED`는 REST `503`으로 응답한다.
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
| `ORG_GRPC_HOST` / `ORG_GRPC_PORT` | `localhost` / `9131` | PROJECT 권한 판정 |
| `ALM_GRPC_ENABLED` / `ALM_GRPC_PORT` | `true` / `9121` | 콘텐츠 조달 gRPC |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_DB` | `localhost` / `6379` / `0` | 이벤트 스트림 |
| `EVENTS_ENABLED` / `EVENTS_STREAM` | `true` / `platform:events:v1` | 이벤트 발행 설정 |
| `EUREKA_URI` | `http://localhost:8761/eureka` | 로컬 서비스 등록 |

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
