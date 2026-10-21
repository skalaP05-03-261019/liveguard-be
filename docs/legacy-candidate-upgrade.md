# 기존 로컬 DB의 V200 업그레이드

BE #20. V200 이전 `InMemoryCandidateSink`는 실제 사건 행 없이 `platform.messages.case_id`에 임시 UUID를 기록했습니다. 이 메시지가 남은 DB에서는 V200의 `messages_case_fk` 검증이 실패합니다. 신규 DB에서는 나타나지 않습니다.

이 절차는 해당 **로컬 개발 DB**에 한정합니다. V200이나 다른 병합된 Flyway 파일은 수정하지 않습니다. Backend를 멈추고 전체 DB를 `pg_dump -Fc`로 백업한 뒤, 복제 DB에서 아래 SQL과 원래 V200의 적용을 먼저 검증합니다.

```bash
psql -v ON_ERROR_STOP=1 -f scripts/reconcile-legacy-candidate-links.sql
```

스크립트는 예전 메시지→임시 사건 ID 연결을 `guard.legacy_candidate_links`에 보존하고 `messages.case_id` 참조만 분리합니다. 메시지 ID·원문·작성자·시각·visibility·version은 바꾸지 않습니다. 채팅 원문을 새 로그/아카이브 테이블에 복제하지 않습니다. V200 이후의 실제 사건·snapshot·정책 버전이나 확정 판정을 추정해서 생성하지 않습니다. 기존 HELD 메시지도 자동 공개하지 않습니다.

같은 DB에서 V200 적용 전 재실행은 추가 변경이 없습니다. 이미 `guard.cases`가 있거나 보존된 연결과 다른 case_id가 있으면 트랜잭션을 거부합니다. 아카이브에는 외래키가 없으므로 이후 테스트 데이터 정리로 원본 메시지가 없어져도 이전 참조 기록은 남습니다. 기본 웹/API에는 이 테이블을 노출하지 않습니다.

그다음 Backend를 재기동해 원래 V200과 후속 마이그레이션을 적용합니다. 새 후보는 실제 `guard.cases/candidates/case_messages`와 불변 마스킹 snapshot에 저장됩니다. 03-2 통합 경로는 규칙·도배를 BE 확정하고 문맥/PII 조사를 outbox에 연결하지만, 이 업그레이드 절차 자체는 과거 AI 제안·확정 판정·미준비 snapshot을 생성하지 않습니다.

복구가 필요하면 서비스를 멈추고 백업을 별도 DB에 복원하여 연결 대상을 전환합니다. 정상 적용된 V200을 임의로 되돌리거나 원본 DB에 백업을 덮어쓰지 않습니다. 실제 사건이 생성된 뒤 legacy 참조를 다시 덮으면 새 데이터와 충돌할 수 있으므로 자동 역변환은 제공하지 않습니다.
