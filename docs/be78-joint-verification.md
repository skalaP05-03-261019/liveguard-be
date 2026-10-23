# BE #78 공동 E2E 검증 보고서

검증일: 2026-10-23. 실제 Spring BE와 Agent #96의 통제 실행기를 사용했다.
운영 DB 보정, 승인 우회, fallback/실행기 비활성화, 외부 모델 호출은 하지 않았다.
Agent Graph/Worker 시험을 복제하지 않고, 기존 실행기 뒤에 **BE 상태 불변식 검증**을 추가했다.

## 1. 버전과 환경

| 대상 | 고정 값 |
|---|---|
| 실제 업무 BE | `acd3e98f7d2b1a74505d9f89bfb56dffe1082673` |
| Agent/API/Worker/Business MCP/통제 fixture | `e720ce65f859a0312c4083e17b39974cf42c512d` |
| BE image ID | `sha256:466c0db02d57bac87c389d15667ac636e8a99aab2803e8bd836b863937e2f480` |
| Agent 소스 fingerprint | `b57f8636518c0a0a86c252bed58112becf276b82944a29e7c96d23e335fb7383` |
| 정책 v3.1 원문 SHA256 | `c34ae086df1254ee32190f3a02839aaa4edda2e6e9450b4b9b814dd3787e8f2d` |
| 정상 전체 시험 Agent image ID | `sha256:c1f561f7ab4a4a0ccf5637144460e1c6085f6813fea3b509ed3420f4863d981e` (최초 실행) |
| 응답 유실 시험 Agent image ID | `sha256:1a2d3cc2d6955685ee82bfe5f090ce610b5fa9e206cb4119d05bbfbccd3f9a34` |
| 용량 시험 Agent image ID | `sha256:6e368ab60fcfefc0ee2dfe1bd6543627604fae407fe932f796f14b36c57173d8` |

실제 이미지와 각 실행의 ID·fingerprint는 로컬 JSON 보고서에 별도로 저장한다.
Agent 실행기는 작업 트리 빌드이므로 `agentDirty`도 기록한다. 본 검증의 tracked 실행 소스는
고정 커밋과 같고, 기존 untracked 중첩 clone은 Docker context와 fingerprint에서 제외된다.
이를 clean checkout 인증으로 바꾸어 표기하지 않는다. 재빌드의 image ID는 달라질 수 있다.

구성: API 2 / Worker 2 / MCP 2, 기존 HAProxy, 공유 PostgreSQL Inbox·Checkpointer,
별도 실제 BE DB. BE 발송은 DB polling, 일반 판정·handoff 마감은 15분이다.
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

기본 host port 18080/18001/18002/18004를 사용하므로 순차 실행한다.
동시 다른 시험이 해당 포트를 사용하면 시작을 중단하고, 다른 담당자의 프로세스를 종료하지 않는다.
초기화/기동 오류는 성공으로 대체하거나 자동 재시도하지 않는다.
기존 환경파일·보고서·프로젝트 볼륨이 있으면 `run`은 덮어쓰거나 지우지 않는다.

출력: 무시된 `integration-results/<project>-{agent,backend,recovery,capacity}.json`.
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

`down`만으로 볼륨을 삭제하지 않는다. loss/capacity도 각 suite/env/project를 맞춘다.
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

정상 최종 재현의 full 실행 시간(기동 제외)은 **61.206초**, 모델 11회(정상 10 + timeout 1)다.
응답 유실 full 실행은 **38.648초**, 모델 11회(정상 10 + 429 1)다.
시나리오별 elapsed는 입력부터 순차 결정까지이므로 마지막 사건에는 앞선 사건의 운영자 처리 대기가 포함된다.
Inbox wait/lastAttempt는 기존 실행기가 제공하는 최신 admission 기준이며 전체 재시도 누적 CPU 시간이 아니다.

용량 시험에서 거부 작업은 최초 next_attempt_at 전 **1.234533초** 시점에도 attempt=1/RETRY였다.
최종 BE attempt=3, Agent operation/job은 1개, uncertain=false, payload hash 동일이었다.
Worker 조립/용량 해제 전에 두 번째 거부가 가능하므로 attempt=2만을 합격 조건으로 강요하지 않는다.
서버 deadline은 HTTP 응답 수신 때 계산되고 Outbox updated_at은 그 이후이므로
`next_attempt_at - updated_at`이 9.999363초였던 것을 10초 위반으로 판정하지 않는다.
저장된 deadline 전 미발송과 최종 접수 시각을 별도로 검증한다.

## 4. 결과 연결 예

응답 유실 실행:

- case `102d1f11-2127-4260-a7a0-c256265444c0`
- task `136b1939-d827-4395-94f0-c97c4a813d3d`
- thread `case:102d1f11-2127-4260-a7a0-c256265444c0:analysis:1`
- review `c4eb041c-c23c-4491-8069-c9454103e4f2`
- decision `882d6b31-3834-4bee-8e53-2fd931c23c2f`
- action `8f9006fd-535f-4b37-9b9f-dfa0680b9ae4`
- operation `resume:882d6b31-3834-4bee-8e53-2fd931c23c2f`

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
```

집중 회귀와 최종 전체 빌드 모두 성공했다. 전체 결과는 **51개 suite / 554개 test**,
실패·오류·건너뜀 0개다. 업무 코드의 기존 동작과 마감 정책은 수정하지 않았다.

위 회귀는 실제 Spring/DB의 계약 증거지만 실제 다중 Agent의 마감 경쟁을 모두 재현한 증거는 아니다.
#76에서 실시간 3분 fallback은 이미 검증했다. 현재 일반 경로의 15분 마감을
짧게 바꾸거나 기능을 꺼서 정상 결과를 만든 것은 아니다.

## 6. 남은 공동 검증과 제한

확인한 실제 BE 시나리오에서 제품 오류나 업무 중복은 발견하지 않았다.
다음은 PASS로 주장하지 않으며 기존 skalaP05-03-261019/liveguard-agent#96과
BE #78의 잔여 공동 범위로 추적한다. 이 보고서만으로 #96 전체 완료/무제한 부하를 인증하지 않는다.

| 잔여 항목 | 재현 조건 / 담당 / 영향 |
|---|---|
| 실제 Agent 접수 응답 유실 → BE 자동 GET 복구 | 기존 BE HTTP fixture 시험은 통과. 실제 API 앞 응답 유실 주입은 Agent/BE 공동 실행기 추가 필요. 영속 접수 불확실성의 종단 검증 |
| 실제 BE 마감과 늦은 Agent의 동시 경합 | 기존 BE 회귀는 통과. 3분 fallback/handoff와 실제 다중 Worker 동시 진행을 별도 프로젝트에서 장시간 재현. BE+Agent, 마감 양쪽의 승인·멱등성 |
| 늦은 프로세스 결과 fencing/ANALYZE-resume 경합의 모든 타이밍 | SIGKILL 회수·다른 Worker 승인 재개는 통과. 취소를 무시하는 호출 등은 Agent #96의 별도 장애 주입 필요 |
| 장시간·대량 유입/Rabbit 다중 Agent 조합 | 이번 4:2 공정성/용량2 시험은 작은 결정적 입력. 기존 Rabbit BE 회귀와 #76 연결 시험은 별도 증거. Agent+BE+Infra, 처리량·운영 상한 |
| 실제 모델 | 외부 전송·비용 없이 통제 모델만 실행. 비용/권한/환경을 합의한 별도 opt-in 시험 필요 |

잔여 항목은 새로운 제품 버그로 꾸며 중복 이슈를 만들지 않는다. 실행 중 발견되는 실제 오류는
안전한 재현 조건·고정 커밋·담당 저장소·영향 범위를 붙여 해당 저장소의 후속 이슈로 분리한다.
전체 시스템 exactly-once, 실제 모델 정확도, 과거 action 성공과 현재 제재 유효성의 동일성은 보장하지 않는다.
