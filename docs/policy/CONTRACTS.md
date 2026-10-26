# LIVE:GUARD | 데이터와 인터페이스 계약

> 개정일: 2026-10-20 | 정책 의미 확정, 신규 API와 필드 이름은 계약 초안
> 
> 
> [POLICY.md](POLICY.md)를 정책 원본으로 사용한다. 현재 문서는 기존 전체 API를 대체하는 구현 완료 명세가 아니다.
> 

## 1. 공통 식별자

외부 JSON은 camelCase, Graph State는 snake_case다. threadId와 thread_id는 같은 값이다. 각 승인 대기에서 저장된 값으로 재개한다. 시각 전달은 UTC ISO8601, 표시 시간대는 UI에서 변환한다. 집계 시각 컬럼은 TBD-05다.

| 값 | 계약 |
| --- | --- |
| userId | 세션/사건에서 서버 확인 |
| liveId | 방송 세션 범위, 모든 정책 조회와 실행에 포함 |
| channelId | 고정 채널 식별자가 있다면 별도, liveId 대체 불가 |
| caseId | 사건 식별자, 다중 유형은 같은 사건으로 보존 |
| reviewId | 문맥 결정 또는 강퇴 심사 요청 식별자 |
| decisionId | 서버에 저장된 운영자 결정 |
| actionId | 실제 실행의 멱등 식별자 |

## 2. 공통 타입

| 타입 | 값 | 의미 |
| --- | --- | --- |
| FilterResult | PASS, REVIEW, HOLD, FLOOD | REVIEW는 현재 확정 경로에서 미사용 |
| HandlingRoute | PASS, RULE_CONFIRMED, CONTEXT_REVIEW, PII_INVESTIGATION, FLOOD_REVIEW | BE 분류와 확정 경로 표현 초안 |
| Verdict | NO_VIOLATION, VIOLATION, NEEDS_REVIEW | AI 제안 또는 확정 결과, 출처/상태 함께 저장 |
| Visibility | PUBLIC, HELD, HIDDEN | 공개 범위 |
| Category | PROFANITY, HARASSMENT, FLOOD, PHISHING, PII_EXPOSURE | 유형별 TIMEOUT 계산 |
| ReviewKind | CONTEXT, KICK | 문맥 판단과 강퇴 승인을 구분 |
| ContextDecision | CONFIRM_NORMAL, CONFIRM_VIOLATION, DEFER | DEFER는 공개 승인 아님 |
| KickDecision | APPROVE, REJECT | 강퇴 결정 |
| ReviewStatus | PENDING, DECIDED | 결정 상태, 실행 상태와 분리 |
| ActionType | HIDE, TIMEOUT, RELEASE_HELD, KICK | 실행 단위 |
| ActionStatus | PENDING, RUNNING, SUCCEEDED, FAILED | 미확인 결과는 observation과 구분 |
| Observation | CONFIRMED, UNKNOWN | UNKNOWN일 때 applied=null |

### 관리자 관제 계약 (#88)

- 로그인과 `GET /api/auth/me`의 `Me.capabilities`: PLATFORM_ADMIN은 `[MONITOR, REVOKE]`, 그 외는 `[]`. DECIDE·RAW_TEXT는 예약 값이며 현재 부여하거나 설정으로 활성화할 수 없다. 채널 담당 운영자의 기존 권한은 channelIds와 채널 역할로 유지하며 빈 capabilities가 그 권한을 제거하는 뜻은 아니다.
- `Me.channelIds`는 실제 소속만 반환한다. 관리자 채널 탐색은 새 `GET /api/channels`를 사용한다. `{channelId, name, live}` 배열이며, 관리자는 전체·그 외는 소속만 조회한다. live는 진행 중인 기존 Live DTO 또는 null이다.
- MONITOR는 진행 중 방송 조회, liveId 기반 메시지 이력·채팅 SSE·관제 SSE, 사건 목록·상세, CONTEXT/KICK 검토 목록에 적용한다. 이력·SSE의 비공개 본문은 POLICY §4 기준으로 마스킹한다.
- REVOKE는 기존 `POST /api/findings/{findingId}/revoke`에 적용한다. 사유·expectedCaseVersion·clientRequestId 및 감사 기록은 기존 계약을 따른다.
- 관리자는 채팅 전송·방송 시작/종료·문맥/강퇴 결정이 403이다. `/internal/**`의 서비스 토큰 인증은 변하지 않는다.
- 관제 SSE는 기존 TOOL_START·TOOL_END·TASK_FAILED 등을 실시간 전달할 뿐이다. 영속 처리 로그 조회, 모델 내부 추론, 관리자 통계 API는 추가하지 않는다.
- 강퇴 검토 카드(`KickReviewCard`)는 최종 결정 `decision`(value·decidedAt, 결정 전 null)과 그 결정의 실행 기록 `execution`(ActionStatus·Observation, 기각이거나 명령 전이면 null)을 준다. 새로고침한 운영자·관리자 화면이 실시간 이벤트 없이도 승인·기각과 실행 결과를 구분한다. 승인 저장은 실행 완료가 아니고, 명령만 있으면 PENDING·UNKNOWN이다. 강퇴 검토의 `ReviewStatus`에는 INVALIDATED(승인 뒤 재검증 실패, 방송 종료)가 있으며 그 전에 저장된 결정은 남는다.

## 3. 저장 객체

| 객체 | 핵심 필드 | 불변식 |
| --- | --- | --- |
| Message | messageId, liveId, userId, visibility, version, holdReasons | 실제 상태와 원인 분리 |
| Case | caseId, liveId, userId, confirmationStatus, policyVersion | 미확정 AI 제안을 집계하지 않음 |
| Finding | findingId, caseId, category, verdict, source, confirmedAt, revokedAt | 유형별 근거, 같은 사건의 중복 유형 제거 |
| ContextReview | reviewId, caseId, threadId, aiVerdict, evidenceVersion, status | 정상 제안이어도 결정 대기 |
| KickEligibility | asOf, liveId, userId, eligibleCaseIds, count | 현재 포함, 다섯 유형 통합 |
| KickReview | reviewId, threadId, basisCaseIds, basisSnapshotId, version | 실제 심사 기준 집합 고정 |
| Reservation | reviewId, caseId, state | 대기 중 심사 중복 사용 금지 |
| Decision | decisionId, reviewId, kind, value, operatorId, decidedAt | 서버 권한 확인 |
| Consumption | caseId, reviewId, decisionId, consumedAt | 승인과 거절 모두 기록, 유형별 이력 삭제 아님 |
| Action | actionId, type, liveId, userId, decisionId, status, observation | 승인 사실과 실행 결과 분리 |
| LiveBan | liveId, userId, actionId, activeUntilLiveEnd | 24시간 만료 필드로 대체하지 않음 |

상세 DDL과 부분 유형 취소는 미정이다. Consumption은 기준 사건과 결정의 관계를 보존해야 하며 단순 사용자 카운터 초기화로 구현하지 않는다.

## 4. 채팅 수신과 프론트 응답

```json
{
  "clientMessageId": "client-example",
  "text": "<채팅 내용>"
}
```

경로 예시: `POST /api/lives/{liveId}/messages`. 서버가 사용자, 방송 활성 상태, 강퇴와 TIMEOUT 상태를 먼저 검증한다. 같은 clientMessageId 재전송은 기존 결과를 반환하며 다른 내용의 ID 재사용은 충돌이다.

| 응답 필드 | 내용 |
| --- | --- |
| messageId, visibility, version | 실제 저장 상태 |
| moderationReasonCode | PROFANITY_INCLUDED, LINK_NOT_ALLOWED, CONTEXT_PENDING 등 표현 초안 |
| moderationNotice | 작성자에게 노출 가능한 사유 |
| timeoutUntil | 실제 TIMEOUT이 적용됐을 때만 |
| reviewStatus | HELD 조사/승인 대기 표시 |

욕설 안내는 “메시지에 금지된 욕설이 포함되어 숨김 처리되었습니다.”처럼 원인을 알린다. 탐지 단어 자체를 공개 화면에 다시 출력할 필요는 없다. 링크 안내는 “이 방송에서는 링크 전송이 금지되어 있습니다.”로 하고 피싱으로 검증했다는 주장을 넣지 않는다.

| 수신 조건 | 처리 |
| --- | --- |
| PASS | PUBLIC, 조사 작업 없음 |
| 명확한 욕설/링크 | RULE_CONFIRMED, HIDDEN, 표 적용, AI 조사 없음 |
| 문맥 후보 | CONTEXT_REVIEW, HELD, AI 조사와 필수 사람 결정 |
| 개인정보 후보 | PII_INVESTIGATION, HELD, AI 조사 |
| 도배 후보 | FLOOD_REVIEW, HELD, 확정 절차 TBD-11 |
| TIMEOUT 또는 live ban 중 | 전송 거부, 새 메시지/사건 생성 금지 |

## 5. Agent 출력

```json
{
  "caseId": "case-example",
  "liveId": "live-example",
  "handlingRoute": "CONTEXT_REVIEW",
  "snapshotId": "snapshot-example",
  "evidenceVersion": 1,
  "policyVersion": "<활성 버전 미정>",
  "messageFindings": [
    {
      "messageId": "message-example",
      "verdict": "NO_VIOLATION",
      "categories": [],
      "policyIds": ["<검증된 조항>"],
      "reason": "문맥상 정상 표현이라는 제안",
      "missingFacts": []
    }
  ]
}
```

문맥 경로에서 이 정상 출력은 공개 명령이 아니다. CONTEXT 검토 요청을 생성하고 interrupt한다. 판정 제안은 modelFindings에, 사람의 최종 판단은 별도 decision과 confirmed findings에 저장한다. 기존 모델 결과를 사람 판단으로 덮어쓰지 않는다.

VIOLATION은 유효 유형, 실제 증거, 정책 참조가 필요하다. TARGET 메시지마다 정확히 한 번 결과를 내고 CONTEXT는 제재 대상에 몰래 포함하지 않는다. 임의 TIMEOUT 기간이나 승인 여부를 모델에 위임하지 않는다.

## 6. 두 종류의 이력 조회

| 조회 | 범위와 목적 |
| --- | --- |
| timeoutHistory | 같은 userId/liveId, 현재 제외, 유형별 과거 확정 사건. 소진 후에도 보존. 조회 기간 TBD-03 |
| kickEligibility | 같은 userId/liveId, 현재 포함 최근 24시간, 다섯 유형 통합 distinct caseId, 취소와 소진 제외 |
| kickExecutionBasis | 특정 승인 reviewId의 고정 기준 집합 검증. 자기 결정의 소진은 허용 |

```
신규 강퇴 심사 후보:
  확정 + 같은 사용자/방송 + 24시간 범위
  - 취소 - 소진 - 중복
  요청 생성 시 다른 대기 심사의 예약과 충돌 검사

승인 실행 근거:
  저장된 승인 review의 기준 사건 집합
  + 현재에도 유효한 확정 사실과 시간창
  + consumption이 바로 이 review/decision의 것인지 검사
  다른 심사 소진 또는 취소는 허용하지 않음
```

소진 제외 신규 집계를 승인 실행 검증에 그대로 사용하지 않는다. 임의의 새 사건으로 승인 기준을 교체하지 않는다. basisCaseIds가 3개보다 많을 때 선택 방식은 TBD-06이다.

## 7. 조회 및 실행 도구

| 도구 | 역할 |
| --- | --- |
| get_moderation_case | liveId, 대상, 증거 버전, 처리 경로와 가시성 조회 |
| get_chat_context | 마스킹된 문맥 조회 |
| get_user_history | TIMEOUT 유형별 이력과 조회 기준 |
| get_kick_eligibility | 신규 강퇴용 통합 사건 집계 |
| get_kick_review_basis | 원 승인 기준과 소진 소유권 조회 |
| get_channel_user_role | 사칭 조사, 조회 대상은 liveId에 연결 |
| get_stream_info / get_security_facts | 방송 사실과 개인정보 근거, UNKNOWN 허용 |
| search_policy / get_policy_section | 정책 버전 일치 검색과 원문 |
| get_action_status | 실제 실행 상태 확인 |
| hide_message / timeout_user | 검증된 표 기반 실행 |
| release_message | 자기 hold 원인만 해제, 문맥은 사람 정상 결정 필수 |
| kick_user | 승인과 기준 재검증 이후 live ban 실행 |

링크의 공식 여부는 링크 금지 예외가 아니다. 기존 get_official_links가 남아 있어도 링크 허용 여부를 뒤집는 데 쓰지 않는다. Business MCP가 자체 제재표를 별도로 계산하지 않고 BE 규칙을 사용한다.

## 8. 검토 API 초안

| 경로 | 목적 |
| --- | --- |
| GET /api/reviews?liveId=…&kind=CONTEXT | 문맥 검토 목록 |
| POST /api/context-reviews/{id}/decision | 정상/위반 확정 또는 보류 |
| GET /api/reviews?liveId=…&kind=KICK | 강퇴 승인 목록 |
| POST /api/kick-reviews/{id}/decision | 승인 또는 거절, 기준 사건 소진과 함께 저장 |
| POST /agent/tasks/{id}/resume | 저장된 결정으로 원 threadId 재개 |
| POST /internal/kick-reviews/{id}/revalidate | 자기 승인 기준 검증 |

```json
{
  "clientRequestId": "request-example",
  "expectedReviewVersion": 1,
  "decision": "APPROVE",
  "reason": "기준 사건 확인 후 승인"
}
```

위 예시는 KICK용이다. CONTEXT는 CONFIRM_NORMAL/CONFIRM_VIOLATION/DEFER를 사용하며 위반 결정에는 서버가 검증할 유형과 증거가 필요하다. 담당 운영자 권한과 liveId 범위를 검사한다.

결정 완료와 기준 사건 소진은 일관되게 기록한다. 중복 요청은 같은 결정 결과를 반환하고 상반 결정이나 오래된 버전은 충돌 처리한다. 결과 저장 성공은 실제 강퇴 성공을 의미하지 않는다. 보류는 최종 정상/위반 확정으로 간주하지 않는다.

## 9. 강퇴 실행 계약

| 필드 | 내용 |
| --- | --- |
| actionId, idempotencyKey | 동일 실행은 재시도에도 유지 |
| liveId, subjectRef | 검증된 방송과 계정 |
| reviewId, decisionId | 서버에 저장된 APPROVE 참조 |
| basisSnapshotId, validationRef | 운영자 기준과 최신 재검증 |
| policyVersion | 적용 버전 |
| effect | 현재 방송 퇴장, 방송 종료까지 입장과 채팅 금지 |

KICK에 durationSeconds=86400을 전달하지 않는다. TIMEOUT의 86400초와 KICK의 방송 종료 기반 효력을 구분한다. 새 liveId에 같은 userId가 들어오면 과거 live ban으로 차단하지 않는다.

| 상태 | 처리 |
| --- | --- |
| 승인 없음 | 실행 거부 |
| 기준 사건 취소/시간창 이탈 | 실행하지 않음, 후속 결정 TBD-06 |
| 자기 승인 결정에 의한 소진 | 그 사실만으로 거부하지 않음 |
| 다른 심사에 의한 소진 | 재사용 금지 |
| 방송 종료 | 활성 강퇴 금지 생성 안 함, 결과 코드 별도 합의 |
| 동일 action 성공 기록 | 효과를 반복하지 않고 기존 결과 반환 |

## 10. 재시도와 실행 결과

```json
{
  "actionId": "action-example",
  "type": "KICK",
  "status": "FAILED",
  "observation": "CONFIRMED",
  "applied": false,
  "attemptCount": 3,
  "retryCount": 2,
  "error": {
    "code": "<합의된 일시 오류 코드>",
    "message": "재시도 한도 소진"
  }
}
```

확인된 일시 오류에 한해 최초 1회 + 최대 재시도 2회다. 위 예시는 실제 미적용이 확인된 경우다. 응답 유실은 observation=UNKNOWN, applied=null로 관리하며 확정 실패라고 표기하지 않는다. 코드 목록과 간격 및 수동 재시도는 TBD-07이다.

재시작이나 Queue 재전달로 예산을 초기화하지 않는다. 알림 재시도와 강퇴 실행 재시도를 구분한다. 소진 기록은 kick 실패로 자동 되돌리지 않는다. 기존 HIDE와 TIMEOUT도 강퇴 실패나 거절만으로 해제하지 않는다.

## 11. Graph State 및 재개

| 필드 | 저장 내용 |
| --- | --- |
| thread_id, task_id, case_id, live_id, user_id | 서버 발급 또는 조회 식별자 |
| handling_route, review_kind, stage | 규칙 경로/문맥/개인정보/도배와 현재 승인 종류 |
| snapshot_id, evidence_version, policy_version | 조사 기준 |
| model_findings, policy_evidence | AI 제안과 근거 |
| context_review_id, context_decision_id | 문맥 심사 연결 |
| kick_review_id, kick_decision_id, basis_case_ids | 강퇴 심사 연결 |
| consumption_refs | 결정 시 사용된 사건 소진 참조 |
| action_id, idempotency_key | 실행 중복 방지 |
| kick_attempt_count, kick_retry_count | 지속 저장하는 예산 |
| checkpoint_ref, execution_results | 진행 위치와 실제 결과 |

```json
{
  "eventId": "event-example",
  "taskId": "task-example",
  "kind": "RESUME_REVIEW",
  "reviewKind": "KICK",
  "threadId": "<중단 당시와 동일한 값>",
  "reviewId": "review-example",
  "decisionId": "decision-example"
}
```

중단 앞의 검토 요청 생성도 reviewId로 멱등 처리한다. 재개는 메시지의 decision 주장 대신 업무 DB 저장본을 읽는다. 한 Graph의 문맥 결정과 후속 강퇴 결정을 혼동하지 않는다.

## 12. SSE와 개인정보

| 이벤트 | 핵심 내용 | 대상 |
| --- | --- | --- |
| CHAT | 허용된 메시지와 visibility | PUBLIC은 시청자, HELD/HIDDEN은 본인과 담당만 |
| MESSAGE_VISIBILITY_CHANGED | messageId, visibility, version | 원문 제거는 ID만 |
| MESSAGE_RESTORED | 실제 PUBLIC이 된 메시지 | 해당 방송 |
| CONTEXT_REVIEW_REQUIRED | reviewId, caseId, aiVerdict | 담당 운영자 |
| KICK_REVIEW_REQUIRED | reviewId, 통합 사건 수 | 강퇴 승인 운영자 |
| REVIEW_DECIDED | reviewKind, decisionId, 결정 | 담당 운영자 |
| ACTION_APPLIED / ACTION_FAILED | actionId, 상태, observation | 본인과 담당 운영자 |
| LIVE_ACCESS_BLOCKED | liveId, 방송 종료까지 금지 안내 | 대상 계정 |

공통 envelope는 eventId, occurredAt, entityVersion, payload다. 중복과 오래된 버전을 무시하고 재접속 조회에도 가시성 인가를 적용한다. HELD 원문을 PUBLIC 이벤트로 먼저 발행하지 않는다. 승인 이벤트만으로 강퇴 완료를 표시하지 않는다.

## 13. 검증 항목

| 항목 | 통과 기준 |
| --- | --- |
| 명확 규칙 | AI 없이 확정 가능, 근거와 정책 기록 |
| 문맥 정상 제안 | 운영자 결정 전 HELD 유지 |
| 표 | PHISHING 600/600/86400, PII 0/600/86400, 다중 최장 하나 |
| 집계 | 유형별 과거와 통합 현재 포함 집계 분리 |
| 소진 | 결정 1회와 기준 사건 소진 일관 저장, 위반 사실 보존 |
| 승인 재검증 | 자기 소진 기준 허용, 타 심사 재사용 금지 |
| 생명주기 | 방송 종료 기반 강퇴, 새 liveId 승계 없음 |
| 신뢰성 | 재시작 후 승인 대기, 중복 승인 효과 1회 |

구체 테스트 사례는 PROJECT.md의 AC 목록을 따른다. 미정 항목은 fixture와 정책 확정을 구분해 시험한다.