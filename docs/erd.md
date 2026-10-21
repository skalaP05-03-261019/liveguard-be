# LIVE:GUARD ERD v0.5

작성: Backend 1 · 상태: **구현 기준안 (팀 확정 필요 항목은 6절)**

## 1. 구조 요약

```
채팅 POST → 1차 필터 ─ PASS ──────────────────────────────► PUBLIC
                      └ REVIEW/HOLD/FLOOD → 사건 생성 (HOLD·FLOOD는 즉시 HELD)
                                               ↓
                        Agent 조사 (MCP 조회 도구 + RAG 검색)
                                               ↓
                        판정 제출 → Backend 업무 규칙
                          ├ VIOLATION     → 메시지 HIDDEN, 누적 횟수 계산
                          ├ NO_VIOLATION  → HELD였으면 PUBLIC 복원
                          └ NEEDS_REVIEW  → 상태 유지, 운영자 검토 목록
                                               ↓
                        누적 3회 도달 → 강퇴 검토 생성
                          → Agent가 kick_user 앞에서 interrupt (checkpointer 저장)
                          → 운영자 APPROVE / REJECT → 같은 thread_id로 재개
                          → APPROVE면 kick_user 실행 (destructiveHint)
```

- 메시지 가림·복원은 **Backend 업무 규칙**이다. Agent는 판정만 제출하고 변경 도구를 호출하지 않는다.
- Agent의 변경 도구는 `kick_user` 하나이며 반드시 interrupt로 멈춘다 (강의자료 표 6-3 Guardrails 최소 기준).
- 운영자의 오탐 해제는 사람이 직접 하는 Backend 기능이다 (Agent 도구 아님).

## 2. 관계도

```mermaid
erDiagram
    USERS ||--o{ CHANNEL_MEMBERS : joins
    CHANNELS ||--o{ CHANNEL_MEMBERS : has
    CHANNELS ||--o{ MESSAGES : contains
    USERS ||--o{ MESSAGES : writes
    CASES ||--o{ MESSAGES : groups
    CASES }o--o| KICK_REVIEWS : "counted in"
    KICK_REVIEWS ||--o| KICKS : executes

    USERS {
        uuid id PK
        text username UK
        text password_hash
        text nickname
        text role "VIEWER MODERATOR PLATFORM_ADMIN"
    }
    CHANNELS {
        uuid id PK
        text name
        jsonb official_hosts "normalized hosts"
        jsonb official_events
    }
    CHANNEL_MEMBERS {
        uuid channel_id PK, FK
        uuid user_id PK, FK
        text member_role "VIEWER STREAMER OFFICIAL_STAFF MODERATOR"
    }
    MESSAGES {
        uuid id PK
        uuid channel_id FK
        uuid author_id FK
        uuid client_message_id
        text text "1..500"
        text normalized_text
        text filter_result "PASS REVIEW HOLD FLOOD"
        text visibility "PUBLIC HELD HIDDEN"
        uuid case_id FK
        int version
        timestamptz sent_at
    }
    CASES {
        uuid id PK
        uuid channel_id FK
        uuid user_id FK
        text filter_result "REVIEW HOLD FLOOD"
        jsonb matching_tags "1차 필터 매칭 태그 (InternalCase.matchingTags)"
        text status "ANALYZING NO_ACTION HIDDEN NEEDS_REVIEW KICK_REVIEW_PENDING CLOSED FAILED"
        text verdict "VIOLATION NO_VIOLATION NEEDS_REVIEW"
        text category "PROFANITY HARASSMENT FLOOD PHISHING"
        text reason
        jsonb evidence_message_ids
        jsonb policy_snapshots
        bool counted "counts toward kick"
        uuid kick_review_id FK
        timestamptz released_at
        uuid released_by
        text thread_id
        uuid trace_id
        int version
        timestamptz created_at
        timestamptz judged_at
    }
    KICK_REVIEWS {
        uuid id PK
        uuid channel_id FK
        uuid user_id FK
        uuid trigger_case_id FK
        text summary "agent summary for moderator"
        text status "PENDING APPROVED REJECTED EXECUTED FAILED"
        uuid decided_by
        text decision_reason
        uuid client_request_id
        int version
        timestamptz created_at
        timestamptz decided_at
    }
    KICKS {
        uuid id PK
        uuid review_id UK
        uuid channel_id FK
        uuid user_id FK
        text idempotency_key UK
        timestamptz starts_at
        timestamptz expires_at
        timestamptz revoked_at
    }
```

Python 쪽: `rag.policy_chunks`(rag-mcp), LangGraph checkpointer(Agent). Flyway는 `platform`·`guard` 스키마만 관리한다.

v0.4 대비 삭제: tenants, holds·visibility_reasons, case_messages(→ `messages.case_id`), case_snapshots, message_judgments, findings 4종, action_plans, actions, appeals, review_decisions, tasks(lease·fence), outbox, processed_events, notifications, stream_events, policy_sets, client_requests, security_facts. **25개 → 7개.**

## 3. 제약·인덱스

| 테이블 | 제약·인덱스 | 이유 |
| --- | --- | --- |
| messages | `UNIQUE(channel_id, author_id, client_message_id)` | 재전송 중복 제거 |
| messages | 인덱스 `(channel_id, sent_at DESC)` | 이력 조회 |
| messages | 인덱스 `(channel_id, author_id, normalized_text, sent_at)` | 도배 10초 3회 판정 |
| cases | 인덱스 `(channel_id, user_id, judged_at) WHERE counted AND released_at IS NULL AND kick_review_id IS NULL` | 누적 횟수 계산 |
| kick_reviews | 부분 유일 `(channel_id, user_id) WHERE status = 'PENDING'` | 같은 사용자에 대한 검토 1건 |
| kick_reviews | `UNIQUE(decided_by, client_request_id)` | 결정 버튼 중복 클릭 |
| kicks | `UNIQUE(review_id)`, `UNIQUE(idempotency_key)` | 강퇴 중복 실행 방지 (Recovery 재시도 대비) |
| kicks | `CHECK (expires_at - starts_at <= interval '86400 seconds')` | 강퇴 기간 상한 |
| kicks | 인덱스 `(channel_id, user_id, expires_at) WHERE revoked_at IS NULL` | 전송·구독 차단 확인 |

## 4. 핵심 쿼리

누적 위반 횟수 (6절 기본값 기준):

```sql
SELECT count(*) FROM guard.cases
WHERE channel_id = :channelId AND user_id = :userId
  AND counted AND released_at IS NULL
  AND kick_review_id IS NULL                    -- 이미 검토에 사용된 사건 제외
  AND judged_at >= :now - interval '24 hours';
```

판정 제출 처리 (Backend, 한 트랜잭션):

```
1. cases.version = expectedVersion 확인 (다르면 409)
2. verdict 저장
3. VIOLATION    → 사건 메시지 visibility = HIDDEN (filter_result=PASS인 증거 메시지는 제외), counted = true
   NO_VIOLATION → HELD 메시지를 PUBLIC으로
   NEEDS_REVIEW → 그대로
4. 누적 횟수 ≥ 3 이고 PENDING 검토가 없으면
   → kick_reviews(PENDING) 생성, 해당 사건들의 kick_review_id 연결
5. 커밋 후 SSE 발행 (MESSAGE_VISIBILITY_CHANGED, CASE_UPDATED, KICK_REVIEW_REQUIRED)
6. 응답: { caseStatus, violationCount, kickReviewId | null }
```

전송·구독 차단 확인:

```sql
SELECT max(expires_at) FROM guard.kicks
WHERE channel_id = :channelId AND user_id = :userId
  AND revoked_at IS NULL AND expires_at > :now;   -- :now는 주입 가능한 Clock
```

후보 전달(`CandidateSink.open`)과 FLOOD 사건 묶기 — BE1 이슈 #15에서 확정:

| 항목 | 내용 |
| --- | --- |
| 호출 조건 | 1차 필터가 PASS가 아니고 **새로 저장된** 메시지. 메시지 저장과 같은 트랜잭션. 재전송은 호출하지 않음 |
| 전달 값 | `channelId`, `userId`, `messageId`, `filterResult`, `matchingTags`(`MatchingTag` 6종), `filterVersion`, `sentAt`, `relatedMessageIds` |
| `filterVersion` | 정규화·사전 버전 조합. 예: `norm-v1+dict-v2` (재현·감사용) |
| `sentAt` | 서버가 요청을 받아 주입 Clock으로 정한 UTC 시각(마이크로초). 클라이언트 시각이 아님. 사건 생성 시각(`cases.created_at`)과 다름. 락 전에 정해지고 재전송 응답과 같음 |
| 이번 범위에서 없는 것 | `liveId`(방송 단위는 Phase 2, 범위는 채널 + 최근 24시간), `handlingRoute`, `policyVersion`(판정 제출 시 `policySnapshots`로 저장), `normalizedTextHash` |

FLOOD 판정에 쓰는 10초 창 (`platform.messages`, 실제 쿼리):

```sql
SELECT id FROM platform.messages
WHERE channel_id = :channelId AND author_id = :authorId AND normalized_text = :normalizedText
  AND sent_at > :sentAt - interval '10 seconds'     -- 하한 제외: 정확히 10초 전 메시지는 불포함
ORDER BY sent_at, id;                               -- relatedMessageIds (오래된 순)
-- 결과 행 수 + 1(현재 메시지) >= 3 이면 FLOOD (HOLD가 아닐 때). 상한은 없음(동시 요청 시 sentAt 순서 역전 가능, 제출 후 개선)
```

사건 묶기: `relatedMessageIds` 중 판정 전(`ANALYZING`) 사건에 연결된 메시지가 있으면 **그 caseId를 재사용**하고, 없으면
새 사건을 만든다(여러 개면 가장 최근 메시지에 연결된 사건). B1은 반환된 caseId를 현재 메시지와 `case_id IS NULL`인
관련 메시지에만 기록한다.

| 도착(초) | 메시지 | `relatedMessageIds` | 결과 |
| --- | --- | --- | --- |
| 0 | m1 | - | PASS (창 안 1건) |
| 1 | m2 | - | PASS (2건) |
| 2 | m3 | `[m1, m2]` | FLOOD, 새 사건 X. m1·m2·m3의 `case_id` = X (m1·m2는 공개 유지) |
| 3 | m4 | `[m1, m2, m3]` | FLOOD, X가 `ANALYZING`이므로 X 재사용 |
| 4 | m5 | `[m1, m2, m3, m4]` | FLOOD, X 재사용 → 3·4·5번째가 같은 사건, 누적 1회 |
| 10 (m1이 0초일 때) | m6 | `[m2, m3, m4, m5]` | m1은 정확히 10초 전이라 창에서 빠짐. 나머지로 FLOOD, X 재사용 |

X가 이미 판정(`HIDDEN` 등)됐으면 다음 도배 메시지는 새 사건 Y를 연다(X에 연결된 메시지의 `case_id`는 그대로).
확실한 욕설·피싱 HOLD는 FLOOD보다 우선하므로 같은 욕설 반복은 FLOOD가 아니라 HOLD 여러 건이며
`relatedMessageIds`는 비어 있다.

## 5. 담당

| 영역 | 테이블·기능 | 담당 |
| --- | --- | --- |
| 인증 | users, 세션 로그인, 역할·채널 권한 | B1 |
| 채팅 | channels, channel_members, messages, 1차 필터, 채팅 SSE | B1 |
| 사건·판정 | cases, 판정 제출 처리, 누적 계산, 관제 SSE 이벤트 발행 | B2 |
| 강퇴 | kick_reviews, kicks, 결정 API, kick_user 내부 API | B2 |
| 내부 조회 API | context·history·channel info | B1 (데이터 주인) |

## 6. 팀 확정 필요 (기본값으로 작성됨)

| # | 항목 | 기본값 |
| --- | --- | --- |
| 1 | 3회를 세는 단위 | 메시지가 아니라 **위반 사건**. 도배 10개가 한 사건이면 1회 |
| 2 | 집계 기간·범위 | 최근 24시간, 같은 채널, 운영자가 해제한 사건 제외 |
| 3 | 강퇴의 의미 | 해당 채널에서 24시간 전송·구독 금지, 열린 SSE 종료. 영구 강퇴 없음 |
| 4 | 운영자 거절 후 | 가림은 유지. 검토에 쓰인 3건은 소진되고, 새 위반 3건이 쌓이면 다시 검토 |
| 5 | 오탐 해제 | 운영자가 사건 해제 → 메시지 PUBLIC, 누적에서 제외 |
| 6 | 유형 | PROFANITY, HARASSMENT, FLOOD, PHISHING (PII 제외, 마스킹만) |
