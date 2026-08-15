# alm-backend

MSA_TEMPLATE의 ALM 프로젝트·이슈 정본 서비스다. PostgreSQL에 프로젝트와 이슈를 저장하고,
org-service의 `PROJECT` grant로 REST 요청을 인가하며, 변경 이벤트를 Redis Streams에 발행한다.
search-service는 내부 gRPC(:9121)로 이슈 원문을 조달해 `alm-issue` 인덱스를 만든다.

## 포트와 경로

| 구분 | 기본 | dev 프로필 | 용도 |
|---|---:|---:|---|
| REST | 9120 | 19120 | `/api/alm/**` (게이트웨이 경유) |
| gRPC | 9121 | 19121 | search-service 전용, 외부 비공개 |

주요 API:

- `GET/POST /api/alm/projects`
- `GET/PUT/DELETE /api/alm/projects/{projectId}`
- `GET/POST /api/alm/projects/{projectId}/issues`
- `GET/PUT/DELETE /api/alm/issues/{issueId}`

프로젝트 키와 이슈 키는 생성 후 변경하지 않는다. 갱신 요청은 `expectedVersion`으로 낙관적
동시성 충돌을 검출한다. 삭제 이벤트는 커밋 이후 발행하며, 검색 색인은 재색인 API로 복구 가능한
파생 데이터다.

## 로컬 검증

common-proto 0.6.0 정식 발행 전에는 platform-backend에서 로컬 패키지를 먼저 만든다.

```powershell
cd C:\MSA_TEMPLATE\platform-backend
.\gradlew.bat :common-proto:publishToMavenLocal '-PprotoVersion=0.6.0-SNAPSHOT'

cd C:\MSA_TEMPLATE\alm-backend
.\gradlew.bat test '-PuseMavenLocal' '-PcommonProtoVersion=0.6.0-SNAPSHOT'
```

0.6.0 발행 후 일반 빌드는 `.\gradlew.bat build`다. 실행에는 PostgreSQL `almdb`, Redis,
auth-server JWKS, org-service gRPC가 필요하다. 컨테이너 구성은 infra-settings의
`infra/keycloak/docker-compose.yml`이 정본이다.

## 장애 의미

- org-service `UNAVAILABLE`/`DEADLINE_EXCEEDED`: REST 503
- 그 외 권한 판정 실패: fail-closed(403 또는 빈 목록)
- Redis 이벤트 발행 실패: 정본 트랜잭션은 유지하고 ERROR 로그. 관리자 재색인으로 복구
- gRPC에서 없는 이슈: `NOT_FOUND`; 고아 이슈: `FAILED_PRECONDITION`
