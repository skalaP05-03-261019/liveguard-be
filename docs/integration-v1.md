# liveId 통합 후속 (2026-10-21)

기준은 BE #34·#35·#36 병합 main `f23f37e`와 `contracts/openapi.yaml`이다. 팀 lifecycle/LiveQuery/LiveEndedEvent/MessageService와 Rabbit Outbox를 보존하며, 검토·집행·오판 복구를 연결한다.

## 실행 경계

방송 lifecycle은 fixture 없이도 동작한다. 통합 정책 처리만 `LIVEGUARD_POLICY_VERSION=v3.1`, `LIVEGUARD_TBD_FIXTURE_ENABLED=true`에서 활성화된다. TBD 정책 제안을 승인된 운영 기본값으로 승격하지 않는다. PII 검증 사실은 UNKNOWN이며 패턴만으로 위반을 확정하지 않는다. NEEDS_REVIEW는 CONTEXT 사람 검토로 중단한다.

V102/V202는 현재 main #36에 병합된 파일을 바이트 그대로 보존한다. V203은 targetVersion·취소 영수증·FLOOD 묶음 저장 및 RELEASED 예약 상태, V204는 별도 review별 강퇴 소유권을 추가한다. #34/#35만 적용한 DB는 #36에서 이미 합쳐진 V102/V202와 체크섬이 다를 수 있다. 해당 DB를 자동 repair하거나 삭제하지 않는다. 백업·적용 버전 확인 후 별도 이관 검토가 필요하며, 실행 증거는 새 격리 DB에서 수집한다. 기존 시험 DB도 보존한다.

기존 channel 전송은 같은 live 정책/제재 경로를 사용한다. 종료 후 같은 clientMessageId 재시도는 최초 메시지를 반환하며 새 전송은 LIVE_ENDED409다. 기존 channel 이력은 전체 방송/NULL 메시지 포함, 기존 SSE는 raw payload를 유지한다. 새 live SSE는 envelope를 사용한다.

## 사람 결정과 복구

CONTEXT는 AI 정상/위반/보류 제안 모두 운영자 결정 전 HELD다. DEFER는 같은 검토를 유지한다. 저장된 결정 조회 후 동일 thread가 재개되고 Backend가 참조·정책·버전·권한을 확인해 집행한다. KICK은 서로 다른 확정 사건 3건에 별도 사람 승인·재검증·실행 직전 권한 검사를 요구한다. 명령이 오래됐으면 거부하고 성공 응답 재시도는 역사적 결과를 반환한다.

POST /api/findings/{findingId}/revoke는 운영자·사건 버전·멱등 ID·사유를 검증한다. 자기 가림 원인만 해제하고 다른 사건의 hold는 유지한다. TIMEOUT은 남은 유효 이력으로 줄이되 연장하지 않는다. 취소된 근거에 연결된 review별 강퇴만 제거하며 다른 유효 강퇴와 이미 소진한 사건은 보존한다.

원문은 작성자와 해당 채널 MODERATOR만 볼 수 있다. STREAMER는 방송 시작/종료 권한만으로 타인 HELD/HIDDEN 원문을 얻지 못한다. 로그인 계정 교체·로그아웃 시 기존 세션 SSE를 닫는다.

## 검증 기록

Java PostgreSQL 통합 검사에 권한·원문 격리·중복 결정·DEFER·정상 복구·위반 제한·방송 종료·FLOOD·다중 강퇴 소유권·예약 재사용·오판 취소를 포함한다. 실제 모델/브라우저 증거는 Infra evidence/unified-final/report.json에서 별도로 확인한다. 테스트 통과를 운영 정책 승인이나 전체 ERD 구현 완료로 해석하지 않는다.
