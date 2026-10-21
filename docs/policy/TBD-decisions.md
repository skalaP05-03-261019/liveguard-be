# LIVE:GUARD TBD 결정안

2026-10-21 · 작성: 함형민(B1)

## 적용 원칙

정책의 의미는 팀 문서(PROJECT·POLICY·CONTRACTS·ARCHITECTURE, 2026-10-20 개정)가 원본이고, 이 문서는 그 미정 등록부(TBD-01~12)의 빈칸만 채운다. 문서가 이미 정한 항목은 이 문서로 바꾸지 않는다.

- **채우는 재료**: v0.5 구현에서 이미 정하고 테스트한 값을 우선 쓰고, 문서 용어로 바꿔 적는다 (채널 → liveId, HOLD/REVIEW → handlingRoute).
- **v0.5에 답이 없는 항목**: 문서의 원칙(규칙 확정과 AI 제안 분리, 운영자 결정 우선, 소진은 이력 보존)에 맞춰 새로 제안한다.
- **확정 방식**: 확인 담당자가 기한까지 이견을 주지 않으면 확정으로 보고 PROJECT.md 미정 등록부를 "결정됨"으로 고친다. 이견이 있으면 그 항목만 다시 정한다.
- **테스트**: 확정 전 값은 fixture로만 쓰고 정책 확정과 섞지 않는다 (CONTRACTS 13절).

이견 기한: 2026-10-22 10:00

## 결정 요약

구현을 막는 TBD는 7개(02·04·05·06·08·11·12)이고, 나머지 5개는 설정값으로 빼 두거나 보류한다. **구조**는 흐름·테이블이 바뀌는 것, **설정**은 값만 바뀌는 것이다.

| TBD | 분류 | 결정 | v0.5 근거 | 확인 |
| --- | --- | --- | --- | --- |
| 01 사전·URL | 설정 | 사전 3종(확실한 욕설·공격·예외), 정규화 후 부분 일치, 모든 링크 탐지 | dict-v2, UrlHosts | B1 |
| 02 문맥 의심 규칙 | 구조 | 경로 우선순위 RULE_CONFIRMED > FLOOD_REVIEW > PII_INVESTIGATION > CONTEXT_REVIEW > PASS | 필터 우선순위 | B1 |
| 03 TIMEOUT 이력 | 설정 | 같은 liveId 전체, 기준은 확정 시각, 취소는 유형(finding) 단위 | 없음 | B2 |
| 04 사건 묶기 | 구조 | 일반은 메시지 1개 = 사건 1개, 도배는 반복 묶음 1개 = 사건 1개(10초 무반복이면 종료). 강퇴 집계는 유형과 관계없이 사건 3건 | relatedMessageIds, #16 | B2 |
| 05 시간 경계 | 구조 | 서버 수신 시각, 마이크로초, 하한 제외 | #16 테스트 | B2 |
| 06 기준 사건 선택 | 구조 | 확정 시각이 가장 오래된 3건, 유효기간은 방송 종료까지 | 없음 | B2 |
| 07 kick 오류 | 설정 | 429·503·연결 실패만 재시도, 1초·2초 간격, 응답 유실은 UNKNOWN 후 상태 조회 | 멱등 키 | AI |
| 08 정책 버전 | 구조 | `v3.0` 고정, BE·Agent·RAG가 공유, filterVersion 별도 기록 | filterVersion | AI |
| 09 TIMEOUT 중첩 | 설정 | 새 만료가 더 늦을 때만 교체, 합산 없음 | 없음 | B2 |
| 10 검토 대기 만료 | 보류 | 만료·재배정 기능 없음 (자동 공개 없음 유지) | 없음 | FE |
| 11 도배 확정 주체 | 구조 | BE 규칙(10초 3회)로 확정, BE가 못 거른 반복만 Agent 판정, 재적발 창은 확정 시각부터 24시간 | 필터 FLOOD | B2 |
| 12 변경 도구 | 구조 | 승인 없는 경로는 BE가 실행, MCP 변경 도구는 interrupt 뒤에서만 | kick_user만 interrupt | AI |

## 구현을 막는 TBD

### TBD-02 문맥 의심 규칙과 신호 우선순위

한 메시지는 경로 하나만 갖고, 걸린 나머지 신호는 matchingTags로 기록해 조사 자료로 넘긴다.

| 순위 | handlingRoute | 조건 (태그) | 초기 상태 |
| --- | --- | --- | --- |
| 1 | RULE_CONFIRMED | 확실한 욕설(CERTAIN_PROFANITY) 또는 링크(LINK, 공식 링크 포함) | HIDDEN |
| 2 | FLOOD_REVIEW | 같은 userId·liveId, 10초 안 같은 문장 3회째부터 (REPEATED_TEXT) | HELD |
| 3 | PII_INVESTIGATION | 전화번호·계좌·이메일 형식 패턴. 숫자 나열만으로는 후보 아님 (AC-06) | HELD |
| 4 | CONTEXT_REVIEW | 공격·비하 사전(ATTACK_TERM), 링크 없는 공식 사칭 + 인증 요구(OFFICIAL_CLAIM + CREDENTIAL_REQUEST) | HELD |
| 5 | PASS | 위 신호 없음 | PUBLIC |

- 근거: POLICY 1·2·3절. REVIEW는 쓰지 않는다. 링크와 사칭이 함께 있으면 링크 금지로 확정하고 사칭 사유는 태그로 따로 남긴다.
- v0.5에서 가져온 것: 한 메시지 하나의 대표 결과 + 태그 전부 기록하는 구조, 태그 6종.

### TBD-04 사건 묶기와 종료

도배는 연속 묶음(burst) 하나를 사건 하나로, 그 외는 메시지 하나를 사건 하나로 만든다.

- **일반 사건**: 대상 메시지 1개당 사건 1개. 주변 문맥은 증거로만 쓰고 제재 대상에 넣지 않는다.
- **도배**: 반복 묶음 하나를 사건 하나로 한다. 3번째 메시지에서 사건을 만들고, 추가 반복은 같은 사건에 증거로 누적한다 (중복 HIDE 대상). 한 번의 도배로 곧바로 강퇴 검토까지 가는 것을 막기 위함이다.
- **묶음 종료**: 같은 문장의 마지막 전송 이후 10초 동안 추가 반복이 없으면 닫는다. 묶음 종료는 기존 제재나 위반 이력 삭제를 뜻하지 않는다. (B1·김태관 제안 일치, 팀 확인 중)
- **재적발**: 묶음이 닫힌 뒤 확정 시각부터 24시간 안에 같은 문장을 보내면 1회로도 새 도배 사건이다 (POLICY 7절). 재적발도 같은 묶음 규칙을 따른다.
- **앞의 두 메시지**: 사건에 증거로 연결만 하고 가리지 않는다 (POLICY 7절 "FLOOD 조건만으로 가리지 않음").
- **상태 분리**: 판정 완료(위반 확정)와 실제 조치 완료(HIDE·TIMEOUT 실행 결과)는 별도 상태로 관리한다.
- **사건 종료**: 확정 또는 취소 시점. 문맥 검토 사건은 운영자의 정상·위반 결정으로 종료하고, 보류(DEFER)는 열린 채로 둔다.
- **부분 유형 취소**: finding 단위로 `revokedAt`을 기록한다. 사건의 유효 finding이 0개가 되면 사건 취소로 보고 집계에서 뺀다.
- v0.5에서 가져온 것: relatedMessageIds로 앞 메시지 연결, 이미 다른 사건에 연결된 메시지는 바꾸지 않음 (PR #8, #16).
- **강퇴 집계**: 위반 유형과 관계없이 서로 다른 확정 사건 3건이면 강퇴 검토를 요청한다. 한 사건에 유형이 여러 개여도 1건이다 (POLICY 8절).

### TBD-05 시간 경계와 기준 시각

모든 시각은 서버가 요청을 받은 UTC 시각(주입 Clock)을 마이크로초로 자른 값이다. 클라이언트 시각은 쓰지 않는다.

| 항목 | 기준 시각 | 경계 |
| --- | --- | --- |
| 도배 10초 창 | 메시지 수신 시각 | `sent_at > 현재 − 10초` (정확히 10초 전은 제외), 현재 포함 3회 |
| 강퇴 집계 24시간 | finding 확정 시각 | `confirmedAt > 현재 − 24시간`, 현재 사건 포함 |
| 도배 재적발 24시간 | 최초 도배 확정 시각 | `확정 시각 + 24시간 > 수신 시각` |

- 문장 비교는 norm-v1 정규화 결과로 한다 (NFKC → 소문자 → 공백 축약 → 같은 문자 3개 이상은 2개).
- v0.5에서 가져온 것: 10초 경계·정밀도·기준 시각 전부 (PR #16 테스트로 확인).
- 알려진 한계: 수신 시각을 락 획득 전에 정하는 문제는 별도 수정으로 락 뒤로 옮긴다.

### TBD-06 강퇴 기준 사건 선택과 승인 유효기간

집계 대상이 3건을 넘으면 확정 시각이 가장 오래된 3건을 기준 집합으로 고정한다.

- 오래된 것부터 고르는 이유: 24시간 창에서 먼저 빠질 사건을 먼저 소진해야 남은 사건이 다음 집계에 쓰일 기회가 커진다.
- 승인 유효기간: 해당 liveId가 끝날 때까지. 방송이 끝나면 실행하지 않는다 (POLICY 10절).
- 재검증 탈락(취소·시간창 이탈로 3건 미달): 그 검토는 INVALIDATED로 닫고 실행하지 않는다. 소진은 되돌리지 않고, 새 검토는 소진되지 않은 사건이 다시 3건이 될 때만 만든다.

### TBD-08 정책 버전과 RAG 색인

활성 정책 버전은 `v3.0`으로 고정하고, 환경변수 하나를 BE·Agent·RAG가 같이 읽는다.

- 값: `v3.0` 고정 (환경변수 `LIVEGUARD_POLICY_VERSION`). 사건·판정·강퇴 실행에 이 값을 기록한다.
- RAG: 색인 청크마다 policyVersion을 기록하고, search_policy는 같은 버전만 반환한다. v0.3 정책 문서는 색인하지 않는다.
- 불일치 처리: Agent가 받은 버전과 RAG 버전이 다르면 작업을 FAILED로 끝낸다.
- 필터 버전(`filterVersion`, 예: norm-v1+dict-v2)은 별도로 사건에 기록한다. v0.5 PR #16에서 이미 구현됐다.

### TBD-11 최초 도배 확정 주체

도배는 BE 규칙(10초 안 같은 문장 3회)으로 먼저 확정하고, BE 규칙이 못 거른 반복만 Agent가 판정한다.

- **BE 규칙**: 같은 userId·liveId에서 10초 안 같은 정규화 문장이 3회가 되면, 3번째를 HELD로 저장하고 같은 트랜잭션에서 확정해 HIDDEN으로 바꾼다. AI 조사는 거치지 않는다. (n=3 확정)
- **Agent 판정**: BE 규칙에 안 걸린 반복은 별도 후보로 보내지 않는다. 다른 이유(문맥·PII 등)로 조사 중인 사건에서 Agent가 주변 문맥의 같은 사용자 반복을 발견하면 FLOOD 유형을 판정한다. 확정은 그 사건 경로의 규칙을 따른다 (문맥 검토면 운영자 결정).
- **근거**: PROJECT TBD-02의 "BE 후보만 조사, PASS 우회 공격 누락 가능". '비슷한 문장 반복'을 BE 후보로 두면 라이브 채팅의 정상 반복(ㅋㅋ, 응원 구호)이 HELD되는 오탐이 많다.
- **후속**: 유사 반복 탐지는 Phase#2 이후 실제 채팅 데이터로 오탐률을 측정한 뒤 결정한다.
- 첫 확정 사건은 중복 HIDE만 하고, TIMEOUT은 제재표대로 두 번째 도배 확정부터 적용한다.
- 재적발 기준 시각: 최초 확정 시각 (TBD-05 표).

### TBD-12 변경 도구와 채점 기준

사람 승인이 없는 경로의 가림·제한은 BE가 업무 규칙으로 실행하고, Agent의 MCP 변경 도구는 사람 승인 interrupt 뒤에서만 호출한다.

| 경로 | 가림·제한 실행 | interrupt |
| --- | --- | --- |
| RULE_CONFIRMED, 도배 확정 | BE 업무 규칙 | 없음 (Agent 미호출) |
| PII_INVESTIGATION | Agent는 판정만 제출, BE가 검증 후 실행 | 없음 |
| CONTEXT_REVIEW | 운영자 결정 후 재개해서 hide_message·timeout_user·release_message | 문맥 검토 interrupt |
| 강퇴 | 운영자 승인 후 재검증하고 kick_user | 강퇴 interrupt |

- 근거: 강의자료 표 6-3 Guardrails "변경 도구는 interrupt로 멈춘다". 문서의 동작(규칙 확정은 BE, 문맥은 운영자 결정)은 그대로 지키고, 실행 주체만 정한다.
- v0.5에서 가져온 것: "Agent는 판정만 제출, 가림은 BE 규칙" 원칙과 MessageVisibilityPort (PR #14).

## 설정값·보류 TBD

아래 항목은 코드에서 설정값이나 교체 가능한 파일로 빼 두고, 확정되면 값만 넣는다.

### TBD-01 사전·경계 매칭·URL 파서

- 사전 3종: 확실한 욕설(RULE_CONFIRMED), 공격·비하(CONTEXT_REVIEW 신호), 예외 단어(시발점, 시발역, 병신년).
- 매칭: 사전 항목과 본문을 같은 norm-v1으로 정규화한 뒤 부분 일치. 띄어쓰기를 지우고 비교하지 않는다 ("이시 발표" 오탐 방지).
- 한계: 띄어쓴 우회("시 발")는 못 잡는다. 문서의 "모든 표현을 탐지한다고 보장하지 않음"과 같다.
- URL: 원문에 NFKC만 적용해 찾는다 (전각 표기 탐지). scheme 있는 URL과 `www.` 또는 `영문.영문2자이상` 도메인을 링크로 본다. 이메일은 링크가 아니라 PII 후보로 본다.
- 변경점: v0.5의 공식 호스트 예외는 없앤다. 공식 호스트 목록은 사칭 조사의 참고 자료로만 쓴다.

### TBD-03 TIMEOUT 이력 조회 기간

- 기간: 같은 userId·liveId의 방송 시작부터 현재까지, 현재 사건 제외. 방송 단위가 이미 자연스러운 경계라 별도 시간 창을 두지 않는다.
- 기준 시각: finding 확정 시각. 취소된 finding(`revokedAt` 있음)은 제외하고, 소진된 사건은 포함한다 (POLICY 9절).
- 설정값: `liveguard.timeout.history-scope=LIVE`로 두어, 나중에 시간 창으로 바꿀 수 있게 한다.

### TBD-07 kick 오류 코드와 재시도

- 일시 오류: HTTP 429, 503 `TEMPORARY_UNAVAILABLE`, 연결 실패. 이 외는 재시도하지 않고 바로 FAILED.
- 간격: 1초, 2초. 최초 포함 최대 3회, 같은 actionId·멱등 키.
- 응답 유실(읽기 타임아웃): UNKNOWN으로 두고 get_action_status로 최대 3회(1초 간격) 확인. 여전히 모르면 운영자 콘솔에 표시한다.
- 수동 재시도: 운영자가 같은 actionId로 다시 실행한다. 자동 재시도 예산과 별도로 기록한다.

### TBD-09 활성 TIMEOUT의 연장과 중첩

- 새 TIMEOUT의 만료 시각이 기존보다 늦으면 교체하고, 이르거나 같으면 기존을 유지한다. 기간 합산은 하지 않는다 (문서의 "최장 하나"와 같은 방향).
- 두 기록은 모두 이력에 남긴다.

### TBD-10 운영자 검토 대기 만료·재배정 (보류)

- 만료와 재배정 기능은 만들지 않는다. 만들지 않는 것 자체가 문서의 "자동 공개 없음"을 지킨다.
- 보류(DEFER) 건은 검토 목록에 남는다. 화면 세부는 FE가 정한다.

## ERD v1.0 구현 결정

2026-10-21 · 결정: 함형민(B1). 정책 원본과 위 TBD 결정에 이름이 없는 테이블·컬럼·값을 [docs/erd.md](../erd.md) v1.0을 위해 정한다. 정책의 의미는 바꾸지 않는다.

| # | 항목 | 결정 | 근거 | 담당 |
| --- | --- | --- | --- | --- |
| E-1 | 방송(`lives`) | 컬럼 `started_at`, `ended_at`(null = 방송 중). 상태 컬럼 없음. 채널당 진행 중 방송 1개(`ended_at IS NULL` 부분 유일). 시작·종료는 운영자 API(openapi v1.0에서 정의). 시드에 진행 중 방송 1개 | PROJECT §2, POLICY §10 | B1 |
| E-2 | 보류 원인(`holdReasons`) | 별도 테이블 `message_hold_reasons(message_id, case_id, reason, created_at, released_at)`. `reason`은 handlingRoute 값. 미해제 원인이 남아 있으면 PUBLIC으로 되돌리지 않는다(자기 원인만 해제) | CONTRACTS §3(Message), POLICY §4 | B1 |
| E-3 | 사건 확정 상태 | `cases.status`는 처리 진행 상태로 유지하고 `confirmation_status`(PENDING, CONFIRMED_VIOLATION, CONFIRMED_NORMAL, CANCELLED)를 추가한다. DEFER는 `decisions`에만 기록하며 상태는 PENDING으로 둔다 | CONTRACTS §3(Case), POLICY §5 | B2 |
| E-4 | 예약 상태 | `reservations.state`: RESERVED, CONSUMED, RELEASED | POLICY §8·§9 | B2 |
| E-5 | 결정 중복 방지 | `decisions`에 `UNIQUE(review_id)`와 `client_request_id` 저장. 같은 요청의 재전송은 같은 결과, 다른 요청은 409 | CONTRACTS §8 | B2 |
| E-6 | TIMEOUT 저장 | `timeouts(live_id, user_id, action_id, starts_at, expires_at)`. 활성 TIMEOUT 교체는 TBD-09(새 만료가 더 늦을 때만)를 따르되 기록은 모두 남긴다 | CONTRACTS §4(timeoutUntil), TBD-09 | B2 |
| E-7 | 도배 재적발 상태 | `flood_marks(live_id, user_id, text_hash, case_id, confirmed_at, notice_event_id)`. `text_hash`는 norm-v1 정규화 문장의 SHA-256, `notice_event_id`는 nullable. 재적발 창은 `confirmed_at + 24시간` | ARCHITECTURE §4, POLICY §7, TBD-05·TBD-11 | B1·B2 |
| E-8 | PII 태그 | MatchingTag에 `PII_PHONE`, `PII_ACCOUNT`, `PII_EMAIL` 추가 | TBD-02(3순위), POLICY §2 | B1 |
