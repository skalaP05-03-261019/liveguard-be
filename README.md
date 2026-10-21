# LIVE:GUARD Backend

V200 이전 메모리 후보 ID가 남은 로컬 DB를 업그레이드할 때는 [기존 후보 연결 보존 절차](docs/legacy-candidate-upgrade.md)를 먼저 확인한다. 신규 DB에는 필요하지 않으며, 기존 DB를 초기화하는 절차가 아니다.

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

기준은 [2026-10-20 CONTRACTS](https://geode-phosphorus-891.notion.site/CONTRACTS-f8e61f4176b5829c9e1c8157b8124676)와
[POLICY](https://geode-phosphorus-891.notion.site/POLICY-e3661f4176b582ab920d01ba690418b7)입니다.
REST 경로·필드는 이 저장소의 [OpenAPI](contracts/openapi.yaml)를 사용합니다.
모든 내부 요청은 `X-Service-Token`이 필요합니다. BE의 `liveguard.internal.service-token`
(`INTERNAL_SERVICE_TOKEN`으로 주입하거나 `LIVEGUARD_INTERNAL_SERVICE_TOKEN`으로 직접 설정)과 맞추며,
미설정·누락·오류 시 `403 FORBIDDEN`입니다.
브라우저 세션이나 `Authorization: Bearer ...`로 대체할 수 없으며 CSRF 토큰은 필요하지 않습니다.
`X-Trace-Id`가 유효한 UUID면 정규화해 유지하고, 없거나 잘못된 값이면 새로 생성합니다.
오류·성공 본문의 `traceId`와 응답 헤더를 일치시킵니다.

| Agent tool | 표준 Backend REST | 현재 상태 |
| --- | --- | --- |
| `get_moderation_case` | `GET /internal/cases/{caseId}` | DB 조회, 필수 정보 미준비 시 409 |
| `get_chat_context` | `GET /internal/cases/{caseId}/context?before=5&after=5` | 실제 DB 조회·마스킹 |
| `get_stream_info`, `get_official_links` | `GET /internal/cases/{caseId}/channel-info` | 실제 채널 역할·공식 호스트·등록 이벤트 조회 |
| `get_channel_user_role` | `GET /internal/cases/{caseId}/role` | 실제 채널 정보 조회 재사용 |
| `get_security_facts` | `GET /internal/cases/{caseId}/security-facts` | 501 스텁, 출처 기반 TRUE/FALSE/UNKNOWN 계약 |
| `get_user_history` | `GET /internal/cases/{caseId}/user-history` | 명시한 `days`로 DB 조회, 기본 기간 미확정 시 501 |
| `get_kick_eligibility` | `GET /internal/cases/{caseId}/kick-eligibility` | 501 스텁, 강퇴 신규 후보 |
| `get_kick_review_basis` | `GET /internal/kick-reviews/{reviewId}/basis` | 501 스텁, 원 승인 기준·소진 소유권 |
| `get_action_status` | `GET /internal/actions/{actionId}` | 501 스텁 |
| `hide_message`, `timeout_user`, `release_message`, `kick_user` | `POST /internal/actions/{actionId}/execute` | 501 스텁 |
| `reconcile_review` (폐기 예정) | `POST /internal/cases/{caseId}/reviews/{decisionId}/reconcile` | 501 레거시 스텁 |

스텁은 인증·입력 검증 후 `501 {error:{code:"NOT_IMPLEMENTED",message:...,retryable:false},traceId:...}`를 반환하며
성공 데이터나 실제 조치를 생성하지 않습니다. 잘못된 입력은 `400 VALIDATION_FAILED`,
구현된 조회의 사건 메시지가 없으면 `404 NOT_FOUND`입니다. `/internal/**`는 외부에 공개하지 않습니다.
서비스 인증은 구현됐지만 실제 조치의 업무 권한·버전·멱등성 검증은 후속 작업입니다.

### 사건·판정 근거 영속화 (BE2 #13)

- V200이 `guard.cases`, 후보 입력, 사건별 증거/대상 메시지, 유형별 finding과 감사 이벤트를 생성하고
  `platform.messages.case_id`에 FK를 추가합니다. 적용 전 기존 `case_id`가 임시 UUID인 데이터는
  별도로 정리해야 합니다. 마이그레이션은 가짜 사건이나 확정 이력을 자동 생성하지 않습니다.
- 기본 `CandidateSink`는 JDBC 구현이며 메시지 저장 트랜잭션에 참여합니다.
  후보 메시지 ID로 중복 생성을 막고, FLOOD는 최근 관련 메시지가 소속된 판정 전 사건을 재사용합니다.
  관련 증거와 실제 조치 대상은 구분하며, 이미 다른 사건에 소속된 메시지의 소속은 바꾸지 않습니다.
- 새 후보가 추가되면 증거 버전과 스냅샷 ID가 바뀝니다. AI 제안은 확정 집계에 포함하지 않으며
  Backend 확정은 명시적인 정책 버전·근거 참조·결정 참조와 최신 증거 버전을 요구합니다.
  확정 권한/정책 검증은 후속 판단 서비스 책임이며, 저장 서비스는 Agent REST로 노출하지 않습니다.
- 여러 유형의 확정 finding이 있어도 사건은 한 번만 집계합니다. 취소는 finding과 기존 근거를
  삭제하지 않고 취소 감사 이벤트를 추가합니다. TIMEOUT 만료는 취소가 아닙니다.
- BE1이 아직 제공하지 않는 방송 ID·활성 정책 버전·처리 경로는 DB에서 미제공 상태로 보존합니다.
  완성된 내부 조회 DTO를 임의 값으로 채우지 않습니다.
  확정 이력 조회는 저장 계층용이며 강퇴 심사용 방송 범위·예약·소진 조건은 후속 이슈에서 추가합니다.

### 사건·이력 조회 기반 (03-1)

- `CaseQueryRepository`는 사건 메타데이터, 후보별 증거 버전·스냅샷, 증거/대상 연결,
  유형별 finding과 감사 이벤트를 읽습니다. 원문을 포함하는 저장 계층 조회는 REST로 노출하지 않습니다.
- 사건 API는 REPEATABLE READ 트랜잭션에서 현재 증거와 대상의 현재 가시성·버전을 읽고,
  기존 `PiiMasker`와 사건 내 가명을 적용합니다. 사건이 없으면 `404 NOT_FOUND`,
  방송·정책·처리 경로 또는 현재 증거/대상이 없으면 `409 CASE_NOT_READY`, `retryable=false`입니다.
  미준비는 재시도만으로 해결됨을 보장하지 않으며 BE1 정보 제공 또는 계약 설정이 필요합니다.
- `targets`가 정본입니다. Agent 호환 필드 `targetMessageIds`, `targetVersions`는 이 목록에서
  파생하므로 독립적으로 수정하거나 별도 입력으로 받지 않습니다.
  주변 문맥 API는 아직 `snapshotId`·`evidenceVersion`·메시지 가시성을 제공하지 않습니다.
  Agent 사건 로더의 사건/문맥 버전 일치 검증까지 연결하는 작업은 BE1과의 후속 협업 범위입니다.
  현재 응답은 불변의 과거 문맥 스냅샷이나 Agent E2E 준비 완료를 보장하지 않습니다.
- TIMEOUT 이력은 명시한 `days`로 서버 시각 기준 기간을 선택합니다. 기존 `/history`도 같은 조회를 사용합니다.
  사건 발생 시각이 `[since, asOf]`에 포함되고 기준 시각까지 확정된, 현재 취소되지 않은 Backend 위반만 조회합니다.
  현재 사건은 제외하며 `violationCount`는 서로 다른 사건 수, `recentCases`는 사건·유형별 한 행입니다.
  서비스의 `timeoutHistory`는 명시적인 시작/종료 시각과 유형별 건수도 제공합니다.
  이는 현재 취소 상태를 반영하는 조회이며 과거 기준 시각의 상태를 재생하는 감사 조회가 아닙니다.
- `days` 생략 시 기존 사건은 기본 기간 미확정으로 `501 NOT_IMPLEMENTED`를 반환합니다.
  30일 기본값·상한은 없습니다. `days * 24`가 응답의 정수 `windowHours` 범위를 넘으면
  정책상 기간 제한이 아니라 표현 범위 오류로 `400 VALIDATION_FAILED`를 반환합니다.
- 강퇴 기초 조회 `kickBasis`는 명시한 사용자·채널·방송과 기준 시각을 받고, 최근 24시간의
  5개 유형을 합친 확정 위반을 사건 단위로 반환합니다. 현재 확정 사건도 포함하며 방송 ID 누락은 거부합니다.
  이 단계의 포함 경계는 저장 계층의 `[asOf - 24h, asOf]`이며 최종 심사 정책의 확정을 대신하지 않습니다.
  예약·소진 제외가 미연동이므로 `eligible`을 제공하지 않고 `/kick-eligibility`도 계속 501입니다.
  TIMEOUT 만료나 강퇴 소진은 판정 취소가 아니며 TIMEOUT 이력을 지우지 않습니다.

### 조회 계약과 남은 정책

- 문맥: `before/after` 각각 0~10, 생략 시 각각 10. 사건 메시지를 우선하는 응답 전체 최대 20건입니다.
- 채널 정보는 방송 활성 상태·종료 시각이나 개인정보 실체를 보장하지 않습니다.
  공식 호스트라는 이유로 링크 전송 금지를 면제하지 않습니다.
- 역할은 현재 채널 역할입니다. BE1 조회는 탈퇴한 작성자를 VIEWER로 반환하므로 과거 역할·본인 확인 결과로 쓰지 않습니다.
- `SecurityFacts`의 TRUE/FALSE에는 검증 출처가 필요하고 확인 불가는 UNKNOWN입니다.
  채널 정보를 개인정보 검증 결과로 바꿔 반환하지 않습니다.
- TIMEOUT 이력은 현재 사건을 제외하며 강퇴 소진 후에도 유효한 과거 이력을 유지합니다.
  `days`는 선택적 양수 요청값이며 기본값·30일 상한을 확정하지 않습니다.
  조회 기간은 TBD-03 합의 후 정하고, 응답 `windowHours`는 실제 조회 기간을 표현합니다.
- 강퇴 후보는 같은 사용자·채널·방송의 Backend 확정 사건 중 최근 24시간, 현재 사건 포함,
  5개 위험 유형 통합 `distinct caseId`입니다. 취소·소진은 제외하지만 TIMEOUT 만료는 제외 사유가 아닙니다.
  3건 이상은 심사 요청 조건이지 승인이나 실행 허가가 아닙니다. 기간 변경용 `days`는 거부합니다.
- 승인 심사는 기준 사건을 고정·예약하고 승인/거절 모두 소진을 기록해야 합니다.
  `KickReviewBasis`는 원 승인 사건과 소진의 `reviewId/decisionId`를 제공하며 신규 후보 조회와 다릅니다.
  자기 승인에 따른 소진은 재검증 때 허용하지만 타 심사 소진·취소 등은 검증해야 합니다.
- `InternalCase`는 `liveId`, `snapshotId`, `evidenceVersion`, `policyVersion`, `handlingRoute`,
  대상 메시지의 가시성·버전(`targets`)을 정의합니다. 후보의 영속 스냅샷과 현재 대상 조회는 연결됐지만
  불변의 주변 문맥 스냅샷 및 BE1 메타데이터 제공은 후속 작업입니다.
  방송 생명주기, 유형별 이력 기간, 기준 시각/경계, 심사 3건 초과 선택, 승인 유효기간,
  TIMEOUT 중첩, 재시도 오류 코드·간격은 합의 전 임의 구현하지 않습니다.
  활성 `policyVersion`은 아직 승인된 값이 없으며 예제·테스트 값은 배포 버전이 아닙니다.

### 실행·결정 계약

`BusinessActionCommand`는 Backend 발급 계획만 전달합니다. 공통 `liveId`가 필요하고,
KICK에는 `reviewId`, `decisionId`, `basisSnapshotId`, `validationRef`, 서로 다른 `basisCaseIds` 3개 이상이 필요합니다.
입력 형식 검증은 승인 검증을 대체하지 않습니다. BE는 저장된 승인·고정 기준·버전·방송 상태를 실행 직전에 재검증해야 합니다.
`durationSeconds`는 TIMEOUT에만 사용하고 KICK에는 사용하지 않습니다. KICK은 현재 방송 종료까지 적용합니다.
RELEASE_HELD는 문맥의 저장된 정상 결정을 참조할 수 있으며 다른 유효 HOLD/HIDE가 있으면 공개할 수 없습니다.

`ActionExecutionResult`는 `status`(PENDING/RUNNING/SUCCEEDED/FAILED)와
`observation`(CONFIRMED/UNKNOWN), nullable `applied`, 영속 `attemptCount/retryCount`를 구분합니다.
UNKNOWN은 `applied=null`이며 성공·실패로 확정하지 않습니다. KICK의 최초 포함 최대 시도는 3회이고,
응답 유실 시 새 조치를 만들지 말고 동일 action의 상태를 확인합니다. KICK에 고정 만료 시각을 사용하지 않습니다.

`reconcile_review`는 기존 호환 계약일 뿐 새 CONTEXT/KICK 결정·Agent resume API가 아닙니다.
본문 `{caseId,decisionId}`는 경로와 같아야 하며 자동 강퇴 승인·소진 취소·HIDE/TIMEOUT 복구를 하지 않습니다.
문맥 AI 제안은 운영자의 정상/위반/보류 결정 전 HELD를 유지해야 합니다.
강퇴 승인은 별도이며 거절·실패가 기존 가림이나 제한을 해제하지 않습니다.
OpenAPI에 남은 판정·구형 강퇴 실행 API는 후속 구현 계획이며 현재 제공되는 API로 오해하지 않도록 구분합니다.

### Agent 저장소에 필요한 후속 변경

이번 변경은 BE 저장소에 한정되므로 인접 `liveguard-agent` 어댑터는 별도 수정해야 합니다.

1. 인증 헤더를 `Authorization`에서 `X-Service-Token`으로 변경합니다.
2. `context?view=moderationCase`는 `/cases/{caseId}`로,
   `view=streamInfo/officialLinks`는 `/channel-info`로, `view=securityFacts`는 `/security-facts`로 변경합니다.
   `/context`는 문맥 전용이며 이전 `view=chatContext`만 호환합니다. 다른 view는 400으로 거부합니다.
3. `/history`는 폐기 예정 별칭입니다. `/user-history`로 전환하고 고정 `days=30` 정책을 제거합니다.
4. `get_kick_eligibility`, `get_kick_review_basis`, `get_channel_user_role`을 등록합니다.
5. 실행 명령의 `violationIds`를 `basisCaseIds`로 바꾸고 `liveId`와 KICK 승인·검증 참조를 연결합니다.
   응답의 UNKNOWN·실제 적용 여부와 지속 재시도 예산을 처리합니다.
6. RAG의 `search_policy/get_policy_section`은 별도 서버 책임이며 BE와 정책 버전을 맞춥니다.

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

정책 원본은 [docs/policy/](docs/policy/README.md)(v3.1)입니다. [기획서·정책 v0.3](docs/live-guard/v0.3/README.md)은 이전 기획으로 구현 기준이 아니며, 평가 사례만 계속 사용합니다.
정책·API 계약 변경은 다른 저장소 담당과 함께 조율합니다.

기존 skala-capstone-P05_03의 커밋 이력은 그대로 보존하며, 저장소 분리 커밋에서 서비스 파일만 재배치합니다.

[저장소 구성·이전 안내](docs/repository-layout.md)를 확인하세요.
