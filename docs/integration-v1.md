# liveId 통합 후속 (2026-10-21)

기준은 #39까지 병합된 코드와 `contracts/openapi.yaml`이다. 03-2에서 두 후보 경로를 하나로 연결했으며 기존 lifecycle/LiveEndedEvent·검토/재개·Rabbit Outbox를 보존한다.

## 실행 경계

방송·채팅·후보는 `liveguard.policy.version=v3.1`을 공유하는 한 경로다. 이전 fixture 스위치로 업무 경로를 분기하지 않는다. 1차 경로는 `MessageFilter`의 `FilterDecision`만 사용한다. 전송 서비스의 별도 URL/PII 규칙은 제거했으므로 현재 필터가 미제공하는 PII 후보 탐지는 여기서 새로 만들지 않는다. 명시적인 PII 후보 계약과 기존 조사·사람 검토 처리는 유지한다. 검증 사실은 UNKNOWN이며 AI의 PII 위반 주장만으로 확정하지 않는다.

V102/V202는 현재 main #36에 병합된 파일을 바이트 그대로 보존한다. V203은 targetVersion·취소 영수증·FLOOD 묶음 저장 및 RELEASED 예약 상태, V204는 별도 review별 강퇴 소유권을 추가한다. #34/#35만 적용한 DB는 #36에서 이미 합쳐진 V102/V202와 체크섬이 다를 수 있다. 해당 DB를 자동 repair하거나 삭제하지 않는다. 백업·적용 버전 확인 후 별도 이관 검토가 필요하며, 실행 증거는 새 격리 DB에서 수집한다. 기존 시험 DB도 보존한다.

V205는 후보 전체 provenance와 불변 마스킹 `case_snapshots`를 추가한다. 이전 finding의 confirmedAt/revokedAt만 기존 감사 시각(없으면 저장 시각)에서 복구한다. 미확인 방송·필터/규칙 provenance나 snapshot을 만들지는 않는다. 미준비 legacy 사건 조회는 CASE_NOT_READY다. 이미 적용한 V100~V204는 변경하지 않으며 out-of-order 적용을 유지한다.

같은 메시지의 후보 재전송은 모든 필드가 같아야 한다. 신규 후보는 실제 메시지·방송·채널·사용자·필터 결과·수신 시각과 증거 범위를 검증하고 같은 트랜잭션에서 저장한다. 열린 FLOOD 묶음은 확정 상태와 무관하게 같은 user/live/text에서 재사용하며 앞 두 메시지는 증거일 뿐 제재 대상이 아니다. 기존 재적발 동작은 유지하되 새 탐지 기능은 추가하지 않는다.

사건·문맥은 같은 snapshotId/evidenceVersion의 불변 마스킹 증거를 읽고, 대상 가시성/버전은 현재 값으로 별도 조회한다. before/after는 각각 0~10이고 문맥은 최대 20건이다. TIMEOUT 이력은 같은 방송 전체의 confirmedAt 기준, 현재 제외·유형별 취소 반영·소진 보존이다. days는 두 이력 API에서 명시적으로 거부한다. 강퇴 신규 후보는 최근 24시간 하한 제외·상한 포함, 현재 포함, 5유형 통합 distinct case이며 실제 예약·소진을 제외한다. 원 결정의 basis API는 고정 ID와 실제 review/decision 소유권만 읽는다.

방송 종료 후 B2는 KICK 검토와 대기 중 집행을 중지한다. RESERVED만 해제하며 CONSUMED와 확정 이력은 보존한다. PENDING/RETRY KICK resume만 중지하되 이전 전달이 불확실하면 FAILED가 아니라 UNKNOWN으로 남긴다. 기존 ACCEPTED/IN_FLIGHT/UNKNOWN 발송 결과나 CONTEXT 검토를 임의로 실패/취소하지 않는다. 중복 종료 이벤트는 결과를 바꾸지 않으며 집행도 방송 활성 상태를 확인한다.

기존 channel 전송은 같은 live 정책/제재 경로를 사용한다. 종료 후 같은 clientMessageId 재시도는 최초 메시지를 반환하며 새 전송은 LIVE_ENDED409다. 기존 channel 이력은 전체 방송/NULL 메시지 포함, 기존 SSE는 raw payload를 유지한다. 새 live SSE는 envelope를 사용한다.

## 사람 결정과 복구

CONTEXT는 AI 정상/위반/보류 제안 모두 운영자 결정 전 HELD다. DEFER는 같은 검토를 유지한다. 저장된 결정 조회 후 동일 thread가 재개되고 Backend가 참조·정책·버전·권한을 확인해 집행한다. KICK은 서로 다른 확정 사건 3건에 별도 사람 승인·재검증·실행 직전 권한 검사를 요구한다. 명령이 오래됐으면 거부하고 성공 응답 재시도는 역사적 결과를 반환한다.

POST /api/findings/{findingId}/revoke는 운영자·사건 버전·멱등 ID·사유를 검증한다. 자기 가림 원인만 해제하고 다른 사건의 hold는 유지한다. TIMEOUT은 남은 유효 이력으로 줄이되 연장하지 않는다. 취소된 근거에 연결된 review별 강퇴만 제거하며 다른 유효 강퇴와 이미 소진한 사건은 보존한다.

원문은 작성자와 해당 채널 MODERATOR만 볼 수 있다. STREAMER는 방송 시작/종료 권한만으로 타인 HELD/HIDDEN 원문을 얻지 못한다. 로그인 계정 교체·로그아웃 시 기존 세션 SSE를 닫는다.

## B1 조회 포트 후속 합의 (#25)

`LiveGuardPort.candidate`는 B2가 `CandidateRequest` 하나로 통합하며 규칙·필터 버전 저장까지 담당한다.
B1 제안 `LivePlatformPort.messages(liveId, messageIds)`는 후속 연결 대상이다. 마스킹 본문·내부 authorId·가시성·현재 버전·수신 시각을 제공하고,
누락 ID를 생략하거나 다른 방송 메시지를 반환하지 않는다. 범위 위반은 VALIDATION_FAILED, 기대 버전 불일치는 STALE_VERSION으로 구분한다.
현재 B2의 메시지 직접 조회는 남아 있으므로 이 포트 전환 완료로 표시하지 않는다.
AuthorRole.UNKNOWN 및 현재 역할 조회 계약은 B1 변경과 Agent 소비자 확인 후 연결한다.

## 검증 기록

Java PostgreSQL 통합 검사에 권한·원문 격리·중복 결정·DEFER·정상 복구·위반 제한·방송 종료·FLOOD·다중 강퇴 소유권·예약 재사용·오판 취소를 포함한다. 실제 모델/브라우저 증거는 Infra evidence/unified-final/report.json에서 별도로 확인한다. 테스트 통과를 운영 정책 승인이나 전체 ERD 구현 완료로 해석하지 않는다.
