# LIVE:GUARD 사후 검토 매뉴얼

문서 ID MM · 정책 세트 0.3-draft · 대상 MODERATOR·PLATFORM_ADMIN·구현 담당

## MM-1.1 최초 조사·자동 실행

1. 고정된 사건·증거 스냅샷을 로드하고 마스킹한다.
2. 모델이 필요한 조회 도구·정책 검색을 선택한다. 도배는 전송·중복, 괴롭힘은 대상·발언·이력, 사칭은 역할·공식 주소·이벤트를 확인한다.
3. VIOLATION/NO_VIOLATION/NEEDS_REVIEW와 근거 ID·조항·부족 정보를 구조화한다.
4. 서버가 requestedActions를 검증하고 planId·actionId별 ActionCommand를 발급한다. 최초 실행은 운영자 승인 대기를 두지 않는다.
5. 실행 노드가 변경 도구를 호출하고 get_action_status로 반영 여부를 확인한다.
6. 결과를 운영자에게 후보고하고 작성자에게 사유·기간·이의제기 방법을 안내한다.

모델·반복·활성 시간 기본안은 최대 8회·조회 도구별 3회·120초다. 사람 대기는 활성 시간에서 제외한다. 초기 실행 이후 추가 재시도 2회는 SDK·Graph·Worker 전체의 공유 예산이며 보고는 별도다. 재시작이 예산을 초기화하지 않는다.

## MM-1.2 후보고·본인 조치 화면

운영자에게 사건·메시지별 공개 상태, 판단·근거, 정책 원문·버전, 조치별 실제 결과, startedAt/expiresAt, 보고 상태, 실패·UNKNOWN·다음 확인을 보여준다. 작성자에게 본인 조치 사유·기간·이의제기 경로를 제공하며 타인 이력·내부 trace는 제외한다.

조치 성공 후 통지만 실패했다면 NOTIFY만 재시도한다. DONE 이벤트나 사건 ACTIONED를 모든 조치·통지의 성공으로 해석하지 않는다. 조치별 결과를 표시한다.

## MM-2.1 예외 검토

근거 부족·충돌, 계속 변하는 사건, 실패·UNKNOWN은 운영자 검토 목록으로 보낸다. 기존 HELD는 오류만으로 공개하지 않고 PUBLIC도 오류만으로 가리지 않는다. 검토 기한 기본 제안은 접수 후 10분 목표·초과 알림이며, 시간이 지났다는 이유로 자동 위반 확정이나 공개를 하지 않는다.

원본 `/api/cases/{caseId}/manual-decision` 계약의 NO_CHANGE/RELEASE_HELD/APPLY_POLICY를 사용한다. 현재 근거·유효 조항·버전·정책 상한을 검증한다. 수동 경로라고 최대 기간을 늘리지 않는다. UNKNOWN은 새 조치를 만들기 전에 원 actionId를 조회한다.

## MM-3.1 이의제기 접수·조사

- VIEWER는 본인 actionId에 reason·clientRequestId로 접수한다. 같은 조치의 열린 이의제기는 하나다.
- 메시지 숨김과 사용자 제한은 같은 사건이어도 별도 조치다. 접수만으로 둘을 함께 취소하지 않는다.
- Agent는 원조치·정책 스냅샷·추가 맥락을 정리하고 REVIEW_PENDING에서 체크포인트를 저장한다.
- 운영자가 UPHOLD 또는 OVERTURN과 사유·expectedVersion을 제출한다.
- 저장된 decisionId를 전달해 **같은 이의제기 thread**를 재개한다. 최초 조사 thread를 무기한 기다리게 하지 않는다.

작업 ID: 최초 `case:{case_id}:analysis:{revision}`, 이의제기 `case:{case_id}:appeal:{appeal_id}`. 큐 eventId·taskId 중복을 제거한다. 모델의 판단·운영자 결정·실제 복구 결과를 별도로 보존한다.

## MM-3.2 유지·취소·복구

| 결정 | 실행·결과 |
|---|---|
| UPHOLD | 원조치 유지, 기존 만료 유지·연장 없음, 사유 통지 |
| OVERTURN + HIDE | decisionId+originalActionId 검증 후 RESTORE_MESSAGE·상태 확인 |
| OVERTURN + TIMEOUT | 특정 원 제한 REVOKE_TIMEOUT·상태 확인 |
| OVERTURN + 이미 만료 | 잘못된 이력 취소·정정, 만료를 다시 시작하지 않음 |
| 복구 실패·UNKNOWN | 취소 결정은 보존하되 restorationStatus에 미완료·불명을 표시 |

다른 원인으로 숨김·제한이 여전히 유효하면 그것까지 함께 해제하지 않는다. 한 조치의 취소를 사용자 모든 제재 해제로 처리하지 않는다. 취소된 위반은 이후 가중 이력에서 제외하고 원기록과 연결한다.

## MM-4.1 결과 불명·부분 성공

1. 응답 유실이면 UNKNOWN·applied=null로 두고 actionId로 확인한다.
2. 이미 적용됐으면 SUCCEEDED로 정합화하고 재실행하지 않는다.
3. 미반영과 처리 중 요청 부재가 확인된 일시 오류만 같은 키로 재시도한다.
4. 권한·정책·버전·증거 오류는 반복 실행하지 않고 검토한다.
5. 미반영 확인 후 예산 소진은 FAILED, 끝까지 확인 불가는 UNKNOWN을 유지한다.

HIDE 성공 후 TIMEOUT 실패를 시험하려면 HIDE 성공을 확인한 뒤 TIMEOUT에 오류를 주입한다. 성공한 숨김은 유지하고 같은 actionId의 미완료 단계만 재처리한다. 통지 실패를 제재 재실행으로 복구하지 않는다. 제한 만료는 재시도 때 다시 계산하지 않는다.

## MM-4.2 작업 전달·대기열

Day 1 HTTP로 연결하고 Day 2 RabbitMQ 기본 전환을 목표로 한다. 둘은 동일 outbox·taskId·실행 함수를 사용하며 장애 시 양쪽으로 동시에 발행하지 않는다.

| kind | 용도 |
|---|---|
| ANALYZE/REANALYZE | 최초 조사·새 증거 후속 조사 |
| RESUME_REVIEW | 저장된 운영자 결정 후 재개, decisionId 필수 |
| NOTIFY | 운영자 후보고·작성자 통지 |
| RECONCILE | UNKNOWN의 원조치 반영 여부 확인 |

작업·체크포인트를 저장한 뒤 ACK한다. 사람 대기 상태도 저장 후 ACK해 Worker를 점유하지 않는다. 큐에는 ID·버전만 담고 원문·토큰을 넣지 않는다. DLQ는 사유·시도 수와 함께 확인 대상으로 표시한다.

## MM-5.1 상태·이벤트 계약

| 대상 | 상태 |
|---|---|
| 사건 | COLLECTING→ANALYZING→NO_ACTION/ACTIONED/NEEDS_REVIEW |
| 작업 | QUEUED→RUNNING→WAITING_REVIEW/SUCCEEDED/FAILED |
| 조치 | PENDING/RUNNING/SUCCEEDED/FAILED/UNKNOWN |
| 이의제기 | OPEN→REVIEW_PENDING→UPHELD/OVERTURNED |
| 복구 | 취소 결정과 별도 ActionResult·restorationStatus |

SSE는 원본 부록 A.3의 CHAT, MESSAGE_VISIBILITY_CHANGED, MESSAGE_RESTORED, CASE_CREATED/STATUS, TOOL_START/END, ACTION_APPLIED, REVIEW_REQUIRED, APPEAL_RESOLVED, DONE, FAILED, ACTION_UNKNOWN, RESYNC_REQUIRED를 따른다. REVIEW_REQUIRED는 최초 조치 승인 요청이 아니다.

eventId 중복·오래된 entityVersion을 무시하고 보존 범위 밖이면 최신 이력으로 재동기화한다. 로그아웃·세션 만료·권한 회수 시 연결 종료. 원문 전송 권한은 발행·재전달 모두 검사한다.

## MM-6.1 MCP·정책 근거

조회: get_moderation_case, get_chat_context, get_user_history, get_stream_info, get_channel_user_role, get_official_links, get_action_status. 검색: search_policy, get_policy_section, 버전 포함 Resource. 변경: hide_message, timeout_user, release_message, revoke_timeout.

입출력은 최신 기획서 부록 A.5를 따르며 이전 get_case/restore_message 계약을 복사하지 않는다. Resource와 조회 도구는 같은 정책 원본을 읽고 version을 일치시킨다. 당시 스냅샷을 보존해 정책 개정 후에도 원래 판단의 근거를 확인한다.

## MM-7.1 시연 체크

A 정상 과장·인용 → 공개 유지 또는 정상 HELD 자동 해제.

B 반복 괴롭힘 3건/5분 → 자동 HIDE·채널 300초 제한 → 실제 전송 거부 → 후보고.

C 공식 사칭·외부 로그인 유도 → 초기 HOLD → 역할·공식 주소 조사 → HIDE·후보고.

D 응답 유실/미반영/보고만 실패 → UNKNOWN 정합화·공유 예산 재시도·제재 중복 없음.

E 이의제기 → 운영자 대기·재시작 → 같은 thread 재개 → 원조치만 취소·복구·결과 통지.

문서 사례·정책표와 실제 호출·DB·공개 범위·재개 증거는 구분한다. 현재는 문서만 최신화한 상태다.
