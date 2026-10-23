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

## Agent 작업 발송 Outbox

기본 경로는 **DB Outbox → HTTP Gateway → Agent 영속 Inbox**입니다.
선택적으로 **DB Outbox → RabbitMQ 알림 → BE HTTP Worker → Agent 영속 Inbox**로 분산합니다.
채팅 후보의 조사와 저장된 사람 결정의 재개는 같은 업무 트랜잭션에서 자동 enqueue합니다.
Agent가 broker를 직접 소비하지는 않습니다. 외부 Agent/RAG 배포와 실제 모델 동작은 별도 검증 대상입니다.
V201은 발송 메타데이터와 불변 envelope만 저장하고 채팅 원문·서비스 토큰은 저장하지 않습니다.

### 업무 코드 연결

`AgentTaskOutbox.enqueue(AgentTaskEnvelope)`는 기존 업무 트랜잭션 안에서만 호출할 수 있습니다
(`Propagation.MANDATORY`). 사건/결정 저장이 롤백되면 작업도 롤백됩니다.
네트워크 호출은 커밋 이후 독립 Worker에서 수행하며 업무 트랜잭션 안에서는 실행을 거부합니다.
호출자가 실제 사건의 준비 상태, snapshot·증거 버전, 저장된 review·decision 권한을 검증해야 합니다.
필수 필드가 있다는 사실은 승인을 의미하지 않으며, 이 서비스는 외부 REST로 노출하지 않습니다.

- `ANALYZE`/`REANALYZE`: `/agent/tasks/{taskId}/run`
- `RESUME_REVIEW`: `/agent/tasks/{taskId}/resume`, `reviewKind`·`reviewId`·`decisionId` 필수
- `RECONCILE`: 지원하지 않으며 enqueue 계약에서 거부
- Agent와 동일하게 run의 논리 키는 `run:{taskId}`, resume은 `resume:{decisionId}`입니다.
  resume은 기존 task/thread/snapshot 바인딩을 유지합니다. 독립 재조사는 Agent 계약에 맞는 새 task/thread를 사용합니다.
- 신규 ANALYZE·REANALYZE·RESUME_REVIEW는 사건에 저장된 실제 `liveId`를 전달합니다.
  enqueue 시 방송·채널·사건·task/thread·증거 바인딩을 검증하며, Agent는 이를 방송별 공정 배분에 사용합니다.
  `liveId`가 없던 기존 Outbox는 해당 필드를 추가하거나 null로 직렬화하지 않고 원래 JSON 필드 구성을 유지합니다.
  기존 작업의 방송을 추정·backfill하지 않으며, 같은 operation에 `liveId`를 덧붙여 enqueue하면 충돌입니다.
- 동일 논리 키와 **동일 전체 payload**는 기존 Outbox ID를 반환합니다.
  eventId·traceId를 포함해 payload를 바꾸거나 eventId를 다른 작업에 재사용하면 `IDEMPOTENCY_CONFLICT`입니다.
  발송 재시도에서는 taskId·eventId·traceId·payload 모두 유지합니다.

### 설정

기본은 비활성화입니다. 로컬 `.env` 또는 배포 환경으로 설정하며 실제 토큰은 저장소에 넣지 않습니다.
Agent와 `AGENT_SERVICE_TOKEN`을 맞추고, BE의 내부 조회 인증용 `INTERNAL_SERVICE_TOKEN`과 혼동하지 않습니다.
Agent URL은 인증정보·경로·query·fragment 없는 HTTP(S) origin이어야 하며 리다이렉트는 따라가지 않습니다.
Compose에서는 `localhost` 대신 해당 네트워크의 Agent 서비스 주소를 사용합니다.

| 설정 (`liveguard.agent.delivery.*`) | 기본값 | 의미 |
| --- | --- | --- |
| `enabled` / `AGENT_DELIVERY_ENABLED` | `false` | 자동 발송 활성화 |
| `transport` / `AGENT_DELIVERY_TRANSPORT` | `db` | `db` polling 또는 `rabbit` 알림 분산 (동시 활성화 불가) |
| `base-url` / `AGENT_BASE_URL` | 미설정 | Agent HTTP origin |
| `service-token` / `AGENT_SERVICE_TOKEN` | 미설정 | Bearer 인증 |
| `connect-timeout` | `3s` | 연결 제한 |
| `request-timeout` | `10s` | 응답 본문까지 포함한 요청 제한 |
| `lease-duration` | `60s` | 선점 유효기간, 두 HTTP 요청의 제한 합계보다 길어야 함 |
| `poll-interval` | `1s` | 선점 주기 |
| `batch-size` | `10` | poll당 제출 상한 |
| `concurrency` | `2` | 인스턴스별 동시 처리 상한 |
| `max-attempts` | `5` | 발송/복구 사이클 예산 (KICK 실행 예산과 별개) |
| `initial-backoff` / `max-backoff` | `2s` / `5m` | 지수 backoff와 상한 |

나머지 설정도 Spring 환경변수 규칙으로 지정할 수 있습니다
(예: `LIVEGUARD_AGENT_DELIVERY_CONCURRENCY=4`).
활성화했는데 URL·인증 정보가 없거나 제한값이 잘못되면 시작을 실패시킵니다.
응답은 최대 64 KiB로 제한합니다. Worker 종료 시 진행 중 요청을 기다리고,
미완료 작업은 lease 만료 후 같은 작업으로 복구합니다.

### 상태 및 장애 복구

| 상태 | 의미 |
| --- | --- |
| `PENDING` | 커밋된 발송 대기 |
| `IN_FLIGHT` | lease로 선점한 발송/상태 확인 |
| `RETRY` | 다음 시도 시각까지 대기 |
| `ACCEPTED` | Agent Inbox의 영속 접수 확인. 조사·조치 성공을 의미하지 않음 |
| `FAILED` | 확인된 전달 거부 또는 확인된 거부의 예산 소진 |
| `UNKNOWN` | 접수 여부 미확인. 확정 실패로 표시하지 않으며 자동 재시도 중지 |

`FOR UPDATE SKIP LOCKED`로 선점하고 lease token으로 완료 기록을 보호합니다.
네트워크 대기 중 DB 트랜잭션을 유지하지 않습니다.
`delivery_uncertain=true`인 재시도·만료 HTTP lease 복구에서만 같은 taskId 상태를 먼저 조회하며
taskId·threadId·operationId·상태가 일치하면 접수 완료로 처리합니다.
resume 상태 조회가 이전 run/resume을 가리키면 현재 결정의 접수로 간주하지 않습니다.
재전달 `200`은 `duplicate=true`, 신규 `202`는 `duplicate=false`와 식별자를 검증합니다.

전달 재시도 대상은 연결/응답 오류 및 HTTP 408·429·500·502·503·504입니다.
인증·요청·계약 충돌·리다이렉트 등은 자동 반복하지 않습니다.
잘못된 접수 응답은 접수 미확인으로 기록합니다. 이전 시도의 불확실성은 후속 거부만으로 지우지 않습니다.
예산 소진 뒤 만료 lease를 복구하는 추가 사이클은 **상태 조회만** 가능하며 POST를 추가하지 않습니다.
따라서 장애 복구 조회 때문에 `attempt_count`가 `max-attempts`를 넘을 수 있지만 발송 예산은 초기화하지 않습니다.
로그에는 outbox ID·안전한 오류 코드·횟수만 남기고 토큰·전체 payload·예외 본문을 남기지 않습니다.

### RabbitMQ 선택 모드

로컬 `.env`의 Rabbit 비밀번호를 변경하고 `AGENT_DELIVERY_ENABLED=true`,
`AGENT_DELIVERY_TRANSPORT=rabbit`, Agent URL/토큰을 설정합니다.

```bash
docker compose --profile rabbit up -d db rabbitmq
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

호스트 Rabbit 포트는 기본 `5673`입니다. 컨테이너 배포에서는 같은 네트워크의
`RABBITMQ_HOST=rabbitmq`, `RABBITMQ_PORT=5672`를 사용합니다.
연결은 `RABBITMQ_HOST/PORT/USER/PASSWORD` 또는 표준 `spring.rabbitmq.*`
(TLS/vhost 포함) 설정을 사용합니다. `enabled=false` 또는 기본 `db` 모드는 broker 없이 실행됩니다.
Rabbit 장애 시 HTTP DB polling으로 조용히 전환하지 않습니다.

`liveguard.agent.delivery.rabbit.*` 설정:

| 설정 | 기본값 | 의미 |
| --- | --- | --- |
| `exchange` / `queue` / `routing-key` | `liveguard.agent.outbox` / `liveguard.agent.outbox` / `outbox` | durable direct topology |
| `confirm-timeout` | `5s` | correlated confirm 대기 상한 |
| `publish-lease` | `30s` | confirm 제한보다 긴 별도 publisher lease |
| `retry-interval` | `5s` | NACK·return·timeout·연결 실패 후 재알림 |
| `recovery-interval` | `60s` | confirm 후에도 미완료 DB 작업을 다시 알리는 주기 |
| `prefetch` | `1` | consumer당 상한 (1~100), consumer 수는 delivery `concurrency` |

V202는 `notification_after`, `publish_token`, `publish_lease_until`만 추가합니다.
publisher는 **HTTP 시도 가능 시각이 된 행 또는 만료 HTTP lease**만 선점합니다.
네트워크 밖에서 DB lease를 커밋한 뒤 UUID 36바이트만 persistent 메시지로 발행하고
mandatory return과 correlated ACK를 모두 확인합니다. broker confirm은 `ACCEPTED`가 아닙니다.
publisher lease 만료/교체 및 HTTP 선점은 이전 publisher 완료 쓰기를 fence합니다.
confirm 유실·NACK·unroutable·broker 재시작·publisher crash·큐 알림 유실은
publish lease/retry/recovery 주기로 다시 발행합니다. DB가 유일한 작업 원장입니다.

consumer는 UUID로 DB 행 하나만 선점하고 기존 HTTP Worker를 사용합니다.
중복·선점 경합·미도래·종료 행은 HTTP 시도 횟수를 쓰지 않고 ACK합니다.
실제 처리 결과는 DB 커밋 후 ACK합니다. DB 저장/lease 소유권 오류는 ACK하지 않고
알림만 reject(no requeue)하며 DB 스캔이 복구합니다.
잘못된 UUID도 reject(no requeue, 기본적으로 폐기)하여 poison 무한 루프를 막습니다.
네트워크 중 DB 트랜잭션은 유지하지 않습니다. 전송은 at-least-once이므로
동일 envelope와 Agent Inbox 멱등성 계약은 계속 필수입니다.

broker 알림의 불확실성은 `delivery_uncertain`이나 HTTP attempt 예산에 영향을 주지 않습니다.
HTTP consumer가 죽고 lease가 만료된 경우에만 HTTP 불확실성이 생겨 상태 GET으로 복구합니다.
확인된 HTTP 429 재시도는 GET 없이 같은 POST를 수행합니다.
broker만 확인된 작업은 DB에 남으며 HTTP 진행/복구 상태를 `ACCEPTED/FAILED/UNKNOWN`으로 구분합니다.
publisher는 poll당 `batch-size`개를 순차 처리하고 consumer는 고정 concurrency/prefetch를 사용합니다.

Rabbit 테스트는 기존 PostgreSQL Testcontainers 외에 `rabbitmq:4.1-alpine`을 사용합니다:

```bash
./gradlew test --tests '*AgentOutboxTest' --tests '*AgentDeliveryConfigurationTest' \
  --tests '*AgentRabbit*' --tests '*FlywayMigrationTest'
```

운영 상태는 아래처럼 원문·토큰 없는 메타데이터로 확인합니다.

```sql
SELECT id, operation_id, task_id, status, attempt_count, next_attempt_at,
       lease_until, delivery_uncertain, last_error_code, updated_at,
       notification_after, publish_token, publish_lease_until
FROM guard.agent_task_outbox
ORDER BY created_at, id;
```

- 일시 중단은 `enabled=false` 후 재시작합니다. DB 작업·시도 횟수는 유지됩니다.
- 장애가 난 `IN_FLIGHT`는 lease 만료 후 자동 복구합니다. 식별자·payload를 새로 만들지 않습니다.
- `FAILED`는 오류 코드에 맞춰 인증·주소·계약 등을 먼저 수정합니다.
- `UNKNOWN`은 Agent 상태 API의 동일 operationId와 Agent 영속 Inbox를 확인합니다.
  taskId 조회는 해당 thread의 최신 작업만 반환하므로 이전 작업이 가려졌다면 Inbox 확인이 필요합니다.
  taskId/threadId만 같은 다른 operation을 접수 증거로 사용하지 않습니다.
- 이번 단계에는 수동 재발송/상태 교정 API가 없습니다. 실패·미확인 행을 삭제하거나
  시도 횟수를 0으로 되돌리지 않습니다. 수동 복구 도구는 접수 근거와 별도 감사 기록을 검증하도록 후속 구현합니다.

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
| `get_chat_context` | `GET /internal/cases/{caseId}/context?before=5&after=5` | 공통 불변 마스킹 snapshot에서 최대 20건 |
| `get_stream_info`, `get_official_links` | `GET /internal/cases/{caseId}/channel-info` | 실제 채널 역할·공식 호스트·등록 이벤트 조회 |
| `get_channel_user_role` | `GET /internal/cases/{caseId}/role` | 실제 채널 정보 조회 재사용 |
| `get_security_facts` | `GET /internal/cases/{caseId}/security-facts` | 통합 사건은 UNKNOWN, 검증된 개인정보 출처는 미연동 |
| `get_user_history` | `GET /internal/cases/{caseId}/user-history` | 같은 사용자·방송 전체 확정 이력, `days`는 400 |
| `get_kick_eligibility` | `GET /internal/cases/{caseId}/kick-eligibility` | 현재 포함 24시간, 취소·예약·소진 제외 읽기 전용 조회 |
| `get_kick_review_basis` | `GET /internal/kick-reviews/{reviewId}/basis` | 원 결정의 고정 사건 ID와 실제 소진 소유권, 결정 전 409 |
| `get_action_status` | `GET /internal/actions/{actionId}` | 저장된 live action 조회; 미지원 legacy action은 501 |
| `kick_user` | `POST /internal/actions/{actionId}/execute` | Backend 발급 KICK 명령의 승인·버전·방송 상태 재검증 |
| `hide_message`, `timeout_user`, `release_message` | `POST /internal/actions/{actionId}/execute` | 별도 MCP 실행은 501; 가림·제한·해제는 BE 확정/apply 경로에서 처리 |
| `reconcile_review` (폐기 예정) | `POST /internal/cases/{caseId}/reviews/{decisionId}/reconcile` | 501 레거시 스텁 |

스텁은 인증·입력 검증 후 `501 {error:{code:"NOT_IMPLEMENTED",message:...,retryable:false},traceId:...}`를 반환하며
성공 데이터나 실제 조치를 생성하지 않습니다. 잘못된 입력은 `400 VALIDATION_FAILED`,
구현된 조회의 사건 메시지가 없으면 `404 NOT_FOUND`입니다. `/internal/**`는 외부에 공개하지 않습니다.
통합 KICK과 CONTEXT apply는 저장된 권한·버전·멱등성을 검사합니다. 자세한 범위는
[liveId 통합](docs/integration-v1.md)을 참고합니다.

### 사건·판정 근거 영속화 (BE2 #13)

- V200이 `guard.cases`, 후보 입력, 사건별 증거/대상 메시지, 유형별 finding과 감사 이벤트를 생성하고
  `platform.messages.case_id`에 FK를 추가합니다. 적용 전 기존 `case_id`가 임시 UUID인 데이터는
  별도로 정리해야 합니다. 마이그레이션은 가짜 사건이나 확정 이력을 자동 생성하지 않습니다.
- 기본 `CandidateSink`는 JDBC 구현이며 메시지 저장 트랜잭션에 참여합니다.
  live/channel 두 HTTP 전송 모두 `JdbcCandidateSink → LiveModerationService` 하나로 연결됩니다.
  메시지 ID 재전송은 전체 provenance가 같을 때만 허용하고, FLOOD는 같은 user/live/text의
  10초 무반복 전 열린 묶음을 확정 후에도 재사용합니다.
  관련 증거와 실제 조치 대상은 구분하며, 이미 다른 사건에 소속된 메시지의 소속은 바꾸지 않습니다.
- 새 후보가 추가되면 증거 버전과 스냅샷 ID가 바뀝니다. AI 제안은 확정 집계에 포함하지 않으며
  Backend 확정은 명시적인 정책 버전·근거 참조·결정 참조와 최신 증거 버전을 요구합니다.
  확정 권한/정책 검증은 후속 판단 서비스 책임이며, 저장 서비스는 Agent REST로 노출하지 않습니다.
- 여러 유형의 확정 finding이 있어도 사건은 한 번만 집계합니다. 취소는 finding과 기존 근거를
  삭제하지 않고 취소 감사 이벤트를 추가합니다. TIMEOUT 만료는 취소가 아닙니다.
- V205는 후보의 liveId·handlingRoute·ruleId·policyVersion·filterVersion·태그와 전체 요청을 보존합니다.
  원래 두 FLOOD 메시지는 증거만 연결하고 제재 대상으로 소유하지 않습니다.
  기존 미준비 사건에 방송·정책·스냅샷을 추정해 채우지 않습니다.

### 사건·이력 조회 통합 (03-2)

- `CaseQueryRepository`는 사건 메타데이터, 후보별 증거 버전·스냅샷, 증거/대상 연결,
  유형별 finding과 감사 이벤트를 읽습니다. 원문을 포함하는 저장 계층 조회는 REST로 노출하지 않습니다.
- 사건 API는 REPEATABLE READ 트랜잭션에서 저장된 불변 마스킹 증거와 대상의 현재 가시성·버전을 읽습니다.
  사건이 없으면 `404 NOT_FOUND`,
  방송·정책·처리 경로 또는 현재 증거/대상이 없으면 `409 CASE_NOT_READY`, `retryable=false`입니다.
  미준비는 재시도만으로 해결됨을 보장하지 않으며 BE1 정보 제공 또는 계약 설정이 필요합니다.
- `targets`가 정본입니다. Agent 호환 필드 `targetMessageIds`, `targetVersions`는 이 목록에서
  파생하므로 독립적으로 수정하거나 별도 입력으로 받지 않습니다.
  문맥 API는 같은 `snapshotId`·`evidenceVersion`을 사용하며 각 방향 0~10, 전체 최대 20건입니다.
  snapshot에 저장된 이후 도착한 문맥을 같은 ID에 덧붙이지 않습니다. 새 증거 버전은 새 snapshot ID를 받습니다.
- TIMEOUT 이력은 같은 user/live의 방송 전체이며 기준 시각까지 확정된, 현재 취소되지 않은 Backend 위반만 조회합니다.
  현재 사건은 제외하며 `violationCount`는 서로 다른 사건 수, `recentCases`는 사건·유형별 한 행입니다.
  서비스의 `timeoutHistory`는 명시적인 시작/종료 시각과 유형별 건수도 제공합니다.
  이는 현재 취소 상태를 반영하는 조회이며 과거 기준 시각의 상태를 재생하는 감사 조회가 아닙니다.
- 두 이력 경로 모두 `days`를 주면 `400 VALIDATION_FAILED`로 거부합니다.
  응답 시각은 `confirmedAt`이며 `windowHours` 필드는 없습니다.
- 강퇴 기초 조회 `kickBasis`는 명시한 사용자·채널·방송과 기준 시각을 받고, 최근 24시간의
  5개 유형을 합친 확정 위반을 사건 단위로 반환합니다. 현재 확정 사건도 포함하며 방송 ID 누락은 거부합니다.
  포함 경계는 `confirmedAt > asOf - 24h` 및 `confirmedAt <= asOf`입니다.
  실제 RESERVED·CONSUMED 소유권을 제외하며, 이 읽기 자체가 예약·승인·발송을 만들지 않습니다.
  TIMEOUT 만료나 강퇴 소진은 판정 취소가 아니며 TIMEOUT 이력을 지우지 않습니다.

### 조회 계약과 남은 정책

- 문맥: `before/after` 각각 0~10, 생략 시 각각 10. 사건 메시지를 우선하는 응답 전체 최대 20건입니다.
- 채널 정보는 방송 활성 상태·종료 시각이나 개인정보 실체를 보장하지 않습니다.
  공식 호스트라는 이유로 링크 전송 금지를 면제하지 않습니다.
- 역할은 현재 채널 역할입니다. BE1 조회는 탈퇴한 작성자를 VIEWER로 반환하므로 과거 역할·본인 확인 결과로 쓰지 않습니다.
- `SecurityFacts`의 TRUE/FALSE에는 검증 출처가 필요하고 확인 불가는 UNKNOWN입니다.
  채널 정보를 개인정보 검증 결과로 바꿔 반환하지 않습니다.
- TIMEOUT 이력은 현재 사건을 제외하며 강퇴 소진 후에도 유효한 과거 이력을 유지합니다.
  취소는 finding 단위이며 다른 유효 유형을 지우지 않습니다.
- 강퇴 후보는 같은 사용자·채널·방송의 Backend 확정 사건 중 최근 24시간, 현재 사건 포함,
  5개 위험 유형 통합 `distinct caseId`입니다. 취소·소진은 제외하지만 TIMEOUT 만료는 제외 사유가 아닙니다.
  3건 이상은 심사 요청 조건이지 승인이나 실행 허가가 아닙니다. 기간 변경용 `days`는 거부합니다.
- 승인 심사는 기준 사건을 고정·예약하고 승인/거절 모두 소진을 기록해야 합니다.
  `KickReviewBasis`는 원 승인 사건과 소진의 `reviewId/decisionId`를 제공하며 신규 후보 조회와 다릅니다.
  자기 승인에 따른 소진은 재검증 때 허용하지만 타 심사 소진·취소 등은 검증해야 합니다.
- `InternalCase`는 `liveId`, `snapshotId`, `evidenceVersion`, `policyVersion`, `handlingRoute`,
  대상 메시지의 현재 가시성·버전(`targets`)을 정의합니다. 활성 정책은 `v3.1`이며 필터 버전과 별개로 저장합니다.
- 방송 종료 B2 소비자는 KICK 검토를 INVALIDATED로 닫고 미집행 KICK·미발송 KICK resume만 중지합니다.
  CONTEXT 조사/검토, 확정·취소 이력과 소진, ACCEPTED/IN_FLIGHT/UNKNOWN 발송 결과는 지우지 않습니다.
  집행은 방송 상태를 다시 검사하며, 일반 복원은 RULE_CONFIRMED나 타 사건의 유효 hold를 풀지 않습니다.

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
판정·CONTEXT apply·강퇴 재검증과 KICK action 실행은 통합 경로에서 동작합니다.
구형 강퇴 실행(`/internal/kick-reviews/{reviewId}/execute`)과 `/history` 별칭은 삭제했습니다(A-12). reconcile은 501 스텁이고, 별도 MCP HIDE/TIMEOUT/RELEASE_HELD 실행은 미지원입니다.

### Agent 저장소에 필요한 후속 변경

이번 변경은 BE 저장소에 한정되므로 인접 `liveguard-agent` 어댑터는 별도 수정해야 합니다.

1. 인증 헤더를 `Authorization`에서 `X-Service-Token`으로 변경합니다.
2. `context?view=moderationCase`는 `/cases/{caseId}`로,
   `view=streamInfo/officialLinks`는 `/channel-info`로, `view=securityFacts`는 `/security-facts`로 변경합니다.
   `/context`는 문맥 전용이며 이전 `view=chatContext`만 호환합니다. 다른 view는 400으로 거부합니다.
3. `/history` 별칭은 삭제했습니다(A-12). `/user-history`를 쓰고 고정 `days=30` 정책을 제거합니다.
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
