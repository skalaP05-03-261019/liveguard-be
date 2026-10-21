# liveId 통합 시험: BE #29 (2026-10-21)

기준 main은 계약/enum PR #32 (`9a3657d`)다. 팀의 DTO·enum·필터 변경을 보존한다. 저장 결정 조회·적용 경로는 이슈 #29에서 사전 고지한 통합 제안이다.

## 실행 경계

방송 시작은 `LIVEGUARD_POLICY_VERSION=v3.1`과 `LIVEGUARD_TBD_FIXTURE_ENABLED=true`를 함께 지정한 경우에만 허용한다. 미정 정책 제안은 팀 승인된 운영 기본값으로 승격하지 않는다. fixture가 꺼지면 기존 채널 API가 유지된다. 켜진 동안에는 오래된 채널 메시지/이력/SSE 경로를 409로 거부하여 liveId 제재를 우회하지 못하게 한다.

V102/V202는 기존 테이블·행을 보존하는 별도 통합 스키마다. ERD v1 전체 구현이나 운영 데이터 이관을 대체하지 않는다. 기존 Flyway 마이그레이션은 수정하지 않았다.

## 관찰한 동작

실제 PostgreSQL, Outbox, Agent, Business/RAG MCP, OpenAI, 브라우저로 일반 채팅/SSE, HELD 비작성자 격리, AI 조사 카드, DEFER 동일 검토 유지, 정상 확정 후 PUBLIC 복구, 위반 확정 후 HIDDEN/TIMEOUT 403(until)을 확인했다. Java Postgres 통합 시험은 사람 결정의 중복·버전 검사, 다른 사건 hold 보존, 방송 종료 후 적용 차단, 별도 KICK 승인/거절·기준 사건 소진·재집행 중복 방지도 확인한다.

## 내부 연동 제안

- GET `/internal/reviews/{reviewId}/decisions/{decisionId}`: 저장된 사람 결정과 case/live/snapshot/evidence/policy/task/thread 참조 조회. 모델·resume 본문의 승인을 신뢰하지 않는다.
- POST `/internal/reviews/{reviewId}/apply`: 위 참조 전체와 최종 결정 ID로 CONTEXT를 같은 thread에서 적용한다. DEFER는 적용하지 않는다.
- 기존 revalidate 응답의 actionCommand 확장: Backend가 저장·발급한 KICK 명령만 기존 execute 경로에서 허용한다.
- review 목록은 화면에 필요한 마스킹된 조사 근거·문맥 projection을 포함한다.

## 병합 전 남은 항목

이 PR은 Draft다. 전체 OpenAPI 응답 projection/페이지네이션과 내부 확장 계약의 팀 합의, KICK 사용자 target version의 실제 소유권·버전 연계(현재 고정 1), finding 취소 후 가림/제한 복구, FLOOD 그룹 최초 확정 흐름, PII의 서버 검증 사실 제공은 남아 있다. PII 패턴만으로 위반을 확정하지 않으며 모호하면 사람 CONTEXT 검토로 보낸다. KICK은 Java 통합 테스트를 통과했으나 실제 화면→MCP 전체 E2E 완료로 표시하지 않는다.

`contracts/openapi.yaml` 원본 v1을 구현 완료로 주장하지 않는다. 현재 로컬 시험에 사용한 추가 projection과 내부 경로는 위 목록과 코드·Agent #48·FE #2에서 확인한다. 통합 증거는 Infra `evidence/v1-browser/report.json`에 있다.

## 병행 팀원 PR

후속 확인에서 BE #34(방송 생명주기), #35(RabbitMQ Outbox)가 열려 있다. 이 Draft를 먼저 병합해 팀원 구현을 덮어쓰지 않는다. #34의 실제 방송 저장/필터 경계가 병합된 뒤 이 통합 구현을 해당 포트에 맞춰 줄이고, #35 전달 보장은 별도 시험한다. 현재 증거는 격리한 시험 브랜치의 증거다.
