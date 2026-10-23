# 다중 Agent·MCP 공동 시험 환경 (#76)

운영 배포가 아닌 **로컬 공동 시험 전용**이다. 기존 루트 Compose·DB·볼륨과 분리한다.
정책은 [정책 원본](policy/README.md)과 [A-25 결정](policy/TBD-decisions.md)을 따른다.
운영 기본 마감(3분), 승인·재검증·BE 대체 실행 기능은 변경하거나 끄지 않는다.

## 1. 고정 대상과 책임

| 대상 | 고정 소스 | 로컬 이미지 |
|---|---|---|
| BE | `acd3e98f7d2b1a74505d9f89bfb56dffe1082673` (#74 포함) | `liveguard-be:integration-acd3e98f7d2b` |
| Agent/API/Worker/Business MCP | `e720ce65f859a0312c4083e17b39974cf42c512d` | `liveguard-agent:integration-e720ce65f859` |
| DB / LB / 선택 broker | `pgvector/pgvector:pg16` / `haproxy:3.0-alpine` / `rabbitmq:4.1-alpine` | Compose에 명시 |

[versions.env](../deploy/integration/versions.env)가 소스·이미지 연결의 정본이다.
빌드 스크립트는 `git archive`로 **해당 커밋의 추적 파일만** 전송한다. dirty checkout,
로컬 `.env`, Agent의 중첩 clone은 이미지에 들어가지 않는다. 이미지 revision label도 기록한다.
위 기반 이미지 태그는 공급자 갱신 가능 태그다. 공동 시험 결과에는 실제 image ID/digest를 함께 남긴다.
배포 레지스트리를 쓰는 담당자는 검증된 이미지 digest를 고정하며 `latest`로 대체하지 않는다.
이 문서·Compose는 위 BE 커밋 위에 추가하는 시험 자산이며, BE 업무 코드는 그 커밋 그대로 빌드한다.

- **BE:** Outbox, 내부 API, 3분 판정 fallback·강퇴 handoff, 시험/관측 절차.
- **Agent:** 공유 Inbox/Checkpointer/Worker, 모델과 RAG 설정·정책 색인.
- **Infra:** 실제 공통 주소·네트워크·이미지 게시. 이 변경은 Infra 저장소를 수정하지 않는다.
- RAG MCP는 증설하지 않는다. 컨테이너에서 접근 가능하고 `v3.1`이 색인된 외부 RAG MCP를 사용한다.
  버전·이미지 digest·`indexedPolicyVersions`를 시험 기록에 함께 남긴다.

## 2. 구성

```text
BE --http://agent-api:8001--> HAProxy --> API 1 / API 2
                                             |
                              shared agent-db.agent_inbox
                                             |
                                    Worker 1 / Worker 2
                                      |             |
                              agent-db.agent (checkpoint)
                                      |
                  http://business-mcp:8002/mcp (HAProxy)
                                      |
                                 MCP 1 / MCP 2
                                      |
                         http://backend:8080/internal/**
```

API 두 개와 Worker 두 개는 **같은 PostgreSQL 인스턴스의 같은 database `agent`**를 사용한다.
BE DB는 별도 서비스다. SQLite로 자동 대체하지 않는다. Worker ID는
`integration-worker-1`, `integration-worker-2`로 다르다. `--scale worker-1=2`로
동일 ID를 복제하지 않는다. 추가 프로세스는 새 고유 ID를 받아야 한다.

공유 설정은 API/Worker 모두 Graph 4 / Worker당 2 / 모델 전체 4 / Inbox 10000이다.
모델 quota group과 제한값도 두 Worker에 동일하게 전달한다.
`agent-init`이 Inbox 및 checkpoint 초기화를 한 번 끝낸 후 Worker가 시작한다.
고정 Agent 버전의 최초 동시 checkpoint migration에서 실제 교착이 관찰되어,
초기화를 독립 one-shot 단계로 분리했다. 실패하면 Worker를 시작하지 않는다.
Agent의 실행·승인·fencing 로직을 수정하거나 실패한 Worker를 무한 재시작하지 않는다.

LB는 요청 단위 round-robin, `retries 0`, Docker DNS 갱신을 사용한다.
MCP 세션 affinity·공유 세션 저장소·MCP별 업무 대기열은 없다.
API LB는 `/health`의 200과 `readyForTasks=true`, MCP LB는 `/ready`의 200을 확인한다.
Worker 종료 유예는 75초(Agent가 최대 두 차례 30초 정리를 기다림),
MCP drain 20초 / Compose 유예 30초다. 요청 인자·인증 헤더를 남기는 LB 요청 로그는 켜지 않는다.

## 3. 준비와 비밀값

필수: Docker/Compose v2.24 이상(또는 v5), Git, Bash, `curl`, `jq`, `uuidgen`, Java 21.
BE와 Agent checkout은 기본적으로 같은 상위 폴더에 둔다. Agent 경로가 다르면
`AGENT_REPO=/absolute/path/liveguard-agent`를 빌드 명령에 지정한다.

```bash
cp deploy/integration/.env.example deploy/integration/.env
chmod 600 deploy/integration/.env
openssl rand -hex 24
```

마지막 명령으로 **매번 다른 값**을 만들어 `.env`의 DB 비밀번호·서비스 토큰을 채운다.
DB 비밀번호는 DSN에 들어가므로 이 예제에서는 URL escape가 필요 없는 hex만 쓴다.
실제 `.env`, 토큰, 전체 Compose 렌더링 결과는 커밋·이슈·스크린샷에 올리지 않는다.
설정 검사는 비밀값을 출력하지 않는 `config --quiet`를 사용한다.

| 인증 구간 | 환경변수 연결 |
|---|---|
| BE → Agent API | `AGENT_SERVICE_TOKEN` → Bearer |
| Worker → Business MCP | `BUSINESS_MCP_AGENT_TOKEN` → Bearer |
| Worker/Gateway 및 Business MCP → BE | `INTERNAL_SERVICE_TOKEN` → 각각 `BACKEND_SERVICE_TOKEN` / `BUSINESS_MCP_SERVICE_TOKEN`, HTTP는 `X-Service-Token` |
| Worker → RAG MCP | `RAG_MCP_AGENT_TOKEN` → 별도 Bearer |
| Worker → 모델 | `MODEL_API_KEY` |

외부 주소에서 `localhost`는 해당 컨테이너 자신이다. 호스트에서 실행하는 신뢰된 모델/RAG라면
Docker Desktop의 `host.docker.internal` 등 컨테이너에서 도달 가능한 주소를 쓴다.
`MODEL_PROVIDER`, `MODEL_BASE_URL`, `MODEL_NAME`, `MODEL_API_KEY`, `MODEL_QUOTA_GROUP`,
`RAG_MCP_URL`은 명시적으로 채운다. 모델 선택이나 실제 인증정보를 기본값으로 숨기지 않는다.

## 4. 빌드·시작과 전달 모드

저장소 루트에서 실행한다.

```bash
bash scripts/integration.sh build
bash scripts/integration.sh normal db config --quiet
bash scripts/integration.sh normal db up -d --wait --wait-timeout 240
bash scripts/integration.sh normal db ps --all
```

기본 시험 프로젝트명은 `liveguard-integration`이다. 담당자별로 분리하려면
`INTEGRATION_PROJECT_NAME=liveguard-myname`과 충돌하지 않는 `.env`의 호스트 포트를 **모든 명령에**
같게 적용한다. 환경파일 경로는 `INTEGRATION_ENV_FILE=/absolute/path/private.env`로 변경할 수 있다.
DB/개별 replica 포트는 호스트에 공개하지 않는다. 호스트 노출은 loopback의
BE 18080, Agent 공통 18001, Business MCP 공통 18002뿐이다.
시드 계정은 `viewer`/`moderator`/`admin`, 비밀번호 `liveguard-local`인 로컬 데이터다.
이 설정을 인터넷이나 운영 DB에 연결하지 않는다.

전달 모드는 두 번째 인자로 **하나만** 선택한다.

| 모드 | 명령 예 | 활성 발송 경로 |
|---|---|---|
| DB | `bash scripts/integration.sh normal db up -d --wait` | DB poll → HTTP |
| Rabbit | `bash scripts/integration.sh normal rabbit up -d --wait` | DB Outbox → Rabbit 알림 → BE HTTP Worker |

Rabbit 모드에는 `RABBITMQ_PASSWORD`가 필요하다. broker는 이 overlay에서만 생성하고,
DB polling으로 자동 전환하지 않는다. Agent는 Rabbit 소비자가 아니다.
전달 모드를 바꿀 때는 기존 명령의 `stop`으로 전체 프로세스를 먼저 멈춘 뒤 새 모드로 `up`한다.
기존 broker 볼륨·DB Outbox는 지우지 않는다. 같은 DB에 DB/Rabbit 발송 BE를 따로 동시에 띄우지 않는다.
반복 가능한 독립 시험은 모드별 새 프로젝트·새 볼륨을 사용하는 편이 안전하다.

## 5. 준비 상태는 세 가지로 나눠 확인

1. **접수 준비:** API 1·2 health가 각각 정상이고 공통 `/health`도
   `readyForTasks=true`. 이는 Inbox/수신 인증 준비이지 Graph 실행·모델 연결 보장이 아니다.
2. **실행 준비:** Worker 1·2 각각 현재 시작 이후 `WORKER_READY`, 프로세스 running.
   `up --wait`만으로는 Worker의 조립 완료를 보장하지 않는다(Worker에 HTTP probe가 없다).
   아래 smoke가 최대 30초씩 두 Worker의 현재 시작 로그와 실행 상태를 확인한다.
   그 뒤 실제 job의 `attempts`, `last_worker_id`로 실행을 확인한다.
3. **MCP 준비:** MCP 1·2 `/ready` 정상은 설정/lifecycle 준비다.
   실제 BE 접근은 기존 `get_moderation_case` 도구로 조회해 확인한다.
   RAG는 별도로 `/health.readyForTasks=true` 및 색인 `v3.1`을 확인하고,
   실제 조사 시험에서는 기존 검색/원문 읽기가 성공하는지 확인한다.
   Worker 조립 성공을 외부 RAG·모델 접속 성공으로 해석하지 않는다.

### 실제 BE 연결 smoke

새 프로젝트의 사용하지 않은 시드 방송 A에서만 실행한다. 기존 메시지가 있으면 거부한다.
브라우저 세션·CSRF로 실제 문맥 후보 메시지 한 건을 보내고, Outbox와 Agent job을
각각 조회하며, 공통 MCP의 초기화 및 실제 BE 사건 조회까지 검증한다.
토큰·채팅·도구 결과 원문은 출력하지 않는다. 토큰은 컨테이너 환경에서 읽어
curl 설정 stdin으로 넘기고 임시 쿠키/응답은 종료 시 삭제한다.

```bash
# 정상 통제 모델 또는 실제 모델·RAG가 연결된 경우:
bash scripts/integration-smoke.sh normal db investigation
```

이 경우 문맥 사건의 예상 Agent 상태는 `WAITING_REVIEW`다. **검토 생성·interrupt까지**의
연결 smoke이며, 사람 승인/재개/강퇴 집행을 검증한 것은 아니다.

외부 모델/RAG 없이 연결만 검사하기로 합의한 경우에는 private `.env`에만
`MODEL_PROVIDER=openai-compatible`, `MODEL_NAME=unused-connection-smoke`,
`MODEL_BASE_URL=http://unconfigured.invalid/v1`, `MODEL_API_KEY=unused-connection-smoke`,
`MODEL_QUOTA_GROUP=connection-smoke-only`, `RAG_MCP_URL=http://unconfigured.invalid:8003/mcp`를
명시적으로 설정하고 RAG 토큰에는 별도 시험용 난수를 사용한다.

```bash
bash scripts/integration-smoke.sh normal db dependency-failure
```

이 시험은 실제 BE→Agent 접수 `ACCEPTED`, Worker 실행 후 **`FAILED/MODEL_UNAVAILABLE`**,
공통 MCP→실제 BE 조회 성공을 요구한다. 출력도 `EXPECTED DEPENDENCY FAILURE`로 표시한다.
이는 모델/RAG 실검증도, 일반 Agent 업무 성공도 아니다. 가상 Backend의 성공으로 대체하지 않는다.
Rabbit 시험은 명령의 `db`를 `rabbit`으로 바꾸고 새 프로젝트에서 실행한다.

## 6. 시험 시나리오와 집계

| 구분 | overlay / 마감 | 합격 관측 |
|---|---|---|
| 일반 Agent 경로 | `normal`, 판정·handoff 각각 **15분** | 예상 대기 5분 + 실행/복구 5분 + 여유 5분 이내. AI 판정/검토 interrupt와 저장된 사람 결정 뒤 재개를 확인. BE 대체 개입 시 일반 성공으로 집계하지 않음 |
| 기본 정책 대체 | `fallback`, 각각 **3분** | 판정 마감 전 검토 미생성, 마감 도달 후 다음 poll(기본 10초)에서 AI 없는 검토, HELD 유지. 사람 결정 후 BE 직접 적용 |
| 강퇴 handoff | `fallback`, 승인부터 **3분** | 승인 전 실행 없음, 마감 전 Agent 경로 유지, 마감 후 BE 재검증·집행. 늦은 Agent 재개는 같은 action 결과, 중복 강퇴 없음 |
| 확인된 거부/불확실성 | 둘 다 | 거부 `FAILED`는 조기 fallback 가능. `UNKNOWN`은 확정 거부로 취급하지 않고 판정 마감 대기. 종료 방송·superseded에는 fallback 없음 |

15분은 **이 시험의 제한된 부하 예산**이지 10000건 만재 처리 보장이 아니다.
실측 대기/모델 RPM·TPM·재시도가 15분을 넘으면 부하/설정을 재합의하고 시험을 중단한다.
마감 연장으로 모델 실패·발송 거부·증거 버전 충돌을 숨기지 않는다.
normal에서도 `FAILED`로 인한 조기 fallback은 살아 있다.
승인·fallback·kick executor의 `enabled=false`는 사용하지 않는다.
시나리오를 바꿀 때에는 기존 미완료 작업의 의미가 달라지지 않도록 별도 프로젝트를 사용한다.

기본 경계/멱등성 회귀는 실제 PostgreSQL Testcontainers를 쓰는 기존 시험으로 확인한다.
마감 전/후, AI 없는 정상·위반·DEFER, 늦은 판정, 종료 방송,
3분 강퇴 handoff, 재검증 실패, 늦은 Agent 실행 중복 방지를 포함한다.

```bash
./gradlew test --tests '*LiveModerationIntegrationTest' --tests '*AgentOutboxTest' --tests '*AgentRabbitIntegrationTest' --console=plain
./gradlew build --console=plain
```

실시간 공동 fallback 시험에서는 Worker를 `stop worker-1 worker-2`로 멈추고 문맥 사건을 생성한다.
BE 발송은 `ACCEPTED`, Agent는 `QUEUED`로 남을 수 있다. 판정 마감 전·후를 각각 기록하고
AI 없는 검토를 운영자가 결정한 뒤 `has_ai_judgment=false`, 직접 적용 상태를 확인한다.
판정 없는 결정에는 Agent interrupt가 없으므로 임의 resume를 만들지 않는다.
실제 강퇴 handoff 시험은 운영자가 정상 절차로 만든 세 확정 사건과 강퇴 승인을 사용하며,
내부 SQL로 승인·집계를 조작하거나 timeout을 끄지 않는다. 실제 집행의 공동 E2E는 별도 시험 범위다.

### 통제 모델과 실제 모델

- **통제 모델:** Agent 담당자가 제공한 신뢰된 OpenAI-compatible function-calling 서버와
  고정된 합성 입력·정책 증거를 사용한다. endpoint 버전/시나리오/의도된 지연·오류와 호출 횟수를 기록한다.
  가짜 BE나 임의의 승인/판정 주입을 정상 조사로 간주하지 않는다.
- **실제 모델:** 명시적인 공급자/모델/endpoint/할당량, 동의된 합성 데이터만 사용한다.
  사용료와 외부 전송 범위를 사전 확인한다. 이 이슈는 모델 성능 평가가 아니다.
- 둘은 별도 프로젝트/Inbox/quota 기록을 사용한다. `.invalid` 연결 smoke를 통제 모델 시험으로
  기록하지 않는다. 실제 키가 없는 실행을 더미 성공으로 돌리지 않는다.

## 7. 관측·로그·보존

DB는 정본이고 Rabbit은 UUID 알림뿐이다. 접수 `ACCEPTED`/HTTP 202와
Graph `WAITING_REVIEW`/`SUCCEEDED`/`FAILED`/`UNKNOWN`을 반드시 따로 기록한다.

```bash
mkdir -p integration-results
bash scripts/integration.sh normal db exec -T db psql -U liveguard -d liveguard -v ON_ERROR_STOP=1 \
  < deploy/integration/observe.sql > integration-results/be-state.txt
bash scripts/integration.sh normal db exec -T agent-db psql -U agent -d agent -v ON_ERROR_STOP=1 \
  < deploy/integration/observe-agent.sql > integration-results/agent-state.txt
bash scripts/integration.sh normal db ps --all > integration-results/processes.txt
bash scripts/integration.sh normal db images > integration-results/images.txt
docker image inspect liveguard-be:integration-acd3e98f7d2b liveguard-agent:integration-764eab26a827 \
  --format '{{.Id}} {{index .Config.Labels "org.opencontainers.image.revision"}}' \
  > integration-results/revisions.txt
```

Agent `/agent/metrics`는 Agent Bearer로 조회한다. 공유 집계만 반환하며 준비 probe 대신 쓰지 않는다.
로그가 필요하면 현재 Worker의 `WORKER_READY`/`WORKER_NOT_READY` 및 BE의
`Context review opened without AI judgment` 같은 허용된 이벤트만 추출한다.
`docker inspect` 전체 환경, `compose config` 전체, payload/envelope, 채팅 원문, 도구 인자,
프롬프트·쿠키·인증 헤더·DB DSN을 보고서에 넣지 않는다.
시험 결과에는 scenario/transport/model-mode, 이미지 ID, 안전한 task/case/action ID,
시작·판정·승인·실행 시각, fallback 개입 여부를 기록한다.
`has_ai_judgment=false`인 검토, 직접 적용 또는 handoff 결과는 `BE_FALLBACK`으로 분리한다.
action 자체가 성공했더라도 실행 주체를 모르면 일반 Agent 성공으로 세지 않고 `UNCLASSIFIED`로 남긴다.

Agent Inbox에는 TTL/자동 삭제가 없다. QUEUED/RUNNING/WAITING_REVIEW/UNKNOWN은
용량을 사용하며 종료 기록도 멱등성 영수증으로 보존한다.
BE 용량 거부는 `Retry-After`와 backoff 중 늦은 시각에 재시도하고 기본 5회 예산을 소모한다.
확인된 거부만 있으면 `FAILED`, 이전 접수 불확실성이 있으면 `UNKNOWN`으로 끝난다.
접수 성공 뒤에는 Outbox 예산으로 Graph 완료를 보장하지 않으며, BE 3분 마감도 별도로 진행한다.
연결 실패에 예산이 환불되어도 업무 마감은 멈추지 않는다.
Agent 미완료 기록·checkpoint를 지워 용량을 만들거나, UNKNOWN을 QUEUED로 바꿔 실행하지 않는다.
BE가 fallback으로 결정했는데 Agent에는 미완료 작업이 남았다면 원 사건·결정·checkpoint를
함께 확인해 Agent 담당자와 명시적으로 정리한다. TTL/취소 계약이 있다고 추정하지 않는다.

## 8. 종료·시험 데이터 정리

```bash
# 신규 Agent claim 중단·실행 정리 후, 네트워크/컨테이너만 제거. 볼륨 보존.
bash scripts/integration.sh normal db stop worker-1 worker-2
bash scripts/integration.sh normal db down
```

운영/공동 DB에서는 부분 테이블 삭제·Outbox/Inbox/멱등성 기록 삭제를 하지 않는다.
독립 로컬 시험 프로젝트를 **완전히 폐기**하는 경우에만, 해당 프로젝트명·볼륨과
보관할 결과를 확인한 뒤 같은 명령에 `down -v`를 사용한다. 다른 프로젝트의 DB/Rabbit/RAG
볼륨에는 적용하지 않는다. 개인 환경파일과 결과는 공유/커밋하지 않는다.

## 9. 이번 검증의 경계

#78의 실제 Spring·통제 모델 공동 검증은 [BE 공동 E2E 보고서](be78-joint-verification.md)를
따른다. 아래는 #76 당시의 모델 미연결 검증 이력이며 #78 결과와 혼합하지 않는다.

요청자와 합의한 검증 범위는 외부 모델·RAG 없는 로컬 연결 시험이다.
일반 조사·통제 모델·실제 모델, 운영자 승인 뒤 실제 Spring action 집행,
다중 인스턴스 장애/복귀·응답 유실 공동 E2E의 성공을 이 smoke로 주장하지 않는다.
그 시험에서는 위 일반/대체 처리 구분과 실제 사건·결정·action 기록을 사용해야 한다.

### 로컬 검증 결과 (2026-10-23)

| 항목 | 결과 |
|---|---|
| 고정 소스 이미지 빌드 | BE·Agent 모두 성공 |
| 설정 조합 | normal/db, normal/rabbit, fallback/db, fallback/rabbit 렌더링·공유 DB·공통 주소·마감·고유 Worker ID 확인 |
| 최초 빈 DB 기동 | one-shot 초기화 뒤 API 2개·Worker 2개·MCP 2개 준비 확인 |
| DB/Rabbit 실제 연결 | 각각 실제 브라우저 메시지 → 사건/Outbox → 공통 Agent API 접수 `ACCEPTED`, Worker 실행 이력 확인 |
| 외부 모델 미연결 | 두 전달 모드 모두 `FAILED/MODEL_UNAVAILABLE` 확인. 조사 성공으로 집계하지 않음 |
| MCP→Spring | 공통 LB의 `get_moderation_case`로 실제 사건 조회, case/trace/처리 경로 검증 |
| 인증 경계 | Agent 미인증 401, 브라우저 세션만으로 내부 사건 조회 403, MCP 미인증 401 확인 |
| 기본 3분 fallback | 새 fallback/db 프로젝트에서 실제 3분 대기. 마감 전 검토 없음, 마감 후 AI 없는 CONTEXT/PENDING 생성 및 메시지 HELD 유지 |
| 전체 빌드 | `./gradlew build --rerun-tasks --console=plain` 성공, 51개 suite / 553개 test, 실패·오류·건너뜀 0 |
| 정리 | 시험 전용 프로젝트의 컨테이너·네트워크·볼륨 제거. 기존 로컬/공동 환경은 변경하지 않음 |
