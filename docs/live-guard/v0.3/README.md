> **구현 기준 아님.** 정책은 [docs/policy/](../../policy/README.md) 우선. [evaluation_cases.md](policies/evaluation_cases.md)는 평가 사례로 계속 사용.

# LIVE:GUARD 기획서·정책 v0.3

실시간 라이브 채팅의 맥락과 자체 정책을 조사해 허용된 조치를 자동 실행하고, 후보고·이의제기·복구를 연결하는 캡스톤 프로젝트의 문서입니다.

## 읽는 순서

| 순서 | 문서 | 내용 |
|---|---|---|
| 1 | [프로젝트 기획서 v0.3](proposal.md) | 전체 서비스·역할·일정·API/SSE/MCP/State 계약 |
| 2 | [분류·조치 기준 및 변경사항](policies/classification-and-actions.md) | v0.2와의 차이, 자동 실행 범위, 정책 연결 |
| 3 | [커뮤니티 정책](policies/community_policy.md) | 반복 괴롭힘·도배·정상 예외 |
| 4 | [자동 조치 정책](policies/moderation_policy.md) | 5분 상한·조치 ID·이력·UNKNOWN |
| 5 | [보안 위험 대응 정책](policies/security_policy.md) | 사칭·외부 유도·공식 주소·마스킹 |
| 6 | [사후 검토 매뉴얼](policies/moderation_manual.md) | 후보고·이의제기·유지/취소·실제 복구 |
| 7 | [평가 사례](policies/evaluation_cases.md) | 개발용 20건·보류용 10건·별도 시스템 시험 30건 |

## 적용 기준

- 전체 구현 계약은 기획서 v0.3과 부록 A를 기준으로 합니다.
- 정책 문서는 0.3-draft이며 Day 0에 수치·사례·정책 버전을 팀에서 동결합니다.
- 최초 조치는 정책·권한·최신 상태 검증 후 자동 실행하고, 사람의 검토는 이의제기·예외 처리에 적용합니다.
- 자동 채팅 제한 기본 상한은 해당 채널 5분입니다. 영구 제한과 실제 플랫폼 연동은 제외합니다.
- 문서·사례 작성은 구현·모델 평가·플랫폼 연동 검증 완료를 뜻하지 않습니다.

## 원본 보존

proposal.md는 전달받은 Markdown을 내용 변경 없이 복사했습니다. 정책 파일은 원문을 유지하고, 업로드에 포함하지 않는 로컬 조사 문서 링크만 저장소에서 읽을 수 있도록 정리했습니다.

기획서 SHA-256: `d0bf9b0210be8bb0a3ea0dc2e90abe17c22b9c1d09ee2a293ef4c0b435ab8402`
