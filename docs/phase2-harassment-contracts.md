# Phase 2 HARASSMENT 프로필 계약 (#90)

2026-10-26. BE가 결정한 FE·Agent 공용 구현 기준이다.
정책 원본 → 본 문서의 결정 → [OpenAPI](../contracts/openapi.yaml) → 공용 DTO 순으로 읽는다.
이 변경은 명세·DTO·계약 테스트만 포함하며 설정 API, 저장소, 접수 경로와 판정 서비스의
신규 계약 검증은 **미구현**이다. 설정 경로에 요청해 성공할 수 있다는 의미가 아니다.
#88의 관리자 권한·검토 카드, #89의 기간 통계 구현은 포함하지 않는다.

## 1. 버전과 지원 조합

| 사건 | policyVersion | evidenceSchemaVersion | harassmentProfile |
|---|---|---|---|
| 기존 사건 | v3.1 또는 v3.2 | 없거나 1 | 없음 |
| Phase 2 활성 신규 방송의 사건 | v3.3 | 2 | 필수, profileVersion=harassment-profile-v1 |

v3.2는 A-27·A-28에서 이미 활성 정책으로 기록됐다. 프로필 해석을 같은 v3.2에
덮어쓰지 않고 **v3.3** 계약으로 발행한다. v3.3의 비HARASSMENT 의미는 v3.2를 유지한다.
`PolicyVersion` enum 추가는 생산·정책 활성화를 의미하지 않는다. 현재 런타임
`PolicyProperties`의 활성 버전은 변경하지 않는다.
Agent의 기존 v3.2 프로필 초안은 신규 v3.3 원문·색인을 만드는 입력 자료이며,
그 초안이 기존 v3.2 사건에 적용되면 안 된다.

`evidenceSchemaVersion`은 본문 형식, 기존 `evidenceVersion`은 사건 증거 개정 순번이다.
같은 policyVersion의 해석에만 의존하지 않도록 둘을 구분한다.
신규 snapshot에는 schemaVersion을 저장하고 전체 snapshot과 함께 불변으로 고정한다.
신규 접수/생성 경로에서 버전 필드를 잃으면 오류이며 legacy로 처리하지 않는다.
기존 저장 snapshot의 버전 부재만 legacy로 읽는다. 응답에 legacy 버전을 명시할 때는 1을 쓴다.
신규 사건의 필수 참조는 `null`로 제출할 수 없으며 임의 기본값으로 보완하지 않는다.

서버는 **저장된 사건**의 스키마를 먼저 읽고 요청과 대조한다. 신규 판정에서
schemaVersion·profile을 모두 지워도 구 계약으로 우회할 수 없다. 기존 v3.1/v3.2
사건에 신규 프로필을 넣는 것도 허용하지 않는다.

## 2. 설정 API

브라우저 세션 + 기존 CSRF를 사용한다. `/internal/**` 서비스 토큰과 혼용하지 않는다.

- `GET /api/lives/{liveId}/harassment-settings`
- `PUT /api/lives/{liveId}/harassment-settings`

PUT 본문:

```json
{
  "clientRequestId": "11111111-1111-4111-8111-111111111111",
  "expectedSettingsRevision": 1,
  "harassmentLevel": "STRICT"
}
```

GET·PUT의 공통 응답:

```json
{
  "data": {
    "liveId": "22222222-2222-4222-8222-222222222222",
    "policyVersion": "v3.3",
    "profileVersion": "harassment-profile-v1",
    "harassmentLevel": "STRICT",
    "settingsRevision": 2,
    "changedAt": "2026-10-26T00:00:00Z"
  },
  "traceId": "33333333-3333-4333-8333-333333333333"
}
```

| 요청자 | GET | PUT |
|---|---|---|
| PLATFORM_ADMIN | 전 방송 | 전 방송 |
| 해당 채널 STREAMER(소유자)·MODERATOR | 해당 방송 | 금지 |
| VIEWER·비소속 사용자 | 금지 | 금지 |

전역 Role은 기존 VIEWER/MODERATOR/PLATFORM_ADMIN을 유지한다. 소유자 식별은
기존 channel_members의 STREAMER를 사용한다. `Me`에 소유자 역할이나 capabilities를
중복 추가하지 않는다. FE의 수정 UI는 `Me.role == PLATFORM_ADMIN`으로 제한하며,
방송별 조회 허용 여부는 서버가 확인한다. 403을 UI에서 숨기거나 성공으로 대체하지 않는다.
#88의 MONITOR/DECIDE/RAW_TEXT는 이 권한을 부여하거나 회수하지 않는다.
공통 인가가 행위 기반으로 바뀌면 SETTINGS_READ/SETTINGS_WRITE에 해당하는 의미를
그 인터페이스에 연결하되 Phase 2에서 별도 관리자 capability 목록을 만들지 않는다.
이 설정 권한은 채팅 전송·방송 시작/종료·CONTEXT/KICK 결정·원문 열람을 부여하지 않는다.

처리 순서:

1. 세션·CSRF 검증, 요청 구조 검증, 변경 권한 검증.
2. 방송·Phase 2 설정 존재 확인. 기존 비활성 방송에 임의 STANDARD를 만들지 않는다.
3. (liveId, clientRequestId)의 완료 기록 확인. 정규화된 세 요청 필드의 값이 같으면
   원래 업무 응답(data)을 반환한다. traceId는 현재 HTTP 요청의 값이다.
   다른 본문이면 IDEMPOTENCY_CONFLICT. 요청 사용자는 감사 기록으로 보존한다.
4. 신규 요청이면 종료 여부 확인 후 expectedSettingsRevision 확인.
5. 같은 값이면 revision·changedAt 유지. 결과를 멱등 기록에 저장한다.
6. 다른 값이면 revision+1, 변경 시각·실행자·이전/신규 값과 응답 저장.

신규 요청이 오래된 revision이면 같은 level 요청도 충돌이다. 완료된 요청의 재전송은
이후 설정 변경·방송 종료 후에도 기존 결과를 반환한다. 권한은 매번 확인한다.
실패 응답은 완료 기록으로 저장하지 않는다. revision 상한은 Java int의 2147483647이며,
증가 불가능한 실제 변경은 409 STALE_VERSION(currentVersion 포함)으로 거절한다.

## 3. 접수·동결·조회

Phase 2 활성 **신규 방송** 생성과 최초 STANDARD/revision 1 생성을 같은 트랜잭션에서 한다.
기존 방송은 자동 전환하지 않는다. 방송 시작 요청 DTO에 선택 프로필을 추가하지 않는다.
관리자가 생성 후 PUT으로 변경하며, 변경 전 접수 메시지는 STANDARD다.
전역 활성 정책 변경이 기존 방송의 새 메시지를 v3.3으로 전환하지 않도록 방송의 정책도 고정한다.

설정 변경과 **모든 메시지 접수(PASS 포함)**는 같은 방송 행 잠금으로 직렬화한다.
메시지의 접수 revision·정책 참조를 저장하고 비동기 CandidateRequest는 그 참조를 사용한다.
묶기 키는 기존 live/user/route 조건에 evidenceSchemaVersion·policyVersion·settingsRevision을
추가한다. 서로 다른 revision TARGET은 한 사건으로 묶지 않는다.
주변 비TARGET 증거는 다른 revision이어도 마스킹·증거 범위 안에서 포함할 수 있다.
프로필 변경으로 도배 창·재적발 상태·유형별 확정 수·제재·강퇴 집계를 초기화하지 않는다.

InternalCase, ContextData, CaseSummary/CaseDetail, ContextReview에는
`policyVersion`, `evidenceSchemaVersion`, `harassmentProfile`을 같은 사건 snapshot에서 전달한다.
기존 브라우저 응답에 없던 policyVersion은 신규 증거에서 필수다.
ContextData는 schema 2일 때 caseId/snapshotId/evidenceVersion도 필수다.
조사·재시도·Worker 교체·checkpoint·운영자 결정·승인 재개는 snapshot의 참조를 따른다.
Business MCP는 이 응답을 전달하며 현재 설정을 다시 조회해 덮어쓰지 않는다.
TaskEnvelope·Outbox는 기존 caseId/snapshotId 결합을 유지하고 프로필을 중복 운반하지 않는다.

### 강퇴 카드의 표시 증거

기존 KickReviewCard는 basisCaseIds 마지막 사건의 증거를 표시한다.
`evidenceCaseId`를 추가해 이 사건을 식별한다. 카드의 policyVersion·evidenceSchemaVersion·
harassmentProfile·messages·findings·evidenceVersion은 모두 **이 표시 사건** 기준이다.
카드의 기존 caseId는 검토가 연결된 사건이며 변경하지 않는다.
basisSnapshotId는 강퇴 심사 기준 집합 식별자이지 표시 증거 snapshotId가 아니다.

legacy 표시 사건은 프로필이 없을 수 있다. 근거 사건들은 revision·정책이 서로 달라도
기존 집계 기준을 만족하면 함께 심사한다. 전체 심사에 단일 프로필을 붙이지 않는다.
FE는 각 근거의 프로필이 필요하면 기존 사건 상세 API를 조회한다.
#88의 decision/execution 추가는 그대로 공존할 수 있다.

## 4. 판정과 구조화 근거

JudgmentRequest에 evidenceSchemaVersion·harassmentProfile만 추가하며 기존 필드는 유지한다.
신규 사건은 비HARASSMENT 조사여도 프로필 참조를 그대로 제출한다.
MessageFinding의 기존 verdict/categories/policyIds/reason/missingFacts는 유지하고
`harassmentAssessment`만 선택 확장한다. legacy 사건에는 이 확장을 제출하지 않는다.

신규 사건에서 다음 중 하나면 해당 finding의 assessment가 필수다.

- categories에 HARASSMENT가 있음
- policyIds에 CP-3.1/CP-3.2/CP-3.3이 있음
- 동결된 matchingTags에 ATTACK_TERM이 있음(모든 TARGET finding에 요구)

마지막 조건은 사건을 읽는 서비스 검증이며 DTO만으로 판별할 수 없다.
그 외 비HARASSMENT 조사에는 assessment를 강제하지 않는다.
CP-2.2는 다른 유형에도 쓰이므로 그 조항만으로 HARASSMENT 조사로 분류하지 않는다.

```json
{
  "clauses": [
    {
      "policyId": "CP-3.2",
      "verdict": "VIOLATION",
      "targetStatus": "MET",
      "conductStatus": "MET",
      "targetEvidenceMessageIds": ["44444444-4444-4444-8444-444444444444"],
      "conductEvidenceMessageIds": ["44444444-4444-4444-8444-444444444444"],
      "exceptionStatus": "DOES_NOT_APPLY",
      "exceptionEvidenceMessageIds": []
    }
  ]
}
```

위 예는 RELAXED의 개인 공격 평가다. STRICT/STANDARD에서는 CP-3.3 평가도 포함한다.
각 적용 위반 조항을 정확히 한 번 평가한다(STRICT/STANDARD 2개, RELAXED 1개).
CP-3.1은 모든 평가의 공통 제한 원칙으로 유지하며 독립 위반 근거가 아니다.

- targetStatus/conductStatus: MET/NOT_MET/UNKNOWN. MET이면 각각 근거 메시지 UUID가 필수다.
  CP-3.2는 식별 가능한 개인과 그 개인을 향한 공격, CP-3.3은 정체성 집단과
  열등 단정·배제·차별 요구를 각각 확인한다.
- 근거 UUID는 동일 snapshot.messages에 있어야 한다. 타 사건·현재 채팅을 근거로
  추가하지 않는다. UUID가 맞는 것만으로 의미 검증을 통과했다고 주장하지 않는다.
- 각 clause의 exceptionStatus: STRICT는 NOT_APPLICABLE와 빈 예외 근거. STANDARD/RELAXED는
  APPLIES/DOES_NOT_APPLY/UNKNOWN. APPLIES이면 예외 근거 UUID가 필수다.
  이 필드는 HARASSMENT의 CP-2.2에만 적용하고 사칭·PII 예외를 바꾸지 않는다.
  예외는 조항별로 기록해 CP-3.3 인용 예외가 독립적인 CP-3.2 개인 공격을 면제하지 않도록 한다.
- clause VIOLATION은 target·conduct 모두 MET, 예외 미적용 확정이어야 한다.
  clause VIOLATION 조항은 finding.policyIds에 있고, HARASSMENT category도 있어야 한다.
  비위반 clause는 위반 policyIds에 넣지 않는다. CP-2.2는 정상 예외 근거로 별도 구분한다.
- 요건 불확실성과 예외 불확실성으로 위반/정상 판단이 불가능하면 clause NEEDS_REVIEW,
  finding NEEDS_REVIEW 및 missingFacts를 제출한다. 확인된 비공격 등 충분한 정상 근거가
  있으면 해당 clause는 NO_VIOLATION일 수 있다.
  기계적 일치 규칙은 요건 중 NOT_MET 또는 예외 APPLIES면 NO_VIOLATION,
  두 요건 MET 및 예외 NOT_APPLICABLE/DOES_NOT_APPLY면 VIOLATION, 나머지는 NEEDS_REVIEW다.
- RELAXED에서 CP-3.3 위반 근거 제출은 다른 근거가 섞여도 거절한다. 집단 공격 사실을
  CP-3.2로 우회하지 않는다. 독립적인 개인 공격 근거가 있을 때만 CP-3.2로 제안한다.
- STRICT의 예외 제외만으로 위반이나 검토 필요를 만들지 않는다. 표·근거 상태·UUID·
  조항/category 일치를 구조적으로 검증하며 reason 문자열 검색으로 대체하지 않는다.

판정 서비스는 구조 검증 후 경로/사건·대상·버전과 모든 근거 UUID를 대조한다.
동일 판정은 DTO의 선택 필드 생략을 정규화한 값 전체(구조화 근거 포함)로 비교한다.
동일 case/snapshot/evidence와 동일 판정은 원래 결과를 반환하며 중복 제재하지 않는다.
같은 증거 식별자의 다른 판정은 409 IDEMPOTENCY_CONFLICT다. 사람 결정·취소 이후의
새 판정은 기존 A-25의 STALE_VERSION을 우선하며 기존 결과를 덮지 않는다.

## 5. 오류

모두 기존 ErrorCode와 `{error:{code,message,retryable,...},traceId}`를 사용한다.

| 조건 | HTTP / code | retryable |
|---|---|---|
| 세션 없음 | 401 UNAUTHENTICATED | false |
| 조회/변경 권한 없음, CSRF 거부 | 403 FORBIDDEN | false |
| 방송 없음 또는 Phase 2 비활성 설정 조회/변경 | 404 NOT_FOUND | false |
| 신규 설정 변경 요청의 종료 방송 | 409 LIVE_ENDED | false |
| 설정 revision 불일치·증가 상한 | 409 STALE_VERSION + currentVersion | false |
| 동일 설정 요청 ID 다른 본문 / 같은 증거의 다른 판정 | 409 IDEMPOTENCY_CONFLICT | false |
| 유효한 snapshotId/evidenceVersion/policyVersion/schemaVersion/프로필 참조가 저장본과 다름 | 409 STALE_VERSION | false |
| 누락/null/미지원 값/알 수 없는 필드/잘못된 근거 UUID/비적용 조항/구조화 근거 모순 | 400 VALIDATION_FAILED | false |
| 필수 snapshot 저장 정보 미준비·손상 | 409 CASE_NOT_READY | false |
| 재시도 가능한 인프라 장애 | 503 TEMPORARY_UNAVAILABLE | true |

지원되는 값의 불일치와 미지원 입력을 구분한다. 신규 사건에서 필수 프로필 필드를 모두
생략한 판정은 저장 사건 판별 후 400이다. 필드를 모두 제공했으나 다른 유효 프로필이면
409다. currentVersion은 기존 필드로 설정 revision에 재사용하고 새 오류 필드를 만들지 않는다.

## 6. 배포·후속 작업

1. 승인된 v3.3 원문을 작성·검수하고 커밋·본문 SHA-256을 발행한다. 현재 승인 해시 없음.
   이 상태에서는 기능 활성화하지 않는다. 승인 후 의미 변경은 새 정책/프로필 버전이다.
2. FE·Agent/MCP·RAG의 구/신 계약 소비자를 준비한다. RAG는 v3.1/v3.2 원문·색인을 보존한다.
3. BE 저장·접수·snapshot·인가·판정 서비스 검증과 데이터 무결성 시험을 구현한다.
   기능 기본 OFF. 이 문서의 DTO validation이 서비스 검증을 대신하지 않는다.
4. 기존 snapshot 없는 행을 신규 기본값으로 backfill하지 않고 구 계약으로 유지한다.
   기존 Worker는 기존 사건만 처리하며 신규 schema 2 소비자가 준비되지 않으면 활성화하지 않는다.
5. 통제 모델 계약·경로 회귀를 검증한다. 평가셋 작성·실제 모델 평가는 별도 후속 작업이며
   API 명세 작업의 선행 조건이 아니다. 운영 활성화 전에는 설계의 평가·출시 기준을 충족한다.
6. 신규 방송 opt-in만 활성화한다. rollback은 새 방송 활성화 중지이며 기존 프로필 방송과
   미완료 schema 2 사건용 호환 BE·Worker·RAG를 남긴다. legacy 바이너리로 무조건 되돌리지 않는다.

FE: 설정 GET/PUT·revision·권한·409 재확인, 사건 당시 프로필과 표시 증거 출처를 구현한다.
Agent: schema/policy/profile 검증, MCP 전달·State/checkpoint 보존, 구조화 근거 생성 및
판정 제출·승인 재개를 구현한다. 필드 추가로 기존 수신기가 무시할 수 있으므로 BE의
저장본 기준 누락 거부를 함께 구현해야 한다.
BE1: #88 인가 리팩터링과 #89 집계는 별도다. Phase 2 권한을 DECIDE에 묶지 않으며
통계는 현재 가시성의 메시지 수/검토 수로 유지하고 프로필 revision JOIN으로 중복 집계하지 않는다.
