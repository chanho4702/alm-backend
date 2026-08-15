# alm-backend 작업 규약

루트 `C:/MSA_TEMPLATE/CLAUDE.md`를 먼저 따른다.

- Java 24 / Spring Boot 4 / Gradle Wrapper를 사용한다.
- REST 정본은 PostgreSQL이며 스키마 변경은 Flyway migration으로만 한다.
- 권한은 org-service gRPC의 `PROJECT` resource로 확인한다. 장애를 권한 없음으로 오인하지 않는다.
- Redis Streams 이벤트는 커밋 이후 발행하고 본문을 싣지 않는다.
- search-service용 gRPC(:9121)는 내부망 전용이며 호스트에 공개하지 않는다.
- 프로젝트·이슈 키는 불변이고, 갱신은 `expectedVersion` 충돌을 검증한다.
- 완료 전 `gradlew test`와 `gradlew bootJar`를 통과시킨다.
