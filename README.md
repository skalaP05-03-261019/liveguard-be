# LIVE:GUARD Backend

Spring Boot 업무 서버의 기본 골격입니다. Java 21, Gradle Wrapper를 사용합니다.

## 로컬 실행

JDK 21이 필요합니다.

```bash
./gradlew bootRun
```

기본 포트는 `8080`입니다.

### 로컬 DB (PostgreSQL + pgvector)

Docker가 필요합니다. `.env`는 저장소에 올리지 않으므로 예시 파일을 복사해 비밀번호를 바꿉니다.

```bash
cp .env.example .env        # POSTGRES_PASSWORD 수정
docker compose up -d db     # 호스트 포트 5433 (POSTGRES_PORT로 변경 가능)
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

`local` 프로파일(`application-local.yml`)이 `.env`의 `POSTGRES_*` 값으로 DB에 접속합니다.
DB 데이터를 비우려면 `docker compose down -v`를 실행합니다.

앱이 뜨면 Flyway가 `db/migration`(스키마·테이블)과, `local`·`test` 프로파일에서만 `db/seed`(개발용 계정·채널)를 적용합니다.
시드는 운영 배포(기본 프로파일)에는 포함되지 않습니다. 모든 시드 계정의 비밀번호는 `liveguard-local`입니다.

| 계정 | 역할 | 소속 채널 |
| --- | --- | --- |
| `viewer` | VIEWER | 라이브 채널 A, B (시청자) |
| `moderator` | MODERATOR | 라이브 채널 A, B (운영자) |
| `admin` | PLATFORM_ADMIN | 없음 |

FE 연동: FE는 Vite 개발 서버 프록시(`/api` → `http://localhost:8080`)로 같은 출처에서 붙으므로 CORS는 설정하지 않습니다. 로그인 전에 `GET /api/auth/me`(401)로 `XSRF-TOKEN` 쿠키를 받고, 변경 요청에는 `X-XSRF-TOKEN` 헤더를 보냅니다.

테스트(`./gradlew test`)는 Testcontainers가 `pgvector/pgvector:pg16` 컨테이너를 직접 띄우므로
compose 없이 Docker만 실행 중이면 됩니다.

## API 문서 (Swagger)

Spring Boot 4.1에 맞는 `springdoc-openapi-starter-webmvc-ui` 3.1.1을 사용합니다.
서버 실행 후 다음 주소에서 확인할 수 있습니다.

- Swagger UI: http://localhost:8080/swagger-ui/index.html (`/swagger-ui.html`에서도 이동 가능)
- OpenAPI JSON: http://localhost:8080/v3/api-docs

`Business MCP Backend` 태그에서 Agent용 내부 REST 계약을 확인할 수 있습니다.
외부에 배포할 때 API 문서를 공개하지 않으려면
`SPRINGDOC_API_DOCS_ENABLED=false`, `SPRINGDOC_SWAGGER_UI_ENABLED=false`를 설정합니다.

## Agent Business MCP REST 계약

인접한 `../liveguard-agent/app/business_mcp/tools.py` 및 `backend.py`의 실제 호출에 맞춘
**계약 스텁**입니다. 정상 형식 요청도 HTTP `501`과
`{error:{code:"NOT_IMPLEMENTED",message:...,retryable:false},traceId:...}`를 반환합니다.
조회 결과를 꾸며내거나 제재·재심 조치를 실행하지 않습니다.
`X-Trace-Id`에는 UUID를 전달하며, 생략하면 오류 응답에 새 UUID를 생성합니다.

| Agent tool | Backend REST |
| --- | --- |
| `get_moderation_case` | `GET /internal/cases/{caseId}/context?view=moderationCase` |
| `get_chat_context` | `GET /internal/cases/{caseId}/context?view=chatContext&before=5&after=5` |
| `get_stream_info` | `GET /internal/cases/{caseId}/context?view=streamInfo` |
| `get_official_links` | `GET /internal/cases/{caseId}/context?view=officialLinks` |
| `get_security_facts` | `GET /internal/cases/{caseId}/context?view=securityFacts` |
| `get_user_history` | `GET /internal/cases/{caseId}/history` (TIMEOUT 유형별 이력, 현재 사건 제외) |
| `get_kick_eligibility` (Agent에 추가 필요) | `GET /internal/cases/{caseId}/kick-eligibility` (강퇴 후보, 최근 24시간 고정) |
| `get_action_status` | `GET /internal/actions/{actionId}` |
| `hide_message`, `timeout_user`, `release_message`, `kick_user` | `POST /internal/actions/{actionId}/execute` |
| `reconcile_review` | `POST /internal/cases/{caseId}/reviews/{decisionId}/reconcile` |

실행 요청 본문은 Agent의 `ActionCommand` JSON입니다. Agent 전용 `BusinessActionCommand`로
`KICK`, 승인 `decisionId`, 서로 다른 위반 참조 `violationIds` 3개 이상을 포함한 계약을 정의하며,
기존 업무 `ActionCommand`의 `RECONCILE_REVIEW` 계약은 변경하지 않습니다.
재심 요청 본문은 `{caseId,decisionId}`이며 경로의 값과 같아야 합니다.

기획서 v0.3과 달리 Agent는 조회를 `context?view=...`로 통합하고 `/history`를 사용합니다.
문맥 조회의 `before/after`는 각각 0~10입니다.
TIMEOUT 이력의 `days`는 호환성을 위한 선택적 양수 입력이며 기본값이나 30일 상한을
확정 정책으로 두지 않습니다. 조회 기간은 정책 합의 후 Service에서 결정해야 하고,
`UserHistory.windowHours`는 실제 조회 기간을 표현합니다. 강퇴 심사 소진은 이 이력을 삭제하지 않습니다.

강퇴 후보는 같은 사용자·채널·방송의 Backend 확정 사건을 최근 24시간 내에서 조회하며,
이번 사건을 포함하고 5개 위험 유형을 합쳐 `caseId`당 1건으로 집계합니다.
판정 취소·심사 소진 사건은 제외하지만 TIMEOUT 만료만으로는 제외하지 않습니다.
`KickEligibility`의 사건 ID는 중복될 수 없고 `violationCount`와 일치해야 합니다.
3건 이상이면 운영자 승인 요청 대상이지 자동 강퇴가 아닙니다.
대기 중에는 기준 사건을 예약하고 승인·거절 결정 시 사용한 사건을 기록해 반복 요청을 막아야 합니다.
거절 시 기존 가림을 유지하며, 승인 후 원 기준을 재검증해 현재 방송 종료까지 퇴장·재입장·채팅 금지를 적용합니다.
새 방송으로 강퇴 효력을 승계하지 않습니다. 이 처리는 아직 구현되지 않았습니다.
강퇴 후보 API의 `days` 입력은 거부합니다. Agent의 기존 이력 도구와 별도 도구로 연결해야 합니다.
내부 API 인증·권한 검사도 아직 미구현이므로 `/internal/**`를 외부에 공개하지 마세요.
실제 업무 구현 전에 서비스 토큰 검증과 권한·버전·멱등성 검증을 연결해야 합니다.

## Docker 실행

Docker 데몬이 실행 중이어야 합니다. Java 21 JDK로 실행 JAR를 빌드한 뒤,
Java 21 JRE 이미지에서 비루트 사용자로 실행하는 멀티스테이지 구성입니다.
호스트에는 JDK가 없어도 됩니다.

```bash
docker build -t liveguard-be:local .
docker run --rm --name liveguard-be -p 8080:8080 liveguard-be:local
```

앱이 기동할 때 Flyway가 DB에 접속하므로 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
`SPRING_DATASOURCE_PASSWORD`도 함께 전달해야 합니다 (위 `docker run`만으로는 기동하지 않습니다).

Swagger UI 주소는 로컬 실행과 같습니다. Spring Boot 설정은 `-e`로 전달할 수 있습니다.
로컬 환경 파일을 사용하는 경우 아래와 같이 실행합니다.

```bash
docker run --rm --name liveguard-be -p 8080:8080 --env-file .env liveguard-be:local
```

`.env`는 필요한 경우 직접 생성하며, 비밀 값은 저장소나 이미지에 포함하지 않습니다.
컨테이너에서 외부 서비스에 연결할 때 `localhost`는 호스트가 아닌 컨테이너 자신을 가리킵니다.
Docker Desktop의 호스트 서비스를 이용하려면 `host.docker.internal`을 사용하세요.

## 검증

```bash
./gradlew test bootJar
```

Docker 이미지 빌드는 테스트를 실행하지 않으므로 위 명령으로 별도 검증합니다.

MCP 서버(business-mcp, rag-mcp)는 이 저장소가 아니라 liveguard-agent(Python)에서 제공합니다.
Python Agent는 [liveguard-agent](https://github.com/skalaP05-03-261019/liveguard-agent), 화면은 [liveguard-fe](https://github.com/skalaP05-03-261019/liveguard-fe) 저장소에서 개발합니다.

## 공통 문서

[기획서·정책 v0.3](docs/live-guard/v0.3/README.md)를 공통 문서 기준으로 유지합니다.
정책·API 계약 변경은 다른 저장소 담당과 함께 조율합니다.

기존 skala-capstone-P05_03의 커밋 이력은 그대로 보존하며, 저장소 분리 커밋에서 서비스 파일만 재배치합니다.

[저장소 구성·이전 안내](docs/repository-layout.md)를 확인하세요.
