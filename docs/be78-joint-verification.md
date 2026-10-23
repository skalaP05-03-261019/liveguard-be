# BE #78 공동 E2E 검증 보고서

검증일: 2026-10-23. 실제 Spring BE와 Agent #96의 통제 실행기를 사용했다.
운영 DB 보정, 승인 우회, fallback/실행기 비활성화, 외부 모델 호출은 하지 않았다.
Agent Graph/Worker 시험을 복제하지 않고, 기존 실행기 뒤에 **BE 상태 불변식 검증**을 추가했다.
Agent #96 완료 후 main `9122b41`로 기존 시험을 재실행하고 잔여 7개 분산 시나리오를 완료했다.

## 1. 버전과 환경

| 대상 | 고정 값 |
|---|---|
| 실제 업무 BE | `acd3e98f7d2b1a74505d9f89bfb56dffe1082673` |
| Agent/API/Worker/Business MCP/통제 fixture | `9122b41bc8ffe5bb21e4fac7ad22a14fe98e5e79` (Agent #96 / PR #104 병합 main) |
| BE 시험 자산 기준 HEAD | `9a03d1a96dcafe7797a97c7e7d3560c55d3a4c99` + 이 보고서의 래퍼·버전·용량 provenance 순서 변경 |
| BE image ID | `sha256:61756e2be4a31f94bbf93c5b5e9ba1ff742b39859abd4bb8a9c466837f00ab65` |
| Agent 소스 fingerprint | `f450edb6edfae86d90cf25e5aa81769522191535bba699b75d80345d29d891b4` |
| 정책 v3.1 원문 SHA256 | `c34ae086df1254ee32190f3a02839aaa4edda2e6e9450b4b9b814dd3787e8f2d` |
| 정상 전체 시험 Agent image ID | `sha256:a37cf25816a2e05143063d8e263656806010b47b1074593694be7ef60ad1b5a9` |
| 응답 유실 시험 Agent image ID | `sha256:d9ee46820dad5cc8d30c0b24a66f3e5b57bf81847a4e5c4454ac800a1a8a37f0` |
| 용량 시험 Agent image ID | `sha256:fb22b404a81fc5cb69cafbf6fe520748c8a0829eb68a1c3520eb01cae8bb836e` |

실제 **실행 중 컨테이너**의 이미지와 각 실행의 ID·fingerprint는 로컬 JSON 보고서에
별도로 저장한다. API LB의 이미지와 Worker/MCP/fixture 이미지를 구분한다.
Agent 실행기는 작업 트리 빌드이므로 `agentDirty`도 기록한다. 본 검증의 tracked 실행 소스는
고정 커밋과 같고, 기존 untracked 중첩 clone은 Docker context와 fingerprint에서 제외된다.
이를 clean checkout 인증으로 바꾸어 표기하지 않는다. 재빌드의 image ID는 달라질 수 있다.

구성: API 2 / Worker 2 / MCP 2, 기존 HAProxy, 공유 PostgreSQL Inbox·Checkpointer,
별도 실제 BE DB. BE 발송은 DB polling 및 별도 Rabbit 접수 유실 시험,
일반 판정·handoff 마감은 15분이다. deadline 시험만 기존 fallback overlay의 실제 3분을 사용한다.
Graph 4 / Worker당 2 / 모델 전체 4, 30 RPM / 60000 TPM / 모델 timeout 30초를 유지한다.
용량 시험만 **초기화 전부터** 모든 공유 구성원의 Inbox capacity를 2로 맞춘다.
정상 기본값 10000이나 운영 3분 정책은 변경하지 않는다.

통제 모델·정책 조회만 fixture다. Business MCP/Gateway의 업무 정본은 실제 Spring이다.
응답 유실 시험의 투명 프록시도 Spring에 원래 요청을 적용한 뒤 응답 body만 끊는다.
검색·임베딩/실제 모델 정확도 또는 공급자 quota를 인증하는 시험이 아니다.

## 2. 재현

필수 환경과 비밀값 경계는 [공동 환경 절차](multi-instance-integration.md)를 따른다.
Agent checkout은 위 커밋이어야 한다. 아래 명령은 **BE 루트**에서 실행한다.

```bash
bash scripts/integration.sh build

# 각 실행은 다른 env와 미사용 project를 요구한다. 실제 키를 전달하지 않는다.
bash scripts/joint-be-e2e.sh run normal \
  "$HOME/.liveguard-be78-normal.env" liveguard-joint96-be78-my-normal
bash scripts/joint-be-e2e.sh run loss \
  "$HOME/.liveguard-be78-loss.env" liveguard-joint96-be78-my-loss
bash scripts/joint-be-e2e.sh run capacity \
  "$HOME/.liveguard-be78-capacity.env" liveguard-joint96-be78-my-capacity
```

[BE 실행 래퍼](../scripts/joint-be-e2e.sh)는 Agent의
`python -m app.joint_test.run` init/build/up/run/down을 그대로 호출한다.
Agent 저장소 위치는 `AGENT_REPO`로 지정한다. `uv`는 Agent의 lockfile/환경을 사용한다.
정상 full/handoff/model-timeout 뒤 [BE 불변식 검사](../scripts/verify_joint_backend.py)와
[두 방송·Worker 복구 검사](../scripts/verify_joint_recovery.py)를 실행한다.
loss는 full/handoff/model429/action-loss 뒤 같은 BE 불변식을 검사한다.
capacity는 [시험 전용 overlay](../deploy/integration/joint-capacity.yaml)와
[실제 접수 용량 검사](../scripts/verify_joint_capacity.py)를 실행한다.
잔여 시험은 Agent main의 `app.joint_test.remaining`을 재사용한다.
BE에 같은 Graph/장애 주입 구현을 복제하지 않는다.

```bash
# 아래 7개 모두 독립 env / project / DB로 순차 실행한다.
for suite in receipt receipt-rabbit race stale load-burst load-steady deadline; do
  bash scripts/joint-be-e2e.sh run "$suite" \
    "$HOME/.liveguard-be78-$suite.env" "liveguard-joint96-be78-my-$suite"
done
```

receipt-rabbit만 기존 Rabbit overlay를 추가하며, Agent가 Rabbit을 직접 소비하지 않는다.
deadline만 fallback(3분), 나머지는 normal(15분)이다. load는 각 24건으로 고정한다.
각 suite에 같은 옵션을 적용한 `down`도 지원한다.

기본 host port 18080/18001/18002/18004를 사용하므로 순차 실행한다.
동시 다른 시험이 해당 포트를 사용하면 시작을 중단하고, 다른 담당자의 프로세스를 종료하지 않는다.
초기화/기동 오류는 성공으로 대체하거나 자동 재시도하지 않는다.
기존 환경파일·보고서·프로젝트 볼륨이 있으면 `run`은 덮어쓰거나 지우지 않는다.

출력: 무시된 `integration-results/<project>-{agent,backend,recovery,capacity,remaining}.json`.
상태·식별자·시각·이미지·안전한 집계만 포함한다. 원문/명령/envelope/토큰/DSN은 기록하지 않는다.
BE 추가 검사는 SQL **SELECT만** 사용한다. 운영자 결정·방송 생성/종료·finding 취소는
실제 세션·CSRF API를 호출한다. Agent fixture control도 시험 인증을 거친다.

실패 시에도 컨테이너/네트워크 정리를 시도하고 DB 볼륨과 private env는 보존한다.
시험 복구/분석 후 명시적으로 정리하려면:

```bash
bash scripts/joint-be-e2e.sh down normal \
  "$HOME/.liveguard-be78-normal.env" liveguard-joint96-be78-my-normal
# 결과를 보관하고 본인 프로젝트의 정확한 이름을 확인한 뒤에만:
docker volume rm liveguard-joint96-be78-my-normal_be-db \
  liveguard-joint96-be78-my-normal_agent-db
```

`down`만으로 볼륨을 삭제하지 않는다. 모든 suite의 env/project를 맞춘다.
receipt-rabbit에는 해당 project의 `_rabbitmq` 볼륨도 있으므로 소유자가 별도로 확인한다.
공동/운영 DB의 멱등성 이력이나 UNKNOWN을 수동 삭제·QUEUED로 변경하지 않는다.

## 3. 실제 분산 환경 결과

| 시나리오 | 입력·검증 | 결과 |
|---|---|---|
| 일반 업무 | 문맥 5건: NORMAL 1 / DEFER 후 NORMAL 1 / VIOLATION 3, 각 실제 운영자 결정, 별도 KICK APPROVE | PASS |
| 상태 구분 | 각 Outbox ACCEPTED, 조사 WAITING_REVIEW, 최종 결정 재개, BE 확정·action 상태를 별도 검사 | PASS |
| 다른 Worker 재개 | 결정 전 이전 소유 Worker를 정상 종료하고 동일 task/thread의 결정 재개를 다른 Worker에서 처리 | PASS |
| 모델 timeout | 최초 한 호출 35초 지연, 기본 30초 timeout 후 기존 모델 재시도 | PASS |
| 모델 429 | 최초 429/Retry-After 1초 후 기존 모델 재시도 | PASS |
| KICK 응답 유실 | 실제 Spring execute 성공 뒤 body 단절, execute POST 1회/유실 1회, KICK action 1개 | PASS |
| 유실 뒤 보수적 상태 | BE SUCCEEDED, Agent NEEDS_REVIEW / ACTION_CONFIRMED_REQUIRES_REVIEW, 전체 Graph 성공으로 재분류하지 않음 | PASS |
| 다른 MCP 관측 | 실행을 전달한 MCP 중지 후 다른 MCP로 동일 action SUCCEEDED 조회 | PASS |
| 동시 중복 접수 | 12개 run/resume operation을 2회씩, 최대 동시 4로 24회 재전송, 모두 200 duplicate | PASS |
| 업무 중복 없음 | 재접수 전후 case/review/decision/finding/action/kick/Outbox 수와 Inbox job 수 동일 | PASS |
| 불변 증거 | 12개 operation의 실제 liveId/snapshot/evidenceVersion 및 불변 snapshot 연결 검사 | PASS |
| 재검증·집행 재호출 | 기존 결정으로 같은 actionCommand 반환, 동일 execute 2회가 기존 결과 반환, 새 업무 조치 없음 | PASS |
| MCP 단일 장애 | mcp-1 중지 후 mcp-2에서 실제 action 조회 | PASS |
| MCP 전체 장애 | 둘 다 중지 후 TEMPORARY_UNAVAILABLE, 성공 결과 없음 | PASS |
| MCP 복귀 | 두 서버 재기동 후 동일 action 조회, 업무 데이터 증가 없음 | PASS |
| finding 취소 | 실제 revoke API 중복 요청이 같은 결과, 기존 강퇴 해제, 과거 action 결과 보존 | PASS |
| 과거 action 재호출 | 취소 뒤 기존 execute는 과거 결과만 반환하고 해제된 강퇴를 재생성하지 않음 | PASS |
| 방송 종료 | 실제 방송 종료 뒤 action 조회/execute 재호출도 과거 결과 유지, 강퇴 재생성 없음 | PASS |
| 다중 방송 공정성 | 실제 새 방송 A 4건/B 2건을 Worker 정지 중 enqueue, 재기동 뒤 최초 4개 admission에 양쪽 방송 포함, 6건 모두 운영자 정상 결정 완료 | PASS |
| 오래된 검토 버전 | 실제 decision API의 잘못된 expectedReviewVersion을 409 STALE_VERSION으로 거부 | PASS |
| 실행 중 Worker SIGKILL | 모델 지연 중 RUNNING 소유 Worker 강제 종료, 30초 lease 뒤 다른 Worker가 동일 operation 2번째 claim | PASS |
| lease 회수 후 업무 결과 | CHECKPOINT_WAITING_CONFIRMED / WAITING_REVIEW 복구, 정상 결정 재개 SUCCEEDED, run job·CONTEXT review 각 1개 | PASS |
| 실제 용량 거부 | capacity 2 / PII 정상 합성 입력 3건 / Worker 정지, 3번째 Outbox RETRY 및 uncertain=false, Inbox에는 2건만 존재 | PASS |
| Retry-After | 저장된 next_attempt_at 전 시도 수 1 유지, 동일 payload hash로 뒤늦은 접수, 이후 job 3개·SUCCEEDED | PASS |

정상 최종 재현의 full 실행 시간(기동 제외)은 **62.618초**, 모델 11회(정상 10 + timeout 1)다.
응답 유실 full 실행은 **40.395초**, 모델 11회(정상 10 + 429 1)다.
시나리오별 elapsed는 입력부터 순차 결정까지이므로 마지막 사건에는 앞선 사건의 운영자 처리 대기가 포함된다.
Inbox wait/lastAttempt는 기존 실행기가 제공하는 최신 admission 기준이며 전체 재시도 누적 CPU 시간이 아니다.

용량 시험에서 거부 작업은 최초 next_attempt_at 전 **1.275384초** 시점에도 attempt=1/RETRY였다.
최종 BE attempt=3, Agent operation/job은 1개, uncertain=false, payload hash 동일이었다.
Worker 조립/용량 해제 전에 두 번째 거부가 가능하므로 attempt=2만을 합격 조건으로 강요하지 않는다.
서버 deadline은 HTTP 응답 수신 때 계산되고 Outbox updated_at은 그 이후이므로
`next_attempt_at - updated_at`이 9.999690초였던 것을 10초 위반으로 판정하지 않는다.
저장된 deadline 전 미발송과 최종 접수 시각을 별도로 검증한다.
새 Agent의 provenance가 실행 중 컨테이너를 요구하므로 용량 overlay 기동 **후** 수집하도록
BE 검사 순서를 수정했다. 첫 시도는 `TESTED_CONTAINER_REQUIRED_CONTROLLED_FIXTURE`로
기동 전 중단됐으며, 새 프로젝트 `liveguard-joint96-be78-main-capacity-final`에서 통과했다.
이를 실제 접수/업무 실패나 PASS 결과로 혼합하지 않는다.

## 4. 결과 연결 예

응답 유실 실행:

- case `b6e738af-102d-48bb-bd38-989577bed15a`
- task `64d06537-e6f5-4c3f-8672-2f9d8afacfd7`
- thread `case:b6e738af-102d-48bb-bd38-989577bed15a:analysis:1`
- review `69489f5f-8c3a-496f-871d-123f4a044fac`
- decision `d18eab82-845e-43d5-837a-76cd377a2174`
- action `5b99d2c5-70bf-42a6-9914-983db99880d1`
- operation `resume:d18eab82-845e-43d5-837a-76cd377a2174`

보고서 `bindings`는 task/operation/case/live/snapshot/evidence/review/decision을 연결하고
KICK 검사는 action ID를 추가한다. 조치 명령·대상 원문은 보고서에 필요하지 않다.
중복 전후 집계는 사건 5 / 검토 6 / 결정 7 / finding 6 / action 4 / 현재 kick 1 /
Outbox 12였다. action 4는 KICK 1개와 정상 업무의 TIMEOUT 3개이지 중복 강퇴 4회가 아니다.
finding 취소 뒤 현재 kick 0이 되어도 action 과거 성공 결과는 유지한다.

## 5. BE 마감·버전 경합 회귀와 증거 수준

실제 분산 시험과 별도로 실제 PostgreSQL/Testcontainers 기반
`LiveModerationIntegrationTest`, `AgentOutboxTest`, `AgentRabbitIntegrationTest`를 실행한다.
사람 결정/시간 경계는 주입 Clock 또는 서비스 시험으로 제어하며 운영 DB를 보정하지 않는다.

| BE 계약 | 회귀 시험 |
|---|---|
| ACCEPTED는 실행 성공이 아님, UNKNOWN은 확정 거부가 아님 | `acceptedOrUncertainDeliveryDoesNotOpenFallbackBeforeTheJudgmentDeadline` (신규) |
| 마감 후 AI 없는 검토·HELD·직접 정상/위반·DEFER | `judgmentDeadlineOpensReviewWithoutAiAndOperatorDecisionAppliesDirectly`, `violationDecisionWithoutAiConfirmsDirectly`, `deferWithoutAiKeepsReviewPendingAndAppliesNothing` |
| 늦은 AI 판정이 결정 전 검토에 연결되고 version 증가 | `lateJudgmentAttachesToOpenReviewAndDecisionGoesThroughAgent` |
| PII 확정 판정/사람 결정 후 늦은 판정 거부 | `lateDefiniteJudgmentOnPiiCaseIsRejectedWhileReviewIsOpen`, 위 deadline 시험 |
| BE handoff 뒤 늦은 Agent가 같은 실행 결과 수신 | `ruleKickApprovalNotTakenByAgentIsHandedOffAfterTheDeadline`, `agentCommandLeftUnexecutedIsExecutedAfterTheDeadline` |
| 취소·권한·대상 버전 재검증 실패 | `handoffThatFailsRevalidationIsRecordedAsFailed`, `handoffRejectedByOperatorAuthorityIsRecordedWithItsCode`, `kickThatFailsAtExecutionIsRecordedAsFailed` |
| 방송 종료 후 변경 거부/이력 유지 | `endedLiveBlocksHumanApply`, `committedLiveEndInvalidatesOnlyKickWorkAndPreservesHistoryAndDeliveryOutcomes` |
| 접수 응답 유실·상태 조회·lease fencing | `crashAfterAgentAcceptedIsRecoveredByStatusWithoutAnotherPost`, `expiredLeaseCanBeRecoveredButOldOwnerCannotOverwrite` |
| 거부 예산·불확실성 유지·재시작·Rabbit due time | 기존 capacity/Retry-After Outbox/Rabbit 시험 |

```bash
./gradlew test --tests '*LiveModerationIntegrationTest' --tests '*AgentOutboxTest' \
  --tests '*AgentRabbitIntegrationTest' --console=plain
./gradlew build --console=plain
# Agent main 반영 후 캐시 없이 전체 재검증
./gradlew build --rerun-tasks --console=plain
```

집중 회귀와 최종 전체 빌드 모두 성공했다. 전체 결과는 **51개 suite / 554개 test**,
실패·오류·건너뜀 0개다. 업무 코드의 기존 동작과 마감 정책은 수정하지 않았다.
Agent의 저장소 표준 unittest 실행으로 공동 실행기 회귀 **12개**도 통과했다.

```bash
# Agent 루트
PYTHONDONTWRITEBYTECODE=1 uv run --locked python -m unittest discover \
  -s tests -p test_joint_controlled.py -v
```

위 회귀는 실제 Spring/DB의 계약 증거지만 실제 다중 Agent의 마감 경쟁을 모두 재현한 증거는 아니다.
#76에서 실시간 3분 fallback은 이미 검증했다. 현재 일반 경로의 15분 마감을
짧게 바꾸거나 기능을 꺼서 정상 결과를 만든 것은 아니다.

## 6. Agent main 병합 후 잔여 공동 검증

아래는 Agent 담당자의 보고서 인용이 아닌 **BE에서 다시 실행한 결과**다.
새 프로젝트 7개 모두 PASS이며, 운영 DB 보정이나 deadline 단축은 하지 않았다.
로컬 증거는 `integration-results/liveguard-joint96-be78-main-<suite>-remaining.json`이다.
기존 시험도 `main-normal-{agent,backend,recovery}`, `main-loss-{agent,backend}`,
`main-capacity-final-capacity` 보고서에서 새 Agent revision으로 모두 통과했다.
이전 `e720ce65` 보고서는 과거 증거이며 현재 버전의 실행 결과와 혼합하지 않는다.

| suite | 실제 관측·불변식 | 기동 제외 시간 |
|---|---|---|
| receipt | 실제 API 202 뒤 응답 단절, POST 1회 → BE 자동 GET 1회 → ACCEPTED, attempts 2·uncertain=true 이력 유지, Inbox 1개·SUCCEEDED | 5.522초 |
| receipt-rabbit | 기존 Rabbit 알림 → BE HTTP 발송에서 같은 접수 유실 복구·중복 방지 | 6.070초 |
| race | ANALYZE RUNNING 중 실제 승인, resume QUEUED 유지, run/resume 중복 8건, 총 job 2개·CONTEXT 1개, 최종 SUCCEEDED | 5.321초 |
| stale | 실제 소유 Worker SIGSTOP, 30초 lease 뒤 다른 Worker attempt 2·WAITING_REVIEW, 지연 응답과 SIGCONT 뒤 이전 프로세스가 살아 있어도 job/checkpoint/pending writes/blob 불변, 최종 결정 SUCCEEDED | 42.345초 |
| load-burst | 두 실제 live 24/24 SUCCEEDED, 모든 Outbox ACCEPTED, 첫 4 admission 양쪽 방송 포함, Graph 최대 4/Worker 최대 2 | 79.861초 |
| load-steady | 약 초당 1건으로 두 방송 24/24 SUCCEEDED, 같은 상한·공정성 유지 | 72.774초 |
| deadline | 실제 기본 3분 fallback·KICK handoff, 결정 전후 늦은 조사와 늦은 KICK 재개, 승인·단일 업무 효과 보존 | 201.323초 |

### 마감의 양쪽 결과

- AI 없는 CONTEXT 검토는 사건 생성 **185.283624초 후** 생성됐다. findings는 비어 있었고,
  Worker 정지 중 ACCEPTED/QUEUED 작업은 RUNNING 슬롯을 차지하지 않았다.
- 사람이 먼저 CONFIRM_NORMAL한 사건은 늦은 ANALYZE가 모델 호출 전에
  `FAILED / STALE_EVIDENCE`로 끝났다. 사람 결정과 findings 없음은 유지됐다.
  이 명시적 거절을 전체 정상 조사 성공으로 표시하지 않는다.
- 미결정 fallback 사건의 늦은 AI는 기존 CONTEXT 검토에 붙었다. 검토를 중복 생성하지 않고
  정상 판정·운영자 결정·동일 task의 resume SUCCEEDED까지 확인했다.
- KICK은 실제 승인 **181.017657초 후** BE가 실행했다. action SUCCEEDED·현재 kick 1개이며,
  이후 늦은 Agent resume도 SUCCEEDED지만 동일 action·실행 시작 시각·업무 효과는 바뀌지 않았다.

### 유한 부하와 초기 설정 판단

| 입력 | 생산·소진 시간 | 처리량 | latency P50 / P95 | 최대 admission 대기 |
|---|---|---|---|---|
| burst 24 | 78.431초 | 0.3060건/초 | 14.789 / 67.798초 | 65.793초 |
| steady 24 | 71.337초 | 0.3364건/초 | 0.724 / 47.900초 | 41.758초 |

기동·인증 전처리를 제외한 값이다. 통제 모델의 실제 HTTP 동시 호출 관측 최대는 1이므로,
모델 동시성 4를 포화시켰거나 실제 sLLM 처리량을 측정했다고 주장하지 않는다.
두 경로 모두 Graph 4 / Worker 2 / 모델 전역 4, 30 RPM·60000 TPM을 완화하지 않았다.
관측상 quota 대기로 admission 시간이 늘어나므로 초기 설정은 유지하고, Worker/Graph 수만
늘려 처리량을 약속하지 않는다. Inbox 10000도 운영 안전 용량 인증이 아니며 실제 모델 환경에서
대기 시간·토큰량을 측정한 뒤 별도로 산정한다. capacity 2는 거부 시험만을 위한 값이다.

### 완료 판단과 범위 밖

#78의 통제 환경 내 정상 흐름·접수 불확실성·장애·중복·마감 양쪽 시나리오와 재현 증거를
확보했다. 확인한 업무 시나리오에서 제품 오류나 업무 중복은 발견하지 않았으며,
용량 실행기의 기동 전 provenance 수집 문제만 BE에서 수정·재검증했다.
Agent 저장소의 실행기나 제품 코드는 수정하지 않았다.
시험 종료 후 본인 프로젝트의 컨테이너·네트워크·DB/Rabbit 볼륨과 생성한 private env를
정리했다. 안전한 로컬 JSON·이 보고서·재사용 이미지는 보존했다.

모든 interleaving의 수학적 보장, 장시간 soak/대규모 운영 부하, Rabbit broker 자체 장애,
실제 외부 모델 정확도·처리량은 이번 완료 판단에 포함하지 않는다. 실제 모델은 #78의 범위 밖이다.
이 제한은 미확인 제품 버그나 기존 #96 미완료로 바꾸어 새 이슈를 만들지 않는다.
전체 시스템 exactly-once, 과거 action 성공과 현재 제재 유효성의 동일성도 보장하지 않는다.
