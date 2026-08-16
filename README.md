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

## 한눈에 보기

| 항목 | 내용 |
|---|---|
| 런타임 | Java 24 · Spring Boot 4.0.6 · Gradle |
| REST | `:9120` / dev `:19120` · `/api/alm/**` |
| 내부 gRPC | `:9121` / dev `:19121` · `AlmContentService` |
| 데이터 | PostgreSQL `almdb` · Flyway |
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
| `PUT` | `/api/alm/issues/{issueId}` | EDIT | 이슈 수정 |
| `DELETE` | `/api/alm/issues/{issueId}` | EDIT | 이슈 삭제 |

프로젝트 키와 이슈 키는 생성 후 바뀌지 않는다. 프로젝트·이슈 수정 요청에는
`expectedVersion`이 필요하며, 현재 버전과 다르면 `409 Conflict`를 반환한다.

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
