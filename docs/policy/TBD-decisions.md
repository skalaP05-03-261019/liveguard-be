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
| 08 정책 버전 | 구조 | `v3.1` 고정, BE·Agent·RAG가 공유, filterVersion 별도 기록 | filterVersion | AI |
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

활성 정책 버전은 `v3.1`으로 고정하고, 환경변수 하나를 BE·Agent·RAG가 같이 읽는다.

- 값: `v3.1` 고정 (환경변수 `LIVEGUARD_POLICY_VERSION`). 사건·판정·강퇴 실행에 이 값을 기록한다.
- RAG: 색인 청크마다 policyVersion을 기록하고, search_policy는 같은 버전만 반환한다. v0.3 정책 문서는 색인하지 않는다.
- 불일치 처리: Agent가 받은 버전과 RAG 버전이 다르면 작업을 FAILED로 끝낸다.
- 필터 버전(`filterVersion`, 예: norm-v1+dict-v2+rules-v2)은 별도로 사건에 기록한다. v0.5 PR #16에서 이미 구현됐다. 정규화(norm)·사전(dict)·판정 규칙(rules: 경로 우선순위, 링크·개인정보 후보 탐지, 도배 기준) 버전을 잇는다. rules-v2는 개인정보 후보(#48)부터이며, 그 이전 기록은 rules 구성요소 없이 norm-v1+dict-v2다.

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
| PII_INVESTIGATION | Agent는 판정만 제출, BE가 검증 후 실행 | 없음. NEEDS_REVIEW만 CONTEXT 검토 생성 + interrupt (A-16 참조) |
| CONTEXT_REVIEW | 운영자 결정 후 재개해서 hide_message·timeout_user·release_message | 문맥 검토 interrupt |
| 강퇴 | 운영자 승인 후 재검증하고 kick_user | 강퇴 interrupt |

- 예외(A-25): Agent 장애로 AI 판정이 없는 CONTEXT_REVIEW·PII_INVESTIGATION 사건은 결정을 기다리는 Agent thread가 없다. 이 사건의 문맥 결정과 강퇴 승인은 운영자 결정 뒤 BE가 업무 규칙으로 실행한다(Agent 미호출). Agent 경로의 강퇴 승인도 마감 안에 Agent가 이어받지 않으면 BE가 실행한다(A-25 넘겨받기). Agent가 변경 도구를 부르는 경로는 없으므로 "변경 도구는 interrupt로 멈춘다"(강의자료 표 6-3)와 "데이터를 바꾸는 도구는 사람이 확인한다"(표 5-1)는 그대로 지켜진다. AI 판정이 있는 정상 경로는 위 표대로다.

- 근거: 강의자료 표 6-3 Guardrails "변경 도구는 interrupt로 멈춘다". 문서의 동작(규칙 확정은 BE, 문맥은 운영자 결정)은 그대로 지키고, 실행 주체만 정한다.
- v0.5에서 가져온 것: "Agent는 판정만 제출, 가림은 BE 규칙" 원칙과 MessageVisibilityPort (PR #14). MessageVisibilityPort는 #47 이후 쓰이지 않아 제거했고, 가시성 변경은 `LivePlatformPort.visibility`(원인별 해제)가 맡는다.

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
| E-5 | 결정 중복 방지 | 최종 결정(DEFER가 아닌 값)만 검토당 1건: `UNIQUE(review_id) WHERE value <> 'DEFER'`. 요청 멱등은 `UNIQUE(review_id, client_request_id)`. DEFER는 최종 결정이 아니므로 `decisions`에 이력으로 남고 검토는 PENDING으로 유지되며, 이후 최종 결정을 저장할 수 있다. 같은 요청의 재전송은 저장된 같은 결과를 돌려주고, 최종 결정이 저장된 뒤 들어온 다른 요청은 409다. 검토 요청은 공통 부모 `reviews`(CONTEXT·KICK)가 식별하고 `decisions.review_id`는 `reviews`를 참조한다 | CONTRACTS §8, TBD-04·10 | B2 |
| E-6 | TIMEOUT 저장 | `timeouts(live_id, user_id, action_id, starts_at, expires_at)`. 활성 TIMEOUT 교체는 TBD-09(새 만료가 더 늦을 때만)를 따르되 기록은 모두 남긴다 | CONTRACTS §4(timeoutUntil), TBD-09 | B2 |
| E-7 | 도배 재적발 상태 | `flood_marks(live_id, user_id, text_hash, case_id, confirmed_at, notice_event_id)`. `text_hash`는 norm-v1 정규화 문장의 SHA-256, `notice_event_id`는 nullable. 재적발 창은 `confirmed_at + 24시간` | ARCHITECTURE §4, POLICY §7, TBD-05·TBD-11 | B1·B2 |
| E-8 | PII 태그 | MatchingTag에 `PII_PHONE`, `PII_ACCOUNT`, `PII_EMAIL` 추가 | TBD-02(3순위), POLICY §2 | B1 |

## OpenAPI v1.0 구현 결정

2026-10-21 · 결정: 함형민(B1). 정책 원본·위 TBD 결정·ERD v1.0에 이름이 없는 경로·필드·값을 [contracts/openapi.yaml](../../contracts/openapi.yaml) v1.0을 위해 정한다. 정책의 의미는 바꾸지 않는다. "B2 확인 요청"은 B2가 확인하기 전까지 잠정 결정이다.

| # | 항목 | 결정 | 근거 | 담당 |
| --- | --- | --- | --- | --- |
| A-1 | 방송 시작·종료 | 시작 `POST /api/channels/{channelId}/lives`, 종료 `POST /api/lives/{liveId}/end`. 권한은 그 채널의 STREAMER 또는 MODERATOR. 진행 중 방송이 있는데 시작하면 409 `LIVE_ALREADY_ACTIVE`, 이미 끝난 방송을 종료하면 409 `LIVE_ENDED` | E-1, POLICY §10 | B1 |
| A-2 | 현재 방송 조회 | `GET /api/channels/{channelId}/lives/current` → `Live{liveId, channelId, startedAt, endedAt}`. 진행 중 방송이 없으면 404 | E-1, CONTRACTS §1(liveId) | B1 |
| A-3 | 작성자 안내 코드 | `moderationReasonCode` 5종: `PROFANITY_INCLUDED`, `LINK_NOT_ALLOWED`, `FLOOD_REPEATED`, `CONTEXT_PENDING`, `PII_PENDING`. PASS는 null. PII 안내에는 원문과 탐지 값을 다시 노출하지 않는다 | CONTRACTS §4, POLICY §4 | B1 |
| A-4 | 전송 응답 | `moderationNotice`(string, null 가능), `timeoutUntil`(실제 TIMEOUT 적용 시만), `reviewStatus`(ReviewStatus, null 가능: HELD 조사·결정 대기면 PENDING, 그 외 null) | CONTRACTS §4 | B1 |
| A-5 | 거부 오류 코드 | `TIMED_OUT` 403 + `until`(TIMEOUT 만료 시각). 429는 Recovery가 자동 재시도하므로 쓰지 않는다. `KICKED` 403(`until` 없음, 방송 종료까지). `LIVE_ENDED` 409, `LIVE_ALREADY_ACTIVE` 409. 넷 다 retryable=false | CONTRACTS §4·§9, POLICY §10 | B1 |
| A-6 | 검토 버전·threadId | ERD `reviews.version` 추가, 결정 요청의 `expectedReviewVersion`과 비교. `threadId`는 브라우저 응답에서 빼고 내부 API에서만 노출 | CONTRACTS §8·§11 | B2 |
| A-7 | 문맥 위반 결정 | `CONFIRM_VIOLATION`이면 `categories`(Category[], 1개 이상)와 `evidenceMessageIds`(Uuid[], 1개 이상) 필수. 다른 결정에서는 보내지 않는다 | CONTRACTS §8 | B2 |
| A-8 | 사건 API | `POST /api/cases/{caseId}/release` 삭제(문맥 결정 API로 대체). `GET /api/cases`의 필터는 `liveId`·`confirmationStatus`. CaseStatus enum은 `cases.status`(진행 상태)용으로 유지. **B2 확인 요청: 운영자 오판 취소(finding revoke, POLICY §8) API의 경로·권한** | CONTRACTS §8, POLICY §8 | B2 |
| A-9 | 판정 제출 | 경로 `POST /internal/cases/{caseId}/judgment` 유지, 본문은 CONTRACTS §5 Agent 출력. CONTEXT_REVIEW 경로의 제출과 PII_INVESTIGATION의 NEEDS_REVIEW 제출(A-16)이 CONTEXT 검토를 만든다(별도 생성 API 없음). 응답 `{confirmationStatus, reviewId, reviewKind}`: 이 제출로 만들어진 검토(CONTEXT, 또는 BE 확정 후 3건 도달 시 KICK)가 없으면 둘 다 null | CONTRACTS §5, ARCHITECTURE §3 | B2 |
| A-10 | Agent 재개 | `POST /agent/tasks/{id}/resume`은 Agent 저장소 계약이라 BE openapi에 넣지 않는다 | CONTRACTS §8 | Agent |
| A-11 | 강퇴 재검증 | `POST /internal/kick-reviews/{reviewId}/revalidate` 응답 `{reviewId, decisionId, valid, validationRef, basisCaseIds}`. `valid=false`면 `validationRef`는 null | CONTRACTS §6·§9 | B2 |
| A-12 | 레거시 내부 경로 | B2 확인(2026-10-23): 호출하는 곳이 없는 `POST /internal/kick-reviews/{reviewId}/execute`(미구현, 실행은 `/internal/actions/{actionId}/execute`), `GET /internal/cases/{caseId}/history`(`/user-history`의 옛 이름), `KickResultResponse`·`KickReviewStatus`는 계약과 코드에서 삭제한다. `POST /internal/cases/{caseId}/reviews/{decisionId}/reconcile`은 BE가 항상 501을 돌려주는 스텁이지만 business-mcp에 `reconcile_review` 도구가 남아 있어 deprecated로 둔다. 지우면 응답이 501에서 404로 바뀌므로 Agent 쪽 도구를 지울 때 함께 지운다 | CONTRACTS §7·§9 | B2 |
| A-13 | 이력·집계 필드 | `UserHistoryRecentCase.judgedAt` → `confirmedAt`. `KickEligibility.violationCount` → `count`, `UserHistory.windowHours` 제거(같은 방송 전체, TBD-03)는 Java 계약 테스트와 함께 바꿔야 해서 contracts.enums PR로 미룬다. **B2 확인 요청** | CONTRACTS §3·§6, TBD-03 | B2 |
| A-14 | SSE | 모든 이벤트 `data`를 `{eventId, occurredAt, entityVersion, payload}`로 감싼다. TOOL_START·TOOL_END·TASK_FAILED·CASE_CREATED·CASE_UPDATED 유지(CASE_*에 `handlingRoute`·`confirmationStatus` 추가). KICK_APPLIED는 ACTION_APPLIED로 대체. LIVE_ACCESS_BLOCKED payload `{liveId, activeUntilLiveEnd: true}`, 안내 문구는 FE | CONTRACTS §12 | B1·B2 |
| A-15 | MatchingTag 데이터 | `UNOFFICIAL_URL` → `LINK` 전환에 데이터 마이그레이션을 만들지 않는다. 운영 데이터가 없고 로컬 DB는 `docker compose down -v`로 초기화한다. enum 값 변경 자체는 contracts.enums PR에서 한다 | E-8, ERD §6 | B1 |
| A-16 | PII 조사의 NEEDS_REVIEW | PII_INVESTIGATION 판정 제출에서 messageFindings 중 하나라도 NEEDS_REVIEW면 사건을 확정하지 않고(confirmationStatus=PENDING) HELD를 유지하며, 새 검토 종류를 만들지 않고 기존 CONTEXT 검토(`reviews.kind = CONTEXT`, `case_id` = 그 사건)를 만든다. 응답에 그 `reviewId`와 `reviewKind=CONTEXT`를 돌려주고, Agent는 문맥 경로와 같이 그 reviewId로 interrupt한다. 운영자 결정은 문맥 결정 API(`ContextDecisionRequest`)를 그대로 쓴다. NO_VIOLATION·VIOLATION은 검토를 만들지 않는다(필수 승인으로 확장하지 않음). ERD 테이블·제약 변경은 없다 | POLICY §5, ARCHITECTURE §4 | B2 |
| A-17 | 기존 메시지의 방송 귀속 | 방송(`platform.lives`) 도입 전에 저장된 메시지는 `live_id` NULL로 둔다. 방송을 임의로 만들어 귀속하지 않는다. 그래서 `Message.liveId`는 null일 수 있다(새 메시지는 항상 값이 있음). 방송 범위 정책(FLOOD 등)은 NULL 메시지를 포함하지 않는다 | 이슈 #25, E-1 | B1 |
| A-18 | 채팅 경로와 진행 중 방송 없음 | 새 경로 `/api/lives/{liveId}/messages`와 함께 기존 `/api/channels/{channelId}/messages`·`/events`를 deprecated로 유지한다(FE 무변경). 기존 전송 경로는 그 채널의 진행 중 방송으로 귀속하고, 진행 중 방송이 없으면 409 `LIVE_ENDED`. 없는 liveId는 404. 같은 clientMessageId 재전송은 방송 종료 검사보다 먼저 확인해 첫 응답을 그대로 돌려준다(종료 직전 저장 후 응답 유실 대비). KICKED는 재전송도 거부하므로 순서가 다르다 | CONTRACTS §4, A-1 | B1 |
| A-19 | 방송 종료 연동 | HTTP·SSE 계약은 추가하지 않고, BE2용 in-process 이벤트 `platform.port.LiveEndedEvent(liveId, channelId, endedAt)`를 둔다. 종료 트랜잭션에서 발행하고 소비자는 `@TransactionalEventListener(AFTER_COMMIT)`로 받는다(롤백 시 전달 없음). 같은 liveId에 한 번만 발행한다(이미 끝난 방송 종료는 409). 채팅 SSE의 방송 종료 이벤트는 SSE envelope 작업 때 추가한다 | 이슈 #25, POLICY §10 | B1·B2 |
| A-20 | Flyway 버전 대역 순서 | B1(`V1xx`)·B2(`V2xx`) 대역을 같이 쓰므로 `spring.flyway.out-of-order=true`로 둔다. 이미 V2xx가 적용된 DB에도 나중에 추가된 V1xx가 적용된다(로컬 DB 초기화 불필요). 새 DB는 버전 순서대로 적용된다. 대역 사이 마이그레이션은 상대 대역에서 나중에 추가되는 테이블에 의존하지 않게 작성한다 | CLAUDE.md 규칙(DB 버전 대역) | B1·B2 |
| A-21 | 전송 멱등 범위 | 멱등 UNIQUE는 `(channel_id, author_id, client_message_id)`로 둔다(ERD §4의 `live_id` 기준을 고침). clientMessageId는 전송마다 새 UUID이며, channel 범위가 live 범위보다 엄격하고 legacy 경로·A-18 재전송(종료 후 첫 응답 반환)을 지원한다 | A-18, 이슈 #25 | B1 |
| A-22 | 작성자 안내 사유 저장 | `platform.messages.moderation_reason_code`(nullable, CHECK 5종, V103)에 **저장 시점 사유**를 둔다. 1차 필터의 처리 경로(규칙 확정은 욕설/링크 규칙)로 정하고 PASS는 NULL이다. `Message.moderationReasonCode`는 작성자 본인 응답에서 HELD·HIDDEN인 동안만 채운다(복구되어 PUBLIC이면 null). 이후 B2가 가시성을 바꿔도 이 값은 갱신하지 않는다. `message_hold_reasons`(E-2)와 합치지 않는다: 이 값은 저장 시점 사유, `message_hold_reasons`는 지금 남아 있는 가림 원인이다(#41·#47 이후 구현 기준) | A-3, E-2, openapi Message | B1 |
| A-23 | 사건 증거 메시지 조회 포트 | `platform.port.LiveMessageQuery.messages(liveId, messageIds)`가 `MessageView(messageId, authorId, maskedText, visibility, version, sentAt, channelId, filterResult, normalizedTextHash)`를 요청 순서대로 돌려준다. `normalizedTextHash`는 norm-v1 문장의 SHA-256(UTF-8, 소문자 hex)로 `guard.flood_marks.text_hash`와 같은 값이며, 정규화 문장 자체는 원문과 같아 넘기지 않는다. 본문은 PiiMasker로 마스킹한 값(HELD·HIDDEN 포함)이고 `authorId`는 내부 가명 부여에만 쓴다. 요청한 id를 조용히 빼지 않는다: 다른 방송(방송 도입 전 포함)의 메시지가 섞이면 `VALIDATION_FAILED`, 없는 id는 `NOT_FOUND`(둘 다면 `VALIDATION_FAILED`). 기대 버전·가시성과 달라진 경우의 `STALE_VERSION`은 `verifyTargets`의 몫이다. 가명·`snapshotId`·`evidenceVersion`·불변 증거 저장은 B2가 맡고, B1은 메시지별 현재 `version`만 제공한다. guard는 `platform.messages`·`platform.lives`를 직접 읽지 않고 이 포트와 `LiveQuery`를 쓴다(#50). 같은 문장 비교와 도배 표식은 `normalizedTextHash`로 한다 | 이슈 #25 §4, B2 합의(2026-10-21) | B1 |
| A-24 | 사건 작성자 역할 확인 불가 | `/internal/cases/{caseId}/channel-info`의 `authorRole`과 `/role`은 **현재** 채널 역할이다(사건 당시 역할 아님). 멤버십이 없거나(탈퇴 등) 확인할 수 없으면 `AuthorRole.UNKNOWN`이고 VIEWER로 대신하지 않는다. `isOfficialOperator`는 STREAMER·OFFICIAL_STAFF·MODERATOR로 확인된 경우만 true이며, UNKNOWN이면 false다. 이 false는 "공식 운영자로 확인되지 않음"이지 "시청자로 확인됨"이 아니다. 소비자는 UNKNOWN을 공식 운영자나 시청자로 추정하지 않는다(Agent 반영: liveguard-agent #57) | 이슈 #25 §5, B2 합의(2026-10-21), PR #45 | B1 |
| A-25 | AI 판정 없는 문맥 검토 | CONTEXT_REVIEW·PII_INVESTIGATION 사건에 Agent 판정이 **마감**(`liveguard.guard.judgment-fallback.deadline`, 기본 3분) 안에 오지 않거나 현재 분석 작업이 Agent 거절로 끝나면(전달 `FAILED`, `LIVE_ENDED`·`SUPERSEDED` 제외), 진행 중인 방송에 한해 AI 판정 없는 CONTEXT 검토를 연다. 메시지는 HELD 유지(자동 공개 없음, ARCHITECTURE §9 "문맥 검토 지연"). 전달 `UNKNOWN`은 실패로 단정하지 않고(§9 "응답 유실") 마감을 기다린다. 계약 스키마는 바꾸지 않는다: `aiVerdict=NEEDS_REVIEW`, `findings=[]`, `summary`로 "AI 결과 없음"을 안내한다. 운영자 결정(CONFIRM_NORMAL·CONFIRM_VIOLATION)은 재개할 Agent interrupt가 없으므로 BE가 같은 요청에서 적용하고, 아직 보내지 않은 분석 작업은 멈춘다(`DECIDED_WITHOUT_JUDGMENT`). DEFER는 PENDING 유지. 결정 전에 늦게 온 판정 중 Agent가 CONTEXT 검토를 기다리게 되는 판정(CONTEXT_REVIEW 경로, 또는 NEEDS_REVIEW 포함)은 그 검토의 AI 제안으로 붙고 검토 version을 올리며, 이후 결정은 기존대로 Agent 재개로 적용한다. PII_INVESTIGATION의 확정 판정(자동 확정·공개)과 결정 뒤에 온 판정은 409 `STALE_VERSION`. AI 판정 없이 위반을 확정해 강퇴 기준(3건)에 도달하면 KICK 검토가 그 사건에 생긴다. 이 강퇴 검토는 기다리는 Agent thread가 없으므로 재개하지 않고, 승인이면 결정과 같은 트랜잭션에서 기준을 재검증(`revalidate`)해 실행 명령(`live_actions` PENDING)을 남기고, BE의 주기 작업(`liveguard.guard.kick-without-agent.poll-interval`, 기본 1초)이 별도 트랜잭션에서 실행(`execute`)한다. 실행 명령이 결정과 함께 저장되므로 재시작해도 잃지 않는다. 재검증 실패면 검토는 INVALIDATED이고 시도하지 않은 실패(attemptCount 0)로 기록한다. 실행이 업무 오류(ApiException)로 거절되면 바로, 그 밖의 일시 오류는 최초 포함 3회(TBD-07) 뒤 실행 기록을 FAILED(`errorCode`)로 남긴다. 어느 경우든 결정은 그대로이고 ACTION_FAILED로 알린다. 사람 승인 뒤에만 실행하는 것은 같고, Agent의 `kick_user` 대신 BE가 호출하는 점만 다르다(TBD-12 예외). "Agent가 기다리지 않음"은 문맥·개인정보 사건에 판정이 없는 것으로 판단한다(이 경로의 Agent는 판정을 제출해야 결정을 기다린다). 규칙·도배 사건과 AI 판정이 있는 사건의 강퇴는 기존대로 Agent 재개로 실행한다. **넘겨받기**: 이 Agent 경로에서도 Agent가 분석 중 실패하면 재개할 interrupt가 없어 승인이 실행되지 않는다(BE는 Agent가 받은 뒤 실패했는지 알 수 없다). 그래서 승인 뒤 마감(`liveguard.guard.kick-handoff.deadline`, 환경변수 `LIVEGUARD_KICK_HANDOFF_DEADLINE`, 기본 3분) 안에 Agent가 재검증하지 않으면 BE가 같은 재검증으로 실행 명령을 남기고, 재검증했지만 마감까지 실행하지 않은 명령도 BE가 실행한다. 실패 기록·ACTION_FAILED는 위와 같다. 재검증·실행은 결정마다 같은 명령·결과를 돌려주므로 Agent가 늦게 재개해도 두 번 실행되지 않고, 이미 실행한 명령의 재검증은 검토를 무효로 돌리지 않는다. 마감은 정상 경로(승인 뒤 약 1초)와 응답이 불확실한 전달 재시도(최대 약 80초)보다 길게 둔다. Agent가 마감보다 오래 닿지 않으면(연결 실패는 재시도 예산을 쓰지 않아 백오프가 최대 5분까지 늘어난다) BE가 먼저 실행하고, 나중에 닿은 재개는 같은 결과를 받는다. 넘겨받은 명령도 같은 실행기가 실행하므로 `liveguard.guard.kick-without-agent.enabled=false`면 넘겨받기도 꺼진다. 정책 원본에 없던 항목이며 TBD-07("모르면 운영자 콘솔에 표시")과 같은 방향이다. TBD-10(검토 대기 만료)과는 별개 | ARCHITECTURE §9, TBD-07 | B2 |
